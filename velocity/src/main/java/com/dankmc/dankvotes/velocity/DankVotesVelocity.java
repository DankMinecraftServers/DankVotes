package com.dankmc.dankvotes.velocity;

import com.dankmc.dankvotes.core.CommandText;
import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesCore;
import com.dankmc.dankvotes.core.DefaultConfig;
import com.dankmc.dankvotes.core.Vote;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * DankVotes - Velocity proxy entry point.
 *
 * Use this on a Velocity network for network-wide vote handling: announcements,
 * streaks, /vote, /votetop, and proxy-level reward commands. For in-world rewards
 * (items, crates) run the same jar on the backend servers too and turn on forwarding,
 * which passes every vote the proxy receives on to them (Paper, Folia, Purpur, Sponge...).
 */
@Plugin(
    id = "dankvotes",
    name = "DankVotes",
    version = "1.1.1",
    description = "Vote rewards done right - polling, Votifier v1/v2, streaks, vote parties, reminders.",
    url = "https://dankminecraftservers.com",
    authors = {"DankMinecraftServers"}
)
public class DankVotesVelocity {

    private static final String PERM_ADMIN = "dankvotes.admin";

    private final ProxyServer proxy;
    private final Logger slf4jLogger;
    private final Path dataDirectory;
    private final java.util.logging.Logger julLogger;

    private DankVotesCore core;
    private VelocityPlatform platform;
    private CommandText text;

    @Inject
    public DankVotesVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.slf4jLogger = logger;
        this.dataDirectory = dataDirectory;
        this.julLogger = bridgeLogger(logger);
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            DefaultConfig.saveIfMissing(dataDirectory.toFile(), DankVotesVelocity.class, julLogger);
            startCore();

            var cm = proxy.getCommandManager();
            cm.register(meta(cm.metaBuilder("dankvotes").aliases("dv", "dankvote")), new AdminCommand());
            cm.register(meta(cm.metaBuilder("vote")), new VoteCommand());
            cm.register(meta(cm.metaBuilder("votes").aliases("myvotes")), new VotesCommand());
            cm.register(meta(cm.metaBuilder("votetop").aliases("topvotes", "topvoters")), new VoteTopCommand());
            cm.register(meta(cm.metaBuilder("voteparty").aliases("vp")), new VotePartyCommand());

