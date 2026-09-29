package com.dankmc.dankvotes.bungee;

import com.dankmc.dankvotes.core.Platform;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Platform implementation for BungeeCord and its forks (Waterfall, FlameCord, ...).
 *
 * Like Velocity, a proxy has no main thread and no worlds: "console commands" run against
 * the proxy itself (proxy plugins such as LuckPerms in proxy mode, alerts), while in-world
 * rewards belong on the backend servers - run the same jar there and turn on forwarding.
 */
final class BungeePlatform implements Platform {

    private final Plugin plugin;
    private final ProxyServer proxy;

    BungeePlatform(Plugin plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getProxy();
    }

    @Override public Logger getLogger() { return plugin.getLogger(); }
    @Override public File getDataFolder() { return plugin.getDataFolder(); }

    @Override
    public boolean isPlayerOnline(String username) {
        return proxy.getPlayer(username) != null;
    }

    @Override
    public void dispatchConsoleCommand(final String command) {
        // BungeeCord runs commands on the calling thread; keep them off the vote threads.
        proxy.getScheduler().runAsync(plugin, new Runnable() {
            @Override public void run() {
                try {
                    if (!proxy.getPluginManager().dispatchCommand(proxy.getConsole(), command)) {
                        String label = command.trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
                        if (unknownCommands.add(label)) {
                            plugin.getLogger().warning("'" + label + "' is not a proxy command, so rewards using it do "
                                + "nothing here. In-world rewards belong in the DankVotes config on your backend servers.");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Reward command failed: '" + command + "': " + e.getMessage());
                }
            }
        });
    }

    /** Reward commands the proxy doesn't know, reported once each. */
    private final java.util.Set<String> unknownCommands =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    @Override
    public void runSync(Runnable task) {
        // A proxy has no main thread.
        task.run();
    }

    @Override
    public void runAsync(Runnable task) {
        proxy.getScheduler().runAsync(plugin, task);
    }

    @Override
    public TaskHandle scheduleRepeatingAsync(Runnable task, long intervalSeconds) {
        long seconds = Math.max(1L, intervalSeconds);
        return handle(proxy.getScheduler().schedule(plugin, task, seconds, seconds, TimeUnit.SECONDS));
    }

    @Override
    public TaskHandle scheduleDelayedAsync(Runnable task, long delaySeconds) {
        return handle(proxy.getScheduler().schedule(plugin, task, Math.max(0L, delaySeconds), TimeUnit.SECONDS));
    }

    @Override
    public void broadcast(String message) {
        proxy.broadcast(components(message));
    }

    @Override
    public void messagePlayer(String username, String message) {
        ProxiedPlayer p = proxy.getPlayer(username);
        if (p != null) p.sendMessage(components(message));
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<String>();
        for (ProxiedPlayer p : proxy.getPlayers()) names.add(p.getName());
        return names;
    }

    @Override
    public boolean hasPermission(String username, String permission) {
        ProxiedPlayer p = proxy.getPlayer(username);
        return p != null && p.hasPermission(permission);
    }

    @Override public boolean isProxy() { return true; }

    /** The backend server (as named in the proxy's config.yml) the player is on, or null. */
    @Override
    public String getPlayerServer(String username) {
        ProxiedPlayer p = proxy.getPlayer(username);
        if (p == null || p.getServer() == null || p.getServer().getInfo() == null) return null;
        return p.getServer().getInfo().getName();
    }

    /** "BungeeCord", "Waterfall", ... as the proxy reports itself. */
    @Override
    public String getPlatformName() {
        String name = proxy.getName();
        return name == null || name.trim().isEmpty() ? "BungeeCord" : name;
    }

    @Override
    public String getPluginVersion() {
        return plugin.getDescription().getVersion();
    }

    static BaseComponent[] components(String legacy) {
        return TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', legacy == null ? "" : legacy));
    }

    private static TaskHandle handle(final ScheduledTask task) {
        return new TaskHandle() {
            @Override public void cancel() { task.cancel(); }
        };
    }
}
