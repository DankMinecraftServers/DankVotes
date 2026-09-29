package com.dankmc.dankvotes.sponge;

import com.dankmc.dankvotes.core.Platform;
import com.dankmc.dankvotes.core.Version;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.scheduler.ScheduledTask;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Platform implementation for Sponge (SpongeVanilla, SpongeForge, SpongeNeo), API 8+.
 *
 * Only API calls that exist unchanged from SpongeAPI 8 through the newest release are used.
 * World-changing work (reward commands) and chat run on the server thread through Sponge's
 * sync scheduler; network work runs on its async scheduler. Player look-ups from vote threads
 * read the player list directly, as the Bukkit build does, and never block on the server
 * thread (which could deadlock against a command running there).
 */
final class SpongePlatform implements Platform {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final PluginContainer container;
    private final Logger logger;
    private final File dataFolder;

    SpongePlatform(PluginContainer container, Logger logger, File dataFolder) {
        this.container = container;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    static Component component(String legacyText) {
        return LEGACY.deserialize(legacyText == null ? "" : legacyText);
    }

    @Override public Logger getLogger() { return logger; }
    @Override public File getDataFolder() { return dataFolder; }

    @Override
    public boolean isPlayerOnline(String username) {
        return player(username).isPresent();
    }

    @Override
    public void dispatchConsoleCommand(final String command) {
        runSync(new Runnable() {
            @Override public void run() {
                try {
                    Sponge.server().commandManager().process(Sponge.systemSubject(), Sponge.systemSubject(), command);
                } catch (Exception e) {
                    logger.warning("Reward command failed: '" + command + "': " + e.getMessage());
                }
            }
        });
    }

    /** Run on the server thread. */
    @Override
    public void runSync(Runnable task) {
        try {
            Sponge.server().scheduler().submit(Task.builder().plugin(container).execute(task).build());
        } catch (IllegalStateException stopping) {
            logger.warning("Server is not running - skipped a scheduled task.");
        }
    }

    @Override
    public void runAsync(Runnable task) {
        Sponge.asyncScheduler().submit(Task.builder().plugin(container).execute(task).build());
    }

    @Override
    public TaskHandle scheduleRepeatingAsync(Runnable task, long intervalSeconds) {
        long seconds = Math.max(1L, intervalSeconds);
        return handle(Sponge.asyncScheduler().submit(Task.builder().plugin(container).execute(task)
            .delay(seconds, TimeUnit.SECONDS).interval(seconds, TimeUnit.SECONDS).build()));
    }

    @Override
    public TaskHandle scheduleDelayedAsync(Runnable task, long delaySeconds) {
        return handle(Sponge.asyncScheduler().submit(Task.builder().plugin(container).execute(task)
            .delay(Math.max(0L, delaySeconds), TimeUnit.SECONDS).build()));
    }

    @Override
    public void broadcast(String message) {
        final Component text = component(message);
        runSync(new Runnable() {
            @Override public void run() {
                Sponge.server().broadcastAudience().sendMessage(text);
            }
        });
    }

    @Override
    public void messagePlayer(final String username, String message) {
        final Component text = component(message);
        runSync(new Runnable() {
            @Override public void run() {
                Optional<ServerPlayer> p = Sponge.server().player(username);
                if (p.isPresent()) p.get().sendMessage(text);
            }
        });
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<String>();
        try {
            // toArray() copies without iterating, so a join/leave on the server thread can't
            // throw ConcurrentModificationException here.
            for (Object o : Sponge.server().onlinePlayers().toArray()) {
                if (o instanceof ServerPlayer) names.add(((ServerPlayer) o).name());
            }
        } catch (RuntimeException e) {
            // Server stopping; an empty list is the safe answer.
        }
        return names;
    }

    @Override
    public boolean hasPermission(String username, String permission) {
        Optional<ServerPlayer> p = player(username);
        return p.isPresent() && p.get().hasPermission(permission);
    }

    @Override public String getPlatformName() { return "Sponge"; }
    @Override public String getPluginVersion() { return Version.get(); }

    private static Optional<ServerPlayer> player(String username) {
        try {
            return Sponge.server().player(username);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static TaskHandle handle(final ScheduledTask task) {
        return new TaskHandle() {
            @Override public void cancel() { task.cancel(); }
        };
    }
}
