package com.dankmc.dankvotes.velocity;

import com.dankmc.dankvotes.core.CommandHandler;
import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesCore;
import com.dankmc.dankvotes.core.DefaultConfig;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * Commands run through the shared {@link CommandHandler}. Only the ones config.yml switches on
 * are registered: proxy commands are matched before the backend's, so a /vote left on here
 * would hide the backend servers' own /vote.
 */
@Plugin(
    id = "dankvotes",
    name = "DankVotes",
    version = "1.2.0",
    description = "Vote rewards done right - polling, Votifier v1/v2, streaks, vote parties, reminders.",
    url = "https://dankminecraftservers.com",
    authors = {"DankMinecraftServers"}
)
public class DankVotesVelocity {

    private final ProxyServer proxy;
    private final Logger slf4jLogger;
    private final Path dataDirectory;
    private final java.util.logging.Logger julLogger;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private volatile CommandHandler commands;

    private volatile DankVotesCore core;

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
            commands = new CommandHandler(() -> core, this::reload, "Velocity");
            registerCommands(core.getConfig());
            slf4jLogger.info("DankVotes enabled on Velocity.");
        } catch (Exception e) {
            slf4jLogger.error("Failed to start DankVotes: {}", e.getMessage(), e);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        DankVotesCore c = core;
        core = null;
        if (c != null) c.stop();
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        DankVotesCore c = core;
        if (c != null) c.onPlayerJoin(event.getPlayer().getUsername());
    }

    /** Forwarding in "current" mode: deliver held votes once the player reaches a backend. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        DankVotesCore c = core;
        if (c != null) {
            c.getForwarder().onPlayerServer(event.getPlayer().getUsername(), event.getServer().getServerInfo().getName());
        }
    }

    /** Register the commands config.yml switches on; the rest stay free for other plugins and the backends. */
    private void registerCommands(DankVotesConfig config) {
        CommandManager cm = proxy.getCommandManager();
        List<String> registered = new ArrayList<>();
        for (String[] names : CommandHandler.enabledCommands(config)) {
            if (!names[0].equals("dankvotes")) warnIfTaken(cm, names);
            String[] aliases = Arrays.copyOfRange(names, 1, names.length);
            cm.register(meta(cm.metaBuilder(names[0]).aliases(aliases)), new VelocityCommand(names[0]));
            registered.add(names[0]);
        }
        commands.setRegistered(registered);
        slf4jLogger.info(CommandHandler.registrationSummary(config));
    }

    /** Velocity lets the newest registration of a name win, so say when DankVotes takes one over. */
    private void warnIfTaken(CommandManager cm, String[] names) {
        List<String> taken = new ArrayList<>();
        for (String name : names) {
            try {
                if (cm.hasCommand(name)) taken.add("/" + name);
            } catch (Throwable t) {
                return;
            }
        }
        if (!taken.isEmpty()) {
            slf4jLogger.warn("{} already belongs to another plugin on this proxy; DankVotes's /{} replaces it. "
                + "To keep the other one, set commands.{}: false in config.yml and restart.",
                String.join(", ", taken), names[0], names[0]);
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
        DankVotesCore old = core;
        core = null;
        if (old != null) old.stop();
        startCore();
    }

    private void startCore() {
        DankVotesConfig config = VelocityConfigLoader.load(dataDirectory.resolve("config.yml"), julLogger);
        VelocityPlatform platform = new VelocityPlatform(proxy, this, julLogger, dataDirectory.toFile());
        DankVotesCore c = new DankVotesCore(platform, config);
        c.start();
        core = c;
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

    // ── commands ─────────────────────────────────────────────────────

    private List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) out.add(p.getUsername());
        return out;
    }

    /** One Velocity command per DankVotes command; the shared handler does the work. */
    private final class VelocityCommand implements SimpleCommand {

        private final String canonical;

        VelocityCommand(String canonical) {
            this.canonical = canonical;
        }

        @Override
        public void execute(Invocation inv) {
            commands.execute(canonical, new VelocitySender(inv.source()), inv.arguments());
        }

        @Override
        public List<String> suggest(Invocation inv) {
            return commands.complete(canonical, new VelocitySender(inv.source()), inv.arguments(), onlineNames());
        }

        /**
         * Player commands are open unless a permissions plugin denies the node; when it does,
         * Velocity passes the command on to the backend server. /dankvotes checks inside.
         */
        @Override
        public boolean hasPermission(Invocation inv) {
            String node = CommandHandler.permission(canonical);
            return node == null || allowedByDefault(inv.source(), node);
        }
    }

    /** A Velocity command source as the shared command handler sees it. */
    private final class VelocitySender implements CommandHandler.Sender {

        private final CommandSource source;

        VelocitySender(CommandSource source) {
            this.source = source;
        }

        @Override
        public String playerName() {
            return source instanceof Player p ? p.getUsername() : null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return source.hasPermission(permission);
        }

        @Override
        public boolean allowedByDefault(String permission) {
            return DankVotesVelocity.allowedByDefault(source, permission);
        }

        @Override
        public void sendLegacy(String line) {
            source.sendMessage(legacy.deserialize(line == null ? "" : line));
        }
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
}
