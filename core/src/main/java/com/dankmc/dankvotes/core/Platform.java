package com.dankmc.dankvotes.core;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Abstraction over the host platform (Paper, Folia, Velocity, etc.).
 * The core module talks only to this interface so it stays platform-agnostic.
 *
 * Methods with default implementations are optional conveniences; platforms should
 * override them where the host API supports the behaviour.
 */
public interface Platform {

    Logger getLogger();

    /** Absolute path to the plugin's data folder (where config.yml lives). */
    java.io.File getDataFolder();

    /** Is a player with this exact name currently online? */
    boolean isPlayerOnline(String username);

    /**
     * Run a console command. Implementations MUST ensure this executes on the correct
     * thread for their platform (global region scheduler on Folia, main thread on Bukkit,
     * or directly on Velocity which is async-safe for command dispatch).
     */
    void dispatchConsoleCommand(String command);

    /** Run a task on the main/global thread (for things that must be sync). */
    void runSync(Runnable task);

    /** Run a task asynchronously (off the main thread) — used for network I/O. */
    void runAsync(Runnable task);

    /**
     * Schedule a repeating async task. Returns a handle that can cancel it.
     * @param intervalSeconds how often to run (also used as the initial delay)
     */
    TaskHandle scheduleRepeatingAsync(Runnable task, long intervalSeconds);

    /**
     * Schedule a one-shot async task after a delay. Default: sleeps on a throwaway
     * daemon thread, which is fine for the rare uses (join reminders).
     */
    default TaskHandle scheduleDelayedAsync(final Runnable task, final long delaySeconds) {
        final boolean[] cancelled = {false};
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(Math.max(0L, delaySeconds) * 1000L);
                    if (!cancelled[0]) task.run();
                } catch (InterruptedException ignored) {}
            }
        }, "DankVotes-Delayed");
        t.setDaemon(true);
        t.start();
        return new TaskHandle() {
            @Override public void cancel() { cancelled[0] = true; t.interrupt(); }
        };
    }

    /** Broadcast a chat message to all online players (legacy '&' colour codes). */
    void broadcast(String message);

    /** Send a message to a specific player if online (legacy '&' colour codes). */
    void messagePlayer(String username, String message);

    /** Names of all currently online players. Used for vote parties and reminders. */
    default List<String> getOnlinePlayerNames() {
        return Collections.emptyList();
    }

    /** Does the (online) player have this permission? Offline players return false. */
    default boolean hasPermission(String username, String permission) {
        return false;
    }

    /**
     * Fire a platform-native "vote received" event so other plugins can react
     * (e.g. a Bukkit event). Return false if another plugin cancelled it.
     */
    default boolean fireVoteEvent(Vote vote) {
        return true;
    }

    /** Platform name for logging, e.g. "Paper", "Folia", "Velocity". */
    String getPlatformName();

    /** The plugin's own version string (from plugin.yml / the Velocity annotation). */
    default String getPluginVersion() {
        return "unknown";
    }

    /** A simple cancellable task handle. */
    interface TaskHandle {
        void cancel();
    }
}