            slf4jLogger.info("DankVotes enabled on Velocity.");
        } catch (Exception e) {
            slf4jLogger.error("Failed to start DankVotes: {}", e.getMessage(), e);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core != null) core.stop();
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        if (core != null) core.onPlayerJoin(event.getPlayer().getUsername());
    }

    /** Forwarding in "current" mode: deliver held votes once the player reaches a backend. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        DankVotesCore c = core;
        if (c != null) {
            c.getForwarder().onPlayerServer(event.getPlayer().getUsername(), event.getServer().getServerInfo().getName());
        }
    }

    /**
     * Tie the command to this plugin where the proxy supports it. CommandMeta.Builder#plugin
     * arrived in Velocity 3.1; on 3.0 the command simply registers without the link.
     */
    private CommandMeta meta(CommandMeta.Builder builder) {
        try {
            builder = builder.plugin(this);
        } catch (NoSuchMethodError legacyVelocity) {
            // Velocity 3.0.x
        }
        return builder.build();
    }

    /** Commands run on several threads; two reloads must not leave two cores running. */
    private synchronized void reload() {
        if (core != null) core.stop();
        startCore();
    }

    private void startCore() {
        DankVotesConfig config = VelocityConfigLoader.load(dataDirectory.resolve("config.yml"), julLogger);
        platform = new VelocityPlatform(proxy, this, julLogger, dataDirectory.toFile());
        core = new DankVotesCore(platform, config);
        text = new CommandText(core);
        core.start();
    }

    /** Route java.util.logging (used by core) into Velocity's SLF4J logger. */
    private static java.util.logging.Logger bridgeLogger(Logger slf4j) {
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("DankVotes");
        jul.setUseParentHandlers(false);
        for (var h : jul.getHandlers()) jul.removeHandler(h);
        jul.addHandler(new java.util.logging.Handler() {
            @Override public void publish(LogRecord r) {
                String msg = r.getMessage();
                if (r.getLevel().intValue() >= Level.SEVERE.intValue()) slf4j.error(msg);
                else if (r.getLevel().intValue() >= Level.WARNING.intValue()) slf4j.warn(msg);
                else slf4j.info(msg);
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return jul;
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void send(CommandSource source, String legacy) {
        for (String line : legacy.split("\n")) source.sendMessage(platform.component(line));
    }

    private void send(CommandSource source, List<String> lines) {
        for (String line : lines) source.sendMessage(platform.component(line));
    }

    /**
     * Player commands default to ALLOWED (like Bukkit's "default: true") unless a
     * permissions plugin explicitly sets the node to false. Velocity's plain
     * hasPermission() returns false for undefined nodes, which would lock everyone out
     * of /vote on proxies without LuckPerms.
     */
    private static boolean allowedByDefault(CommandSource source, String permission) {
        return source.getPermissionValue(permission) != Tristate.FALSE;
    }

    private static String nameOf(CommandSource source) {
        return source instanceof Player p ? p.getUsername() : null;
    }

    private List<String> onlineNames(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix == null ? "" : prefix.toLowerCase();
        for (Player pl : proxy.getAllPlayers()) {
            if (pl.getUsername().toLowerCase().startsWith(p)) out.add(pl.getUsername());
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(p)).sorted().toList();
    }

    // ── commands ─────────────────────────────────────────────────────

    private class VoteCommand implements SimpleCommand {
        @Override public void execute(Invocation inv) { send(inv.source(), text.vote(nameOf(inv.source()))); }
        @Override public boolean hasPermission(Invocation inv) { return allowedByDefault(inv.source(), "dankvotes.vote"); }
    }

    private class VotesCommand implements SimpleCommand {
        @Override public void execute(Invocation inv) {
            CommandSource s = inv.source();
            String viewer = nameOf(s);
            String target = inv.arguments().length > 0 ? inv.arguments()[0] : null;
            if (target == null && viewer == null) { send(s, text.usage("/votes <player>")); return; }
            if (target != null && viewer != null && !target.equalsIgnoreCase(viewer) && !s.hasPermission("dankvotes.votes.others")) {
                send(s, text.noPermission()); return;
            }
            send(s, text.votes(viewer == null ? "CONSOLE" : viewer, target));
        }
        @Override public boolean hasPermission(Invocation inv) { return allowedByDefault(inv.source(), "dankvotes.votes"); }
        @Override public List<String> suggest(Invocation inv) {
            return inv.arguments().length <= 1 && inv.source().hasPermission("dankvotes.votes.others")
                ? onlineNames(inv.arguments().length == 1 ? inv.arguments()[0] : "") : List.of();
        }
    }

    private class VoteTopCommand implements SimpleCommand {
        @Override public void execute(Invocation inv) {
            int page = 1;
            if (inv.arguments().length > 0) { try { page = Integer.parseInt(inv.arguments()[0]); } catch (NumberFormatException ignored) {} }
            send(inv.source(), text.voteTop(page, 10));
        }
        @Override public boolean hasPermission(Invocation inv) { return allowedByDefault(inv.source(), "dankvotes.votetop"); }
    }

    private class VotePartyCommand implements SimpleCommand {
        @Override public void execute(Invocation inv) { send(inv.source(), text.voteParty()); }
        @Override public boolean hasPermission(Invocation inv) { return allowedByDefault(inv.source(), "dankvotes.voteparty"); }
    }

    /** /dankvotes <help|reload|status|test|setvotes|addvotes|reset|party|key|version> */
    private class AdminCommand implements SimpleCommand {
        private final List<String> subs = List.of("help", "reload", "status", "test", "setvotes", "addvotes", "reset", "party", "key", "version");

        @Override
        public void execute(Invocation inv) {
            CommandSource s = inv.source();
            String[] args = inv.arguments();
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) { send(s, text.help(s.hasPermission(PERM_ADMIN))); return; }
            if (!s.hasPermission(PERM_ADMIN)) { send(s, text.noPermission()); return; }
            DankVotesConfig.Messages m = core.getConfig().messages;

            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    reload();
                    send(s, m.prefix + m.reloaded);
                }
                case "status" -> send(s, text.status());
                case "version" -> send(s, m.prefix + "&7DankVotes &fv" + platform.getPluginVersion() + " &7on &fVelocity");
                case "key" -> send(s, text.key());
                case "party" -> { core.getEngine().forceVoteParty(); send(s, m.prefix + m.partyForced); }
                case "test" -> {
                    String target = args.length > 1 ? args[1] : (nameOf(s) != null ? nameOf(s) : "TestPlayer");
                    core.getEngine().processVote(new Vote(target, "test", "127.0.0.1", System.currentTimeMillis(), true, 0));
                    send(s, m.prefix + m.testVote.replace("%player%", target));
                }
                case "setvotes", "addvotes" -> {
                    if (args.length < 3) { send(s, text.usage("/dankvotes " + args[0] + " <player> <amount>")); return; }
                    int n;
                    try { n = Integer.parseInt(args[2]); } catch (NumberFormatException e) { send(s, text.usage("/dankvotes " + args[0] + " <player> <amount>")); return; }
                    int value = args[0].equalsIgnoreCase("setvotes") ? n : core.getStorage().getVoteCount(args[1]) + n;
                    core.getStorage().setVoteCount(args[1], value);
                    core.getStorage().flush();
                    send(s, m.prefix + m.votesSet.replace("%player%", args[1]).replace("%votes%", String.valueOf(Math.max(0, value))));
                }
                case "reset" -> {
                    if (args.length < 2) { send(s, text.usage("/dankvotes reset <player>")); return; }
                    core.getStorage().resetPlayer(args[1]);
                    core.getStorage().flush();
                    send(s, m.prefix + m.votesReset.replace("%player%", args[1]));
                }
                default -> send(s, text.usage("/dankvotes <" + String.join("|", subs) + ">"));
            }
        }

        @Override
        public List<String> suggest(Invocation inv) {
            if (!inv.source().hasPermission(PERM_ADMIN)) return List.of();
            String[] a = inv.arguments();
            if (a.length <= 1) return filter(subs, a.length == 1 ? a[0] : "");
            if (a.length == 2 && List.of("test", "setvotes", "addvotes", "reset").contains(a[0].toLowerCase())) return onlineNames(a[1]);
            return List.of();
        }
    }
}
