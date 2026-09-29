package com.dankmc.dankvotes.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * One scheduler for every Bukkit-API server.
 *
 * <ul>
 *   <li>CraftBukkit, Spigot, Paper, Purpur, Pufferfish and the other forks (1.7.10 → 26.x):
 *       the classic {@code BukkitScheduler}.</li>
 *   <li>Folia and its forks: Folia has no main thread, so "sync" work runs on the global
 *       region scheduler and background work on its async scheduler.</li>
 * </ul>
 *
 * Folia is detected up front by its {@code RegionizedServer} class. As a safety net for forks
 * that regionise without that class, the first {@link UnsupportedOperationException} from the
 * classic scheduler also switches to the region schedulers.
 *
 * The Folia API is called through reflection on its public interfaces (not on the server's
 * implementation classes), so this Java 8 jar compiled against the Spigot 1.8.8 API needs no
 * Folia dependency and never touches classes that don't exist on older servers.
 */
final class SchedulerAdapter {

    private static final String REGIONIZED_SERVER = "io.papermc.paper.threadedregions.RegionizedServer";

    private final Plugin plugin;
    private volatile Regionized regionized;

    SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        if (classExists(REGIONIZED_SERVER)) {
            this.regionized = Regionized.create(plugin);
            if (this.regionized == null) {
                plugin.getLogger().severe("Folia was detected but its scheduler API could not be reached. "
                    + "Please report this with your server version.");
            }
        }
    }

    boolean isFolia() {
        return regionized != null;
    }

    /**
     * Run on the main thread (Bukkit) or the global region thread (Folia). Returns false if
     * the task could not be scheduled because the plugin is being disabled.
     */
    boolean runGlobal(Runnable task) {
        if (!plugin.isEnabled()) return false;
        Regionized r = regionized;
        if (r != null) return r.runGlobal(task);
        try {
            Bukkit.getScheduler().runTask(plugin, task);
            return true;
        } catch (UnsupportedOperationException e) {
            return switchToRegionized(e) && regionized.runGlobal(task);
        }
    }

    /** Run off the main thread. */
    boolean runAsync(Runnable task) {
        if (!plugin.isEnabled()) return false;
        Regionized r = regionized;
        if (r != null) return r.runAsync(task);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return true;
        } catch (UnsupportedOperationException e) {
            return switchToRegionized(e) && regionized.runAsync(task);
        }
    }

    /** Repeat {@code task} off the main thread every {@code intervalSeconds}. Returns a canceller. */
    Runnable runAsyncRepeating(Runnable task, long intervalSeconds) {
        long seconds = Math.max(1L, intervalSeconds);
        if (!plugin.isEnabled()) return NOOP;
        Regionized r = regionized;
        if (r != null) return r.runAsyncRepeating(task, seconds);
        try {
            final int taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, task, seconds * 20L, seconds * 20L)
                .getTaskId();
            return new Runnable() {
                @Override public void run() { Bukkit.getScheduler().cancelTask(taskId); }
            };
        } catch (UnsupportedOperationException e) {
            return switchToRegionized(e) ? regionized.runAsyncRepeating(task, seconds) : NOOP;
        }
    }

    /** Run {@code task} off the main thread after {@code delaySeconds}. Returns a canceller. */
    Runnable runAsyncLater(Runnable task, long delaySeconds) {
        long seconds = Math.max(0L, delaySeconds);
        if (!plugin.isEnabled()) return NOOP;
        Regionized r = regionized;
        if (r != null) return r.runAsyncLater(task, seconds);
        try {
            final int taskId = Bukkit.getScheduler()
                .runTaskLaterAsynchronously(plugin, task, seconds * 20L)
                .getTaskId();
            return new Runnable() {
                @Override public void run() { Bukkit.getScheduler().cancelTask(taskId); }
            };
        } catch (UnsupportedOperationException e) {
            return switchToRegionized(e) ? regionized.runAsyncLater(task, seconds) : NOOP;
        }
    }

    /** Cancel everything this plugin scheduled (used on disable). */
    void cancelAll() {
        Regionized r = regionized;
        if (r != null) {
            r.cancelAll();
            return;
        }
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (Throwable ignored) {
            // Nothing scheduled through the classic scheduler on this server.
        }
    }

    private synchronized boolean switchToRegionized(UnsupportedOperationException cause) {
        if (regionized != null) return true;
        Regionized r = Regionized.create(plugin);
        if (r == null) {
            plugin.getLogger().log(Level.SEVERE, "The server refused the Bukkit scheduler and has no Folia "
                + "scheduler API either - DankVotes cannot schedule tasks here.", cause);
            return false;
        }
        plugin.getLogger().info("This server uses regionised threading - switched to the Folia schedulers.");
        regionized = r;
        return true;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final Runnable NOOP = new Runnable() {
        @Override public void run() {}
    };

    /** Folia's GlobalRegionScheduler + AsyncScheduler, reached reflectively through their interfaces. */
    static final class Regionized {

        private static final String PKG = "io.papermc.paper.threadedregions.scheduler.";

        private final Plugin plugin;
        private final Object global;
        private final Object async;
        private final Method globalExecute;       // GlobalRegionScheduler#execute(Plugin, Runnable)
        private final Method globalCancelTasks;   // GlobalRegionScheduler#cancelTasks(Plugin)
        private final Method asyncRunNow;         // AsyncScheduler#runNow(Plugin, Consumer)
        private final Method asyncRunDelayed;     // AsyncScheduler#runDelayed(Plugin, Consumer, long, TimeUnit)
        private final Method asyncRunAtFixedRate; // AsyncScheduler#runAtFixedRate(Plugin, Consumer, long, long, TimeUnit)
        private final Method asyncCancelTasks;    // AsyncScheduler#cancelTasks(Plugin)
        private final Method taskCancel;          // ScheduledTask#cancel()

        private Regionized(Plugin plugin, Object global, Object async, Method globalExecute, Method globalCancelTasks,
                           Method asyncRunNow, Method asyncRunDelayed, Method asyncRunAtFixedRate,
                           Method asyncCancelTasks, Method taskCancel) {
            this.plugin = plugin;
            this.global = global;
            this.async = async;
            this.globalExecute = globalExecute;
            this.globalCancelTasks = globalCancelTasks;
            this.asyncRunNow = asyncRunNow;
            this.asyncRunDelayed = asyncRunDelayed;
            this.asyncRunAtFixedRate = asyncRunAtFixedRate;
            this.asyncCancelTasks = asyncCancelTasks;
            this.taskCancel = taskCancel;
        }

        /** Null when the server has no (or an incompatible) Folia scheduler API. */
        static Regionized create(Plugin plugin) {
            try {
                Class<?> globalType = Class.forName(PKG + "GlobalRegionScheduler");
                Class<?> asyncType = Class.forName(PKG + "AsyncScheduler");
                Class<?> taskType = Class.forName(PKG + "ScheduledTask");
                Object global = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Object async = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                if (global == null || async == null) return null;
                return new Regionized(plugin, global, async,
                    globalType.getMethod("execute", Plugin.class, Runnable.class),
                    globalType.getMethod("cancelTasks", Plugin.class),
                    asyncType.getMethod("runNow", Plugin.class, Consumer.class),
                    asyncType.getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class),
                    asyncType.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class),
                    asyncType.getMethod("cancelTasks", Plugin.class),
                    taskType.getMethod("cancel"));
            } catch (Throwable t) {
                return null;
            }
        }

        boolean runGlobal(Runnable task) {
            return call(globalExecute, global, plugin, task) != FAILED;
        }

        boolean runAsync(Runnable task) {
            return call(asyncRunNow, async, plugin, consumer(task)) != FAILED;
        }

        Runnable runAsyncRepeating(Runnable task, long seconds) {
            return canceller(call(asyncRunAtFixedRate, async, plugin, consumer(task), seconds, seconds, TimeUnit.SECONDS));
        }

        Runnable runAsyncLater(Runnable task, long seconds) {
            Object scheduled = seconds <= 0
                ? call(asyncRunNow, async, plugin, consumer(task))
                : call(asyncRunDelayed, async, plugin, consumer(task), seconds, TimeUnit.SECONDS);
            return canceller(scheduled);
        }

        void cancelAll() {
            call(globalCancelTasks, global, plugin);
            call(asyncCancelTasks, async, plugin);
        }

        private Runnable canceller(final Object scheduledTask) {
            if (scheduledTask == null || scheduledTask == FAILED) return NOOP;
            return new Runnable() {
                @Override public void run() { call(taskCancel, scheduledTask); }
            };
        }

        private static Consumer<Object> consumer(final Runnable task) {
            return new Consumer<Object>() {
                @Override public void accept(Object scheduledTask) { task.run(); }
            };
        }

        private static final Object FAILED = new Object();

        /** Invoke, returning the result or FAILED. Folia refuses tasks from disabled plugins. */
        private Object call(Method method, Object target, Object... args) {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (plugin.isEnabled()) {
                    plugin.getLogger().log(Level.WARNING, "Folia scheduler call " + method.getName() + " failed", cause);
                }
                return FAILED;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Folia scheduler call " + method.getName() + " failed", e);
                return FAILED;
            }
        }
    }
}
