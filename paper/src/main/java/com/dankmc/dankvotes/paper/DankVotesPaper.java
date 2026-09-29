package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.CommandText;
import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesCore;
import com.dankmc.dankvotes.core.DefaultConfig;
import com.dankmc.dankvotes.core.Vote;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DankVotes - entry point for every Bukkit-API server (UNIVERSAL build, Java 8).
 *
 * Loads on CraftBukkit, Spigot, Paper, Purpur, Pufferfish and other forks, hybrids such as
 * Mohist and Arclight, and Folia, from Minecraft 1.7.10 to the latest release. Receives votes
 * via outbound polling (no port forwarding), the inbound Votifier v1/v2 protocol, or an
 * existing NuVotifier install, then runs fully customizable in-game rewards.
 */
public class DankVotesPaper extends JavaPlugin implements Listener {

    private static final String PERM_ADMIN = "dankvotes.admin";
    private static final String PERM_VOTE = "dankvotes.vote";
    private static final String PERM_VOTES = "dankvotes.votes";
    private static final String PERM_VOTES_OTHERS = "dankvotes.votes.others";
    private static final String PERM_VOTETOP = "dankvotes.votetop";
    private static final String PERM_VOTEPARTY = "dankvotes.voteparty";

    private static final List<String> ADMIN_SUBS = Arrays.asList(
        "help", "reload", "status", "test", "setvotes", "addvotes", "reset", "party", "key", "version");

    private DankVotesCore core;
    private CommandText text;
    private boolean nuVotifierHooked;
    private boolean placeholdersHooked;
    private Object metrics;

    @Override
    public void onEnable() {
        DefaultConfig.saveIfMissing(getDataFolder(), DankVotesPaper.class, getLogger());
        startCore();

        getServer().getPluginManager().registerEvents(this, this);

        // Optional integrations - each is a no-op when the other plugin is absent.
        if (core.getConfig().nuVotifierHookEnabled) {
            try {
                nuVotifierHooked = NuVotifierHook.register(this);
                if (nuVotifierHooked) {
                    getLogger().info("Hooked into NuVotifier: votes it receives will be rewarded by DankVotes.");
                }
            } catch (Throwable t) {
                getLogger().warning("Could not hook NuVotifier: " + t.getMessage());
            }
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                placeholdersHooked = new PlaceholderHook(this).register();
                if (placeholdersHooked) getLogger().info("Registered PlaceholderAPI expansion: %dankvotes_*%");
            } catch (Throwable t) {
                getLogger().warning("Could not register PlaceholderAPI expansion: " + t.getMessage());
            }
        }
        if (core.getConfig().metricsEnabled) {
            try {
                metrics = MetricsHook.start(this);
            } catch (Throwable ignored) {
                // Metrics are best-effort only.
            }
        }

