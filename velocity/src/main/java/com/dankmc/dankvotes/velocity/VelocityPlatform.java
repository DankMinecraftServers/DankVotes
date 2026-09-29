package com.dankmc.dankvotes.velocity;

import com.dankmc.dankvotes.core.Platform;
import com.dankmc.dankvotes.core.Version;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Platform implementation for the Velocity proxy.
 *
 * On a proxy, "console commands" run against the proxy itself. Rewards that must
 * happen inside a world (items, crates) belong on the backend servers - run the same
 * DankVotes jar there. The proxy half is ideal for network-wide announcements, proxy
 * plugins (e.g. LuckPerms in proxy mode) and network-wide /vote, /votetop, streaks.
 */
public class VelocityPlatform implements Platform {

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final File dataFolder;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    public VelocityPlatform(ProxyServer proxy, Object plugin, Logger logger, File dataFolder) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    public Component component(String legacyText) {
        return legacy.deserialize(legacyText == null ? "" : legacyText);
    }

    @Override public Logger getLogger() { return logger; }
    @Override public File getDataFolder() { return dataFolder; }

    @Override
    public boolean isPlayerOnline(String username) {
        return proxy.getPlayer(username).isPresent();
    }

    @Override
    public void dispatchConsoleCommand(String command) {
        // Velocity command dispatch is thread-safe.
        proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command)
            .exceptionally(t -> {
                logger.warning("Reward command failed: '" + command + "': " + t.getMessage());
                return false;
            });
    }

    @Override
    public void runSync(Runnable task) {
        // Velocity has no main thread.
        task.run();
    }

    @Override
    public void runAsync(Runnable task) {
        proxy.getScheduler().buildTask(plugin, task).schedule();
    }

    @Override
    public TaskHandle scheduleRepeatingAsync(Runnable task, long intervalSeconds) {
        ScheduledTask scheduled = proxy.getScheduler()
            .buildTask(plugin, task)
            .delay(intervalSeconds, TimeUnit.SECONDS)
            .repeat(intervalSeconds, TimeUnit.SECONDS)
            .schedule();
        return scheduled::cancel;
    }

    @Override
    public TaskHandle scheduleDelayedAsync(Runnable task, long delaySeconds) {
        ScheduledTask scheduled = proxy.getScheduler()
            .buildTask(plugin, task)
            .delay(delaySeconds, TimeUnit.SECONDS)
            .schedule();
        return scheduled::cancel;
    }

    @Override
    public void broadcast(String message) {
        Component component = component(message);
        proxy.getAllPlayers().forEach(p -> p.sendMessage(component));
    }

    @Override
    public void messagePlayer(String username, String message) {
        Component component = component(message);
        proxy.getPlayer(username).ifPresent(p -> p.sendMessage(component));
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) names.add(p.getUsername());
        return names;
    }

    @Override
    public boolean hasPermission(String username, String permission) {
        return proxy.getPlayer(username).map(p -> p.hasPermission(permission)).orElse(false);
    }

    @Override public String getPlatformName() { return "Velocity"; }
    @Override public String getPluginVersion() { return Version.get(); }
}
