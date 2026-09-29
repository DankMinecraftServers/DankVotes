package com.dankmc.dankvotes.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Scheduler adapter that works on BOTH Folia and regular Bukkit/Paper/Spigot/Purpur.
 *
 * Folia removed the single main thread and shards the world into regions, each with its
 * own scheduler. The classic BukkitScheduler is not available there. We detect Folia at
 * runtime (by checking for its GlobalRegionScheduler) and route calls accordingly via
 * reflection — so this single jar runs everywhere without compile-time Folia deps.
 */
public class FoliaScheduler {

    private final Plugin plugin;
    private final boolean folia;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isFolia() {
        return folia;
    }

    /**
     * Run a task on the correct "global" thread.
     * Folia: GlobalRegionScheduler.execute. Bukkit: main thread via scheduler.
     */
    public void runGlobal(Runnable task) {
        if (folia) {
            try {
                Object globalScheduler = Bukkit.class
                    .getMethod("getGlobalRegionScheduler")
                    .invoke(null);
                globalScheduler.getClass()
                    .getMethod("execute", Plugin.class, Runnable.class)
                    .invoke(globalScheduler, plugin, task);
            } catch (Exception e) {
                // Fallback: just run it (last resort)
                task.run();
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Run a task asynchronously, on either platform. */
    public void runAsync(Runnable task) {
        if (folia) {
            try {
                Object asyncScheduler = Bukkit.class
                    .getMethod("getAsyncScheduler")
                    .invoke(null);
                // runNow(Plugin, Consumer<ScheduledTask>)
                java.util.function.Consumer<Object> consumer = (ignored) -> task.run();
                asyncScheduler.getClass()
                    .getMethod("runNow", Plugin.class, java.util.function.Consumer.class)
                    .invoke(asyncScheduler, plugin, consumer);
            } catch (Exception e) {
                new Thread(task, "DankVotes-Async").start();
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Schedule a repeating async task at a fixed interval.
     * Returns a Runnable that cancels it when called.
     */
    public Runnable runAsyncRepeating(Runnable task, long intervalSeconds) {
        if (folia) {
            try {
                Object asyncScheduler = Bukkit.class
                    .getMethod("getAsyncScheduler")
                    .invoke(null);
                java.util.function.Consumer<Object> consumer = (ignored) -> task.run();
                Object scheduledTask = asyncScheduler.getClass()
                    .getMethod("runAtFixedRate", Plugin.class, java.util.function.Consumer.class,
                               long.class, long.class, TimeUnit.class)
                    .invoke(asyncScheduler, plugin, consumer, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
                return () -> {
                    try {
                        scheduledTask.getClass().getMethod("cancel").invoke(scheduledTask);
                    } catch (Exception ignored) {}
                };
            } catch (Exception e) {
                // Fall through to a plain thread-based timer
                return threadTimer(task, intervalSeconds);
            }
        } else {
            long ticks = intervalSeconds * 20L;
            int taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, task, ticks, ticks)
                .getTaskId();
            return () -> Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    /**
     * Run a task asynchronously after a delay. Returns a Runnable that cancels it.
     */
    public Runnable runAsyncLater(Runnable task, long delaySeconds) {
        if (folia) {
            try {
                Object asyncScheduler = Bukkit.class
                    .getMethod("getAsyncScheduler")
                    .invoke(null);
                java.util.function.Consumer<Object> consumer = (ignored) -> task.run();
                Object scheduledTask = asyncScheduler.getClass()
                    .getMethod("runDelayed", Plugin.class, java.util.function.Consumer.class,
                               long.class, TimeUnit.class)
                    .invoke(asyncScheduler, plugin, consumer, delaySeconds, TimeUnit.SECONDS);
                return () -> {
                    try {
                        scheduledTask.getClass().getMethod("cancel").invoke(scheduledTask);
                    } catch (Exception ignored) {}
                };
            } catch (Exception e) {
                return threadDelay(task, delaySeconds);
            }
        } else {
            int taskId = Bukkit.getScheduler()
                .runTaskLaterAsynchronously(plugin, task, delaySeconds * 20L)
                .getTaskId();
            return () -> Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    private Runnable threadDelay(Runnable task, long delaySeconds) {
        final boolean[] cancelled = {false};
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delaySeconds * 1000L);
                if (!cancelled[0]) task.run();
            } catch (InterruptedException ignored) {}
        }, "DankVotes-Delayed");
        t.setDaemon(true);
        t.start();
        return () -> cancelled[0] = true;
    }

    /** Simple fallback timer using a daemon thread. */
    private Runnable threadTimer(Runnable task, long intervalSeconds) {
        final boolean[] cancelled = {false};
        Thread t = new Thread(() -> {
            while (!cancelled[0]) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                    if (!cancelled[0]) task.run();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "DankVotes-Timer");
        t.setDaemon(true);
        t.start();
        return () -> cancelled[0] = true;
    }
}
