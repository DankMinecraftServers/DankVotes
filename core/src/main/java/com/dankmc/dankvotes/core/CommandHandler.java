package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The player and admin commands (/vote, /votes, /votetop, /voteparty, /dankvotes) written
 * once for every platform that doesn't ship its own command classes (BungeeCord, Sponge).
 * Behaviour matches the Bukkit and Velocity commands exactly; a platform only adapts its
 * command sender to {@link Sender} and supplies the current core and a reload action.
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

    /**
     * @param access       returns the current core; a reload replaces it
     * @param reload       stops the core, re-reads config.yml and starts a new core
     * @param platformName shown by /dankvotes version
     */
    public CommandHandler(CoreAccess access, Runnable reload, String platformName) {
        this.access = access;
        this.reload = reload;
        this.platformName = platformName;
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

    /** Run a command. {@code command} is a canonical name or alias. */
    public void execute(String command, Sender sender, String[] args) {
        String name = canonical(command);
        if (name == null) return;
        if (args == null) args = new String[0];
        DankVotesCore core = access.core();
        if (core == null) {
            sender.sendLegacy("&cDankVotes is not running.");
            return;
        }
        CommandText text = new CommandText(core);
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
        if (name.equals("votes")) {
            return args.length == 1 && sender.hasPermission(PERM_VOTES_OTHERS)
                ? filter(onlineNames, args[0]) : Collections.<String>emptyList();
        }
        if (name.equals("dankvotes") && sender.hasPermission(PERM_ADMIN)) {
            if (args.length <= 1) return filter(ADMIN_SUBCOMMANDS, args.length == 1 ? args[0] : "");
            if (args.length == 2) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("test") || sub.equals("setvotes") || sub.equals("addvotes") || sub.equals("reset")) {
                    return filter(onlineNames, args[1]);
                }
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
        DankVotesConfig.Messages m = core.getConfig().messages;

        if (sub.equals("reload")) {
            reload.run();
            DankVotesCore fresh = access.core();
            DankVotesConfig.Messages fm = fresh != null ? fresh.getConfig().messages : m;
            s.sendLegacy(fm.prefix + fm.reloaded);
            return;
        }
        if (sub.equals("status")) { send(s, text.status()); return; }
        if (sub.equals("version")) {
            s.sendLegacy(m.prefix + "&7DankVotes &fv" + core.getPlatform().getPluginVersion() + " &7on &f" + platformName);
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
            core.getEngine().processVote(new Vote(target, "test", "127.0.0.1", System.currentTimeMillis(), true, 0));
            s.sendLegacy(m.prefix + m.testVote.replace("%player%", target));
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
        s.sendLegacy(text.usage("/dankvotes <" + join(ADMIN_SUBCOMMANDS) + ">"));
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

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
