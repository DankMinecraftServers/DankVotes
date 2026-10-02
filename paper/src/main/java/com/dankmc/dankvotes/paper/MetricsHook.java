package com.dankmc.dankvotes.paper;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

import java.util.concurrent.Callable;

/**
 * bStats metrics (https://bstats.org). Isolated in its own class so the main plugin never
 * references bStats classes directly; if they fail to load for any reason the plugin keeps
 * working without metrics. bStats 3.1+ detects Folia itself and never touches the Bukkit
 * scheduler there.
 *
 * Public stats: https://bstats.org/plugin/bukkit/DankVotes/34452 (the "delivery_mode" and
 * "platform" pies below must also exist as custom charts on that page to be shown).
 * Server owners can opt out with "metrics: false" in config.yml (or the global bStats
 * config in plugins/bStats/config.yml).
 */
final class MetricsHook {

    /** bStats plugin id (bstats.org, Bukkit platform). 0 would switch metrics off. */
    static final int PLUGIN_ID = 34452;

    private MetricsHook() {}

    /** Start metrics. Returns the running instance (pass it to {@link #stop}) or null. */
    static Object start(final DankVotesPaper plugin) {
        if (PLUGIN_ID <= 0) return null;
        Metrics metrics = new Metrics(plugin, PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("delivery_mode", new Callable<String>() {
            @Override public String call() {
                if (plugin.getCore() == null) return "unknown";
                boolean poll = plugin.getCore().getConfig().pollingEnabled;
                boolean votifier = plugin.getCore().getConfig().votifierEnabled;
                if (poll && votifier) return "polling+votifier";
                if (poll) return "polling";
                if (votifier) return "votifier";
                return plugin.isNuVotifierHooked() ? "nuvotifier-hook" : "none";
            }
        }));
        metrics.addCustomChart(new SimplePie("platform", new Callable<String>() {
            @Override public String call() {
                return plugin.getCore() == null ? "unknown" : plugin.getCore().getPlatform().getPlatformName();
            }
        }));
        return metrics;
    }

    /** Stop the bStats submit thread (it would otherwise outlive a plugin reload). */
    static void stop(Object metrics) {
        if (metrics instanceof Metrics) ((Metrics) metrics).shutdown();
    }
}
