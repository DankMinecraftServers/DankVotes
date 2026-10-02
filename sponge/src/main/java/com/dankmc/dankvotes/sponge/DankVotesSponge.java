package com.dankmc.dankvotes.sponge;

import com.dankmc.dankvotes.core.CommandHandler;
import com.dankmc.dankvotes.core.ConfigMapper;
import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesCore;
import com.dankmc.dankvotes.core.DefaultConfig;
import com.google.inject.Inject;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.Server;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.ArgumentReader;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * DankVotes - Sponge entry point (SpongeVanilla, SpongeForge, SpongeNeo; API 8 and newer).
 *
 * Sponge finds this class through META-INF/sponge_plugins.json and creates it with Guice.
 * The plugin instance is registered for events automatically. Config lives in
 * config/dankvotes/config.yml - the same file format as every other platform.
 */
@Plugin("dankvotes")
public final class DankVotesSponge {

    private final PluginContainer container;
    private final Path configDir;
    private final java.util.logging.Logger logger;
    private final CommandHandler commands;
    private volatile DankVotesCore core;

    @Inject
    public DankVotesSponge(final PluginContainer container, @ConfigDir(sharedRoot = false) final Path configDir) {
        this.container = container;
        this.configDir = configDir;
        this.logger = bridgeLogger(container.logger());
        this.commands = new CommandHandler(
            new CommandHandler.CoreAccess() {
                @Override public DankVotesCore core() { return DankVotesSponge.this.core; }
            },
            new Runnable() {
                @Override public void run() { restartCore(); }
            },
            "Sponge");
    }

    // ── lifecycle ────────────────────────────────────────────────────

    /**
     * Sponge asks for commands before the server (and DankVotes) has started, so config.yml is
     * read here for which ones are switched on. The rest are never registered.
     */
    @Listener
    public void onRegisterCommands(final RegisterCommandEvent<Command.Raw> event) {
        File file = DefaultConfig.saveIfMissing(configDir.toFile(), DankVotesSponge.class, logger);
        DankVotesConfig config = loadConfig(file);
        List<String> registered = new ArrayList<String>();
        for (String[] names : CommandHandler.enabledCommands(config)) {
            try {
                event.register(this.container, new RawCommand(names[0]), names[0], Arrays.copyOfRange(names, 1, names.length));
                registered.add(names[0]);
            } catch (RuntimeException e) {
                logger.warning("Could not register /" + names[0] + ": " + e.getMessage());
            }
        }
        commands.setRegistered(registered);
        logger.info(CommandHandler.registrationSummary(config));
    }

    @Listener
    public void onServerStarted(final StartedEngineEvent<Server> event) {
        startCore();
        logger.info("DankVotes " + core.getPlatform().getPluginVersion() + " enabled on Sponge.");
    }

    @Listener
    public void onServerStopping(final StoppingEngineEvent<Server> event) {
        DankVotesCore c = core;
        core = null;
        if (c != null) c.stop();
    }

    @Listener
    public void onJoin(final ServerSideConnectionEvent.Join event) {
        DankVotesCore c = core;
        if (c != null) c.onPlayerJoin(event.player().name());
    }

    private synchronized void startCore() {
        File dataFolder = configDir.toFile();
        File file = DefaultConfig.saveIfMissing(dataFolder, DankVotesSponge.class, logger);
        DankVotesCore c = new DankVotesCore(new SpongePlatform(container, logger, dataFolder), loadConfig(file));
        c.start();
        core = c;
    }

    private synchronized void restartCore() {
        DankVotesCore old = core;
        core = null;
        if (old != null) old.stop();
        startCore();
    }

    private DankVotesConfig loadConfig(File file) {
        if (!file.exists()) return new DankVotesConfig();
        try {
            InputStream in = new FileInputStream(file);
            try {
                Object root = new Yaml().load(in);
                return ConfigMapper.fromMap(root instanceof Map ? (Map<?, ?>) root : null);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            logger.warning("Could not read config.yml, using defaults: " + e.getMessage());
            return new DankVotesConfig();
        }
    }

    /** Route java.util.logging (used by core) into the plugin's Log4j logger. */
    private static java.util.logging.Logger bridgeLogger(final org.apache.logging.log4j.Logger log4j) {
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("DankVotes-Sponge");
        jul.setUseParentHandlers(false);
        for (Handler h : jul.getHandlers()) jul.removeHandler(h);
        jul.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                String msg = r.getMessage();
                if (r.getThrown() != null) msg = msg + " (" + r.getThrown() + ")";
                int level = r.getLevel().intValue();
                if (level >= Level.SEVERE.intValue()) log4j.error(msg);
                else if (level >= Level.WARNING.intValue()) log4j.warn(msg);
                else log4j.info(msg);
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return jul;
    }

    // ── commands ─────────────────────────────────────────────────────

    /** A raw (unparsed) Sponge command; the shared handler parses and answers. */
    private final class RawCommand implements Command.Raw {

        private final String canonical;

        RawCommand(String canonical) {
            this.canonical = canonical;
        }

        @Override
        public CommandResult process(final CommandCause cause, final ArgumentReader.Mutable arguments) {
            commands.execute(canonical, new SpongeSender(cause), words(arguments.remaining()));
            return CommandResult.success();
        }

        @Override
        public List<CommandCompletion> complete(final CommandCause cause, final ArgumentReader.Mutable arguments) {
            DankVotesCore c = core;
            List<String> online = c == null ? new ArrayList<String>() : c.getPlatform().getOnlinePlayerNames();
            // Keep a trailing empty word so "dankvotes test " completes the next argument.
            String[] args = arguments.remaining().split(" ", -1);
            List<CommandCompletion> out = new ArrayList<CommandCompletion>();
            for (String s : commands.complete(canonical, new SpongeSender(cause), args, online)) {
                out.add(CommandCompletion.of(s));
            }
            return out;
        }

        /** Everyone may try; the handler checks permissions and explains a refusal. */
        @Override
        public boolean canExecute(final CommandCause cause) {
            return true;
        }

        @Override
        public Optional<Component> shortDescription(final CommandCause cause) {
            return Optional.of(SpongePlatform.component(CommandHandler.description(canonical)));
        }

        @Override
        public Optional<Component> extendedDescription(final CommandCause cause) {
            return Optional.empty();
        }

        @Override
        public Component usage(final CommandCause cause) {
            DankVotesCore c = core;
            return SpongePlatform.component(CommandHandler.arguments(canonical, c == null ? null : c.getConfig()));
        }
    }

    private static final class SpongeSender implements CommandHandler.Sender {

        private final CommandCause cause;

        SpongeSender(CommandCause cause) {
            this.cause = cause;
        }

        @Override
        public String playerName() {
            Object subject = cause.subject();
            return subject instanceof ServerPlayer ? ((ServerPlayer) subject).name() : null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return cause.subject().hasPermission(permission);
        }

        @Override
        public boolean allowedByDefault(String permission) {
            return cause.subject().permissionValue(permission) != Tristate.FALSE;
        }

        @Override
        public void sendLegacy(String line) {
            cause.audience().sendMessage(SpongePlatform.component(line));
        }
    }

    private static String[] words(String input) {
        String t = input == null ? "" : input.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }
}
