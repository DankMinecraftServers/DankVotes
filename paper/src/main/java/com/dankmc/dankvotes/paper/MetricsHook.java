package com.dankmc.dankvotes.paper;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

import java.util.concurrent.Callable;

/**
 * bStats metrics (https://bstats.org). Isolated in its own class so the main plugin never
 * references bStats classes directly; if they fail to load for any reason the plugin keeps
 * working without metrics.
 *
 * SETUP: register the plugin at https://bstats.org/getting-started to receive a numeric
 * plugin id, then set {@link #PLUGIN_ID}. While it is 0, metrics are skipped entirely.
 * Server owners can opt out with "metrics: false" in config.yml (or the global bStats
 * config in plugins/bStats/config.yml).
 */
final class MetricsHook {

    /** bStats plugin id - obtain from bstats.org (0 = metrics disabled). */
    static final int PLUGIN_ID = 0;

    private MetricsHook() {}

    static boolean start(final DankVotesPaper plugin) {
        if (PLUGIN_ID <= 0) return false;
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
        return true;
    }
}
