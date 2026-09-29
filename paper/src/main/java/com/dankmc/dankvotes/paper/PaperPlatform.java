package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.Platform;
import com.dankmc.dankvotes.core.Vote;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Platform implementation for every Bukkit-API server: CraftBukkit, Spigot, Paper, Purpur,
 * Pufferfish and other forks, hybrids such as Mohist and Arclight, and Folia.
 *
 * UNIVERSAL build: compiled against the Spigot 1.8.8 API and Java 8, so it loads on every
 * server from MC 1.7.10 to the latest. Messaging uses the legacy String API
 * (sendMessage(String) + ChatColor) which exists on ALL versions - we deliberately avoid
 * the Adventure Component API because 1.7-1.16 servers don't have it.
 *
 * Scheduling goes through {@link SchedulerAdapter}: Folia's region schedulers when present,
 * the classic BukkitScheduler otherwise.
 */
public class PaperPlatform implements Platform {

    private final Plugin plugin;
    private final SchedulerAdapter scheduler;

    public PaperPlatform(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = new SchedulerAdapter(plugin);
    }

    SchedulerAdapter getScheduler() { return scheduler; }

    @Override
    public Logger getLogger() {
        return plugin.getLogger();
    }

    @Override
    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public boolean isPlayerOnline(String username) {
        Player p = Bukkit.getPlayerExact(username);
        return p != null && p.isOnline();
    }

    @Override
    public void dispatchConsoleCommand(final String command) {
        // Command dispatch must run on the main thread (Bukkit) or the global region (Folia).
        boolean scheduled = scheduler.runGlobal(new Runnable() {
            @Override public void run() {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (Exception e) {
                    plugin.getLogger().warning("Reward command failed: '" + command + "': " + e.getMessage());
                }
            }
        });
        if (!scheduled) {
            plugin.getLogger().warning("Server is shutting down - skipped reward command: " + command);
        }
    }

    @Override
    public void runSync(Runnable task) {
        scheduler.runGlobal(task);
    }

    @Override
    public void runAsync(Runnable task) {
        scheduler.runAsync(task);
    }

    @Override
    public TaskHandle scheduleRepeatingAsync(Runnable task, long intervalSeconds) {
        final Runnable canceller = scheduler.runAsyncRepeating(task, intervalSeconds);
        return new TaskHandle() {
            @Override public void cancel() { canceller.run(); }
        };
    }

    @Override
    public TaskHandle scheduleDelayedAsync(Runnable task, long delaySeconds) {
        final Runnable canceller = scheduler.runAsyncLater(task, delaySeconds);
        return new TaskHandle() {
            @Override public void cancel() { canceller.run(); }
        };
    }

    @Override
    public void broadcast(final String message) {
        final String colored = color(message);
        scheduler.runGlobal(new Runnable() {
            @Override public void run() {
                Bukkit.broadcastMessage(colored);
            }
        });
    }

    @Override
    public void messagePlayer(final String username, final String message) {
        final String colored = color(message);
        scheduler.runGlobal(new Runnable() {
            @Override public void run() {
                Player p = Bukkit.getPlayerExact(username);
                if (p != null && p.isOnline()) {
                    p.sendMessage(colored);
                }
            }
        });
    }

    @Override
    public List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<String>();
        for (Player p : BukkitCompat.onlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    @Override
    public boolean hasPermission(String username, String permission) {
        Player p = Bukkit.getPlayerExact(username);
        return p != null && p.hasPermission(permission);
    }

    @Override
    public boolean fireVoteEvent(Vote vote) {
        try {
            DankVoteEvent event = new DankVoteEvent(vote);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } catch (Exception e) {
            // Never let a misbehaving listener swallow a vote.
            plugin.getLogger().warning("A plugin threw an exception handling DankVoteEvent: " + e.getMessage());
            return true;
        }
    }

    /** The server's own name ("Paper", "Purpur", "Folia", ...), marking regionised forks. */
    @Override
    public String getPlatformName() {
        String name = Bukkit.getName();
        if (name == null || name.trim().isEmpty()) name = "Bukkit";
        return scheduler.isFolia() && !name.toLowerCase(java.util.Locale.ROOT).contains("folia") ? name + " (Folia)" : name;
    }

    @Override
    public String getPluginVersion() {
        return plugin.getDescription().getVersion();
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
