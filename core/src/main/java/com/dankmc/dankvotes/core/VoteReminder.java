package com.dankmc.dankvotes.core;

/**
 * Periodically reminds online players who haven't voted today, and (optionally) shortly
 * after they join. Players with the {@code dankvotes.reminder.bypass} permission are
 * never reminded.
 */
public class VoteReminder {

    public static final String BYPASS_PERMISSION = "dankvotes.reminder.bypass";

    private final Platform platform;
    private final DankVotesConfig config;
    private final VoteStorage storage;
    private final RewardEngine engine;
    private Platform.TaskHandle handle;

    public VoteReminder(Platform platform, DankVotesConfig config, VoteStorage storage, RewardEngine engine) {
        this.platform = platform;
        this.config = config;
        this.storage = storage;
        this.engine = engine;
    }

    public void start() {
        if (!config.reminderEnabled || config.reminderIntervalMinutes <= 0) return;
        long seconds = Math.max(60L, config.reminderIntervalMinutes * 60L);
        handle = platform.scheduleRepeatingAsync(new Runnable() {
            @Override public void run() { remindAll(); }
        }, seconds);
    }

    public void stop() {
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
    }

    /** Called from the join listener. */
    public void onJoin(final String username) {
        if (!config.reminderEnabled || !config.reminderOnJoin) return;
        platform.scheduleDelayedAsync(new Runnable() {
            @Override public void run() { remind(username); }
        }, Math.max(0, config.reminderOnJoinDelaySeconds));
    }

    private void remindAll() {
        for (String name : platform.getOnlinePlayerNames()) {
            remind(name);
        }
    }

    private void remind(String username) {
        if (Strings.isBlank(config.reminderMessage)) return;
        if (!platform.isPlayerOnline(username)) return;
        if (storage.hasVotedToday(username)) return;
        if (platform.hasPermission(username, BYPASS_PERMISSION)) return;
        platform.messagePlayer(username, engine.applyPlayerPlaceholders(config.reminderMessage, username));
    }
}