        getLogger().info("DankVotes " + getDescription().getVersion() + " enabled on " + core.getPlatform().getPlatformName() + ".");
    }

    @Override
    public void onDisable() {
        SchedulerAdapter scheduler = null;
        if (core != null) {
            if (core.getPlatform() instanceof PaperPlatform) {
                scheduler = ((PaperPlatform) core.getPlatform()).getScheduler();
            }
            core.stop();
            core = null;
        }
        if (metrics != null) {
            try { MetricsHook.stop(metrics); } catch (Throwable ignored) {}
            metrics = null;
        }
        if (scheduler != null) scheduler.cancelAll();
    }

    /** Folia runs commands on several threads at once, so two reloads must not interleave. */
    private synchronized void reload() {
        reloadConfig();
        if (core != null) core.stop();
        startCore();
    }

    private void startCore() {
        DankVotesConfig config = PaperConfigLoader.load(getConfig());
        PaperPlatform platform = new PaperPlatform(this);
        core = new DankVotesCore(platform, config);
        text = new CommandText(core);
        core.start();
    }

    /** The active core (null while disabled). Hooks call this so /dankvotes reload is safe. */
    public DankVotesCore getCore() { return core; }
    public boolean isNuVotifierHooked() { return nuVotifierHooked; }

    // ── Events ───────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (core != null) core.onPlayerJoin(event.getPlayer().getName());
    }

    // ── Commands ─────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (core == null) {
            sender.sendMessage(PaperPlatform.color("&cDankVotes is not running."));
            return true;
        }
        String name = command.getName().toLowerCase();
        if (name.equals("vote")) return cmdVote(sender);
        if (name.equals("votes")) return cmdVotes(sender, args);
        if (name.equals("votetop")) return cmdVoteTop(sender, args);
        if (name.equals("voteparty")) return cmdVoteParty(sender);
        if (name.equals("dankvotes")) return cmdAdmin(sender, args);
        return false;
    }

    private boolean cmdVote(CommandSender sender) {
        if (!sender.hasPermission(PERM_VOTE)) return deny(sender);
        String viewer = sender instanceof Player ? ((Player) sender).getName() : null;
        send(sender, text.vote(viewer));
        return true;
    }

    private boolean cmdVotes(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_VOTES)) return deny(sender);
        String viewer = sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
        String target = args.length > 0 ? args[0] : null;
        if (target == null && !(sender instanceof Player)) {
            sender.sendMessage(PaperPlatform.color(text.usage("/votes <player>")));
            return true;
        }
        if (target != null && !target.equalsIgnoreCase(viewer) && !sender.hasPermission(PERM_VOTES_OTHERS)) {
            return deny(sender);
        }
        sender.sendMessage(PaperPlatform.color(text.votes(viewer, target)));
        return true;
    }

    private boolean cmdVoteTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM_VOTETOP)) return deny(sender);
        int page = 1;
        if (args.length > 0) {
            try { page = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        send(sender, text.voteTop(page, 10));
        return true;
    }

    private boolean cmdVoteParty(CommandSender sender) {
        if (!sender.hasPermission(PERM_VOTEPARTY)) return deny(sender);
        sender.sendMessage(PaperPlatform.color(text.voteParty()));
        return true;
    }

    private boolean cmdAdmin(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            for (String line : text.help(sender.hasPermission(PERM_ADMIN)).split("\n")) {
                sender.sendMessage(PaperPlatform.color(line));
            }
            return true;
        }
        if (!sender.hasPermission(PERM_ADMIN)) return deny(sender);

        String sub = args[0].toLowerCase();
        DankVotesConfig.Messages m = core.getConfig().messages;

        if (sub.equals("reload")) {
            reload();
            sender.sendMessage(PaperPlatform.color(m.prefix + m.reloaded));
            return true;
        }
        if (sub.equals("status")) {
            send(sender, text.status());
            return true;
        }
        if (sub.equals("version")) {
            sender.sendMessage(PaperPlatform.color(m.prefix + "&7DankVotes &fv" + getDescription().getVersion()
                + " &7on &f" + core.getPlatform().getPlatformName()));
            return true;
        }
        if (sub.equals("key")) {
            send(sender, text.key());
            return true;
        }
        if (sub.equals("party")) {
            core.getEngine().forceVoteParty();
            sender.sendMessage(PaperPlatform.color(m.prefix + m.partyForced));
            return true;
        }
        if (sub.equals("test")) {
            String target = args.length > 1 ? args[1]
                : (sender instanceof Player ? ((Player) sender).getName() : "TestPlayer");
            core.getEngine().processVote(new Vote(target, "test", "127.0.0.1", System.currentTimeMillis(), true, 0));
            sender.sendMessage(PaperPlatform.color(m.prefix + m.testVote.replace("%player%", target)));
            return true;
        }
        if (sub.equals("setvotes") || sub.equals("addvotes")) {
            if (args.length < 3) {
                sender.sendMessage(PaperPlatform.color(text.usage("/dankvotes " + sub + " <player> <amount>")));
                return true;
            }
            int n;
            try { n = Integer.parseInt(args[2]); } catch (NumberFormatException e) {
                sender.sendMessage(PaperPlatform.color(text.usage("/dankvotes " + sub + " <player> <amount>")));
                return true;
            }
            int current = core.getStorage().getVoteCount(args[1]);
            int value = sub.equals("setvotes") ? n : current + n;
            core.getStorage().setVoteCount(args[1], value);
            core.getStorage().flush();
            sender.sendMessage(PaperPlatform.color(m.prefix + m.votesSet
                .replace("%player%", args[1]).replace("%votes%", String.valueOf(Math.max(0, value)))));
            return true;
        }
        if (sub.equals("reset")) {
            if (args.length < 2) {
                sender.sendMessage(PaperPlatform.color(text.usage("/dankvotes reset <player>")));
                return true;
            }
            core.getStorage().resetPlayer(args[1]);
            core.getStorage().flush();
            sender.sendMessage(PaperPlatform.color(m.prefix + m.votesReset.replace("%player%", args[1])));
            return true;
        }
        sender.sendMessage(PaperPlatform.color(text.usage("/dankvotes <" + join(ADMIN_SUBS) + ">")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("votes")) {
            return args.length == 1 && sender.hasPermission(PERM_VOTES_OTHERS) ? onlineNames(args[0]) : Collections.<String>emptyList();
        }
        if (name.equals("dankvotes") && sender.hasPermission(PERM_ADMIN)) {
            if (args.length == 1) return filter(ADMIN_SUBS, args[0]);
            if (args.length == 2) {
                String sub = args[0].toLowerCase();
                if (sub.equals("test") || sub.equals("setvotes") || sub.equals("addvotes") || sub.equals("reset")) {
                    return onlineNames(args[1]);
                }
            }
        }
        return Collections.<String>emptyList();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private boolean deny(CommandSender sender) {
        sender.sendMessage(PaperPlatform.color(text.noPermission()));
        return true;
    }

    private void send(CommandSender sender, List<String> lines) {
        for (String line : lines) sender.sendMessage(PaperPlatform.color(line));
    }

    private static List<String> onlineNames(String prefix) {
        List<String> names = new ArrayList<String>();
        for (Player p : BukkitCompat.onlinePlayers()) names.add(p.getName());
        return filter(names, prefix);
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<String>();
        String p = prefix == null ? "" : prefix.toLowerCase();
        for (String o : options) if (o.toLowerCase().startsWith(p)) out.add(o);
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
