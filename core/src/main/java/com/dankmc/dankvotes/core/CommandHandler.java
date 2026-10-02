package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The player and admin commands (/vote, /votes, /votetop, /voteparty, /dankvotes), written
 * once for every platform. A platform only adapts its command sender to {@link Sender},
 * supplies the current core and a reload action, and registers the commands that
 * {@link #enabledCommands} returns: a command switched off in config.yml is never registered,
 * so another plugin can use the name.
 */
public final class CommandHandler {

    public static final String PERM_ADMIN = "dankvotes.admin";
    public static final String PERM_VOTE = "dankvotes.vote";
    public static final String PERM_VOTES = "dankvotes.votes";
    public static final String PERM_VOTES_OTHERS = "dankvotes.votes.others";
    public static final String PERM_VOTETOP = "dankvotes.votetop";
    public static final String PERM_VOTEPARTY = "dankvotes.voteparty";

    /** Canonical command names and their aliases, identical on every platform. */
    public static final String[][] COMMANDS = {
        {"vote"},
        {"votes", "myvotes"},
        {"votetop", "topvotes", "topvoters"},
        {"voteparty", "vp"},
        {"dankvotes", "dv", "dankvote"},
    };

    public static final List<String> ADMIN_SUBCOMMANDS = Collections.unmodifiableList(Arrays.asList(
        "help", "reload", "status", "test", "setvotes", "addvotes", "reset", "party", "key", "version"));

    /** Whoever ran the command, as the platform sees them. */
    public interface Sender {
        /** The player's name, or null for the console (or any other non-player sender). */
        String playerName();

        /** A plain permission check, used for the admin and "view others" nodes. */
        boolean hasPermission(String permission);

        /**
         * Player commands (/vote, /votes, /votetop, /voteparty) are open to everyone unless a
         * permissions plugin explicitly denies the node, like Bukkit's "default: true".
         */
        boolean allowedByDefault(String permission);

        /** Send one line written with legacy '&' colour codes. */
        void sendLegacy(String line);
    }

    /** Supplies the running core (null while DankVotes is stopped or reloading). */
    public interface CoreAccess {
        DankVotesCore core();
    }

    private final CoreAccess access;
    private final Runnable reload;
    private final String platformName;
    /** Canonical names of the commands the platform registered (null until it says). */
    private volatile Set<String> registered;

    /**
     * @param access       returns the current core; a reload replaces it
     * @param reload       stops the core, re-reads config.yml and starts a new core
     * @param platformName shown by /dankvotes version; null shows the core's platform name
     */
    public CommandHandler(CoreAccess access, Runnable reload, String platformName) {
        this.access = access;
        this.reload = reload;
        this.platformName = platformName;
    }

    // ── registration ─────────────────────────────────────────────────

    /**
     * The commands to register for this config, each as {name, aliases...}. /dankvotes is
     * always included; a player command is left out when config.yml turns it (or the part of
     * DankVotes it belongs to) off.
     */
    public static List<String[]> enabledCommands(DankVotesConfig config) {
        List<String[]> out = new ArrayList<String[]>();
        for (String[] names : COMMANDS) {
            if (config == null || config.commandEnabled(names[0])) out.add(names.clone());
        }
        return out;
    }

    /** Tell the handler which commands (canonical names) the platform registered. */
    public void setRegistered(Collection<String> canonicalNames) {
        registered = canonicalNames == null ? null : Collections.unmodifiableSet(new HashSet<String>(canonicalNames));
    }

    /** One log line: the commands registered, and the ones left free with the setting that keeps them off. */
    public static String registrationSummary(DankVotesConfig config) {
        List<String> on = new ArrayList<String>();
        List<String> off = new ArrayList<String>();
        for (String[] names : COMMANDS) {
            String reason = config.offReason(names[0]);
            if (reason == null) on.add("/" + names[0]);
            else off.add("/" + names[0] + " (" + reason + ")");
        }
        String line = "Commands: " + join(on, ", ");
        if (!off.isEmpty()) line += " - not registered, free for other plugins: " + join(off, ", ");
        return line;
    }

    /** What /help and the platforms' command lists show for a command. */
    public static String description(String command) {
        String name = canonical(command);
        if ("vote".equals(name)) return "Show the server's vote links";
        if ("votes".equals(name)) return "Show your (or another player's) vote count and streak";
        if ("votetop".equals(name)) return "Show the top voters leaderboard";
        if ("voteparty".equals(name)) return "Show progress towards the next vote party";
        return "DankVotes admin commands";
    }

    /** The arguments a command takes ("[player]"), without the command itself. */
    public static String arguments(String command) {
        return arguments(command, null);
    }

    /** Like {@link #arguments(String)}, listing only the /dankvotes subcommands this config switches on. */
    public static String arguments(String command, DankVotesConfig config) {
        String name = canonical(command);
        if ("votes".equals(name)) return "[player]";
        if ("votetop".equals(name)) return "[page]";
        if ("dankvotes".equals(name)) return "<" + join(subcommands(config), "|") + ">";
        return "";
    }

    /** The permission node a player needs to see and use a command, or null when the handler decides (/dankvotes). */
    public static String permission(String command) {
        String name = canonical(command);
        if ("vote".equals(name)) return PERM_VOTE;
        if ("votes".equals(name)) return PERM_VOTES;
        if ("votetop".equals(name)) return PERM_VOTETOP;
        if ("voteparty".equals(name)) return PERM_VOTEPARTY;
        return null;
    }

    /** /dankvotes subcommands this config switches on. */
    public static List<String> subcommands(DankVotesConfig config) {
        List<String> out = new ArrayList<String>();
        for (String sub : ADMIN_SUBCOMMANDS) {
            if (config == null || config.subcommandEnabled(sub)) out.add(sub);
        }
        return out;
    }

    /** Resolve an alias ("dv", "myvotes", ...) to its canonical command name, or null. */
    public static String canonical(String label) {
        if (label == null) return null;
        String l = label.toLowerCase(Locale.ROOT);
        int colon = l.indexOf(':');                      // "dankvotes:vote" namespaced labels
        if (colon >= 0) l = l.substring(colon + 1);
        for (String[] names : COMMANDS) {
            for (String n : names) if (n.equals(l)) return names[0];
        }
        return null;
    }

    // ── running commands ─────────────────────────────────────────────

    /** Run a command. {@code command} is a canonical name or alias. */
    public void execute(String command, Sender sender, String[] args) {
        String name = canonical(command);
        if (name == null) return;
        if (args == null) args = new String[0];
        DankVotesCore core = access.core();
        if (core == null) {
            // A reload that failed leaves no core; let an admin fix config.yml and reload again.
            if (name.equals("dankvotes") && args.length > 0 && args[0].equalsIgnoreCase("reload") && sender.hasPermission(PERM_ADMIN)) {
                reload(sender);
                return;
            }
            sender.sendLegacy("&cDankVotes is not running.");
            return;
        }
        CommandText text = new CommandText(core);
        DankVotesConfig cfg = core.getConfig();
        if (!cfg.commandEnabled(name)) {
            // Only reachable between a /dankvotes reload that turned it off and the next restart.
            sender.sendLegacy("voteparty".equals(name) && !cfg.votePartyEnabled ? text.voteParty() : text.commandDisabled());
            return;
        }
        if (name.equals("vote")) vote(sender, text);
        else if (name.equals("votes")) votes(sender, text, args);
        else if (name.equals("votetop")) voteTop(sender, text, args);
        else if (name.equals("voteparty")) voteParty(sender, text);
        else admin(core, sender, text, args);
    }

    /** Tab completion for a command. */
    public List<String> complete(String command, Sender sender, String[] args, Collection<String> onlineNames) {
        String name = canonical(command);
        if (name == null || args == null) return Collections.emptyList();
        DankVotesCore core = access.core();
        DankVotesConfig cfg = core == null ? null : core.getConfig();
        if (cfg != null && !cfg.commandEnabled(name)) return Collections.emptyList();
        if (name.equals("votes")) {
            return args.length <= 1 && sender.hasPermission(PERM_VOTES_OTHERS)
                ? filter(onlineNames, args.length == 1 ? args[0] : "") : Collections.<String>emptyList();
        }
        if (name.equals("dankvotes") && sender.hasPermission(PERM_ADMIN)) {
            List<String> subs = subcommands(cfg);
            if (args.length <= 1) return filter(subs, args.length == 1 ? args[0] : "");
            if (args.length == 2) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                boolean takesPlayer = sub.equals("test") || sub.equals("setvotes") || sub.equals("addvotes") || sub.equals("reset");
                if (takesPlayer && subs.contains(sub)) return filter(onlineNames, args[1]);
            }
        }
        return Collections.emptyList();
    }

    // ── player commands ──────────────────────────────────────────────

    private void vote(Sender s, CommandText text) {
        if (!s.allowedByDefault(PERM_VOTE)) { s.sendLegacy(text.noPermission()); return; }
        send(s, text.vote(s.playerName()));
    }

    private void votes(Sender s, CommandText text, String[] args) {
        if (!s.allowedByDefault(PERM_VOTES)) { s.sendLegacy(text.noPermission()); return; }
        String player = s.playerName();
        String viewer = player != null ? player : "CONSOLE";
        String target = args.length > 0 ? args[0] : null;
        if (target == null && player == null) {
            s.sendLegacy(text.usage("/votes <player>"));
            return;
        }
        if (target != null && !target.equalsIgnoreCase(viewer) && !s.hasPermission(PERM_VOTES_OTHERS)) {
            s.sendLegacy(text.noPermission());
            return;
        }
        s.sendLegacy(text.votes(viewer, target));
    }

    private void voteTop(Sender s, CommandText text, String[] args) {
        if (!s.allowedByDefault(PERM_VOTETOP)) { s.sendLegacy(text.noPermission()); return; }
        int page = 1;
        if (args.length > 0) {
            try { page = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        send(s, text.voteTop(page, 10));
    }

    private void voteParty(Sender s, CommandText text) {
        if (!s.allowedByDefault(PERM_VOTEPARTY)) { s.sendLegacy(text.noPermission()); return; }
        s.sendLegacy(text.voteParty());
    }

    // ── /dankvotes ───────────────────────────────────────────────────

    private void admin(DankVotesCore core, Sender s, CommandText text, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            for (String line : text.help(s.hasPermission(PERM_ADMIN)).split("\n")) s.sendLegacy(line);
            return;
        }
        if (!s.hasPermission(PERM_ADMIN)) { s.sendLegacy(text.noPermission()); return; }

        String sub = args[0].toLowerCase(Locale.ROOT);
        DankVotesConfig cfg = core.getConfig();
        DankVotesConfig.Messages m = cfg.messages;
        if (!cfg.subcommandEnabled(sub)) { s.sendLegacy(text.commandDisabled()); return; }

        if (sub.equals("reload")) {
            reload(s);
            return;
        }
        if (sub.equals("status")) { send(s, text.status()); return; }
        if (sub.equals("version")) {
            String platform = platformName != null ? platformName : core.getPlatform().getPlatformName();
            s.sendLegacy(m.prefix + "&7DankVotes &fv" + core.getPlatform().getPluginVersion() + " &7on &f" + platform);
            return;
        }
        if (sub.equals("key")) { send(s, text.key()); return; }
        if (sub.equals("party")) {
            core.getEngine().forceVoteParty();
            s.sendLegacy(m.prefix + m.partyForced);
            return;
        }
        if (sub.equals("test")) {
            String target = args.length > 1 ? args[1] : (s.playerName() != null ? s.playerName() : "TestPlayer");
            boolean counted = core.getEngine().processVote(new Vote(target, "test", "127.0.0.1", System.currentTimeMillis(), true, 0));
            s.sendLegacy(m.prefix + (counted ? m.testVote : m.testVoteRefused).replace("%player%", target));
            return;
        }
        if (sub.equals("setvotes") || sub.equals("addvotes")) {
            if (args.length < 3) { s.sendLegacy(text.usage("/dankvotes " + sub + " <player> <amount>")); return; }
            int n;
            try { n = Integer.parseInt(args[2]); } catch (NumberFormatException e) {
                s.sendLegacy(text.usage("/dankvotes " + sub + " <player> <amount>"));
                return;
            }
            int value = sub.equals("setvotes") ? n : core.getStorage().getVoteCount(args[1]) + n;
            core.getStorage().setVoteCount(args[1], value);
            core.getStorage().flush();
            s.sendLegacy(m.prefix + m.votesSet.replace("%player%", args[1]).replace("%votes%", String.valueOf(Math.max(0, value))));
            return;
        }
        if (sub.equals("reset")) {
            if (args.length < 2) { s.sendLegacy(text.usage("/dankvotes reset <player>")); return; }
            core.getStorage().resetPlayer(args[1]);
            core.getStorage().flush();
            s.sendLegacy(m.prefix + m.votesReset.replace("%player%", args[1]));
            return;
        }
        s.sendLegacy(text.usage("/dankvotes <" + join(subcommands(cfg), "|") + ">"));
    }

    /** Stop the core, re-read config.yml and start a new core, telling the sender how it went. */
    private void reload(Sender s) {
        try {
            reload.run();
        } catch (RuntimeException e) {
            s.sendLegacy("&cReload failed (" + e + ") - see the console. Fix config.yml and run /dankvotes reload again.");
            return;
        }
        DankVotesCore fresh = access.core();
        if (fresh == null) {
            s.sendLegacy("&cReload failed - see the console. Fix config.yml and run /dankvotes reload again.");
            return;
        }
        DankVotesConfig.Messages fm = fresh.getConfig().messages;
        s.sendLegacy(fm.prefix + fm.reloaded);
        String note = restartNote(fresh.getConfig());
        if (note != null) s.sendLegacy(fm.prefix + note);
    }

    /**
     * After a reload: commands are registered with the server once, at startup, so a change to
     * which ones are on needs a restart. Returns the line saying so, or null when nothing changed.
     */
    String restartNote(DankVotesConfig config) {
        Set<String> now = registered;
        if (now == null) return null;
        List<String> add = new ArrayList<String>();
        List<String> remove = new ArrayList<String>();
        for (String[] names : COMMANDS) {
            boolean wanted = config.commandEnabled(names[0]);
            boolean present = now.contains(names[0]);
            if (wanted && !present) add.add("/" + names[0]);
            if (!wanted && present) remove.add("/" + names[0]);
        }
        if (add.isEmpty() && remove.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("&eRestart the server to ");
        if (!add.isEmpty()) sb.append("register ").append(FeatureReview.join(add));
        if (!add.isEmpty() && !remove.isEmpty()) sb.append(" and to ");
        if (!remove.isEmpty()) sb.append("free ").append(FeatureReview.join(remove)).append(" (turned off until then)");
        return sb.append('.').toString();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static void send(Sender s, List<String> lines) {
        for (String line : lines) s.sendLegacy(line);
    }

    static List<String> filter(Collection<String> options, String prefix) {
        List<String> out = new ArrayList<String>();
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        Collections.sort(out);
        return out;
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
