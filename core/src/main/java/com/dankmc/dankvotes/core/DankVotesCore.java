package com.dankmc.dankvotes.core;

/**
 * Central orchestrator for the core module. Each platform creates one of these,
 * passing in its Platform implementation and parsed config. It owns the storage,
 * reward engine, poller, Votifier server, reminders and update checker.
 */
public class DankVotesCore {

    private final Platform platform;
    private final DankVotesConfig config;
    private final VoteStorage storage;
    private final RewardEngine engine;
    private final ApiPoller poller;
    private final VotifierServer votifierServer;
    private final VoteReminder reminder;
    private final UpdateChecker updateChecker;
    private final VoteForwarder forwarder;
    private final long startedAt = System.currentTimeMillis();

    public DankVotesCore(Platform platform, DankVotesConfig config) {
        this.platform = platform;
        this.config = config;
        FeatureReview.applyDefaults(config);
        this.storage = new VoteStorage(platform.getDataFolder());
        this.engine = new RewardEngine(platform, config, storage);
        this.poller = new ApiPoller(platform, config, engine);
        this.votifierServer = new VotifierServer(platform, config, engine);
        this.reminder = new VoteReminder(platform, config, storage, engine);
        this.updateChecker = new UpdateChecker(platform, config);
        this.forwarder = new VoteForwarder(platform, config);
    }

    /** Start polling and/or the Votifier listener based on config. */
    public void start() {
        platform.getLogger().info("DankVotes v" + platform.getPluginVersion() + " starting on " + platform.getPlatformName());
        String off = FeatureReview.summary(config);
        if (off != null) platform.getLogger().info(off);
        if (!config.offlineVotes) {
            platform.getLogger().info("Votes from players who are offline are ignored (behaviour.offline-votes: false).");
        }
        for (String warning : FeatureReview.warnings(config)) platform.getLogger().warning(warning);

        // Forwarding first, so no vote can arrive before it is ready to pass them on.
        forwarder.start();
        if (forwarder.isRunning()) engine.setForwarder(forwarder);
        if (config.pollingEnabled) poller.start();
        if (config.votifierEnabled) votifierServer.start();
        reminder.start();
        updateChecker.start();

        if (!config.pollingEnabled && !config.votifierEnabled && !config.nuVotifierHookEnabled) {
            platform.getLogger().warning("Polling, Votifier and the NuVotifier hook are all disabled - "
                + "DankVotes will not receive any votes! Enable at least one in config.yml.");
        }
        if (config.voteLinks.isEmpty() && config.commandEnabled("vote")) {
            platform.getLogger().info("Tip: add your vote sites under vote-links in config.yml so /vote shows them to players.");
        }
    }

    public void stop() {
        // Refuse new votes first (senders retry; the poller leaves them unacknowledged), then
        // wait for the vote being processed, so everything accepted is also queued for forwarding.
        engine.stopAccepting();
        poller.stop();
        votifierServer.stop();
        reminder.stop();
        updateChecker.stop();
        forwarder.stop();
        storage.close();
        platform.getLogger().info("DankVotes stopped.");
    }

    /** Called by platform join listeners: replay offline-queued votes, then maybe remind. */
    public void onPlayerJoin(final String username) {
        platform.runAsync(new Runnable() {
            @Override public void run() {
                engine.replayQueuedVotes(username);
                if (!storage.hasVotedToday(username)) reminder.onJoin(username);
            }
        });
    }

    public long getUptimeMillis() { return System.currentTimeMillis() - startedAt; }

    public Platform getPlatform() { return platform; }
    public RewardEngine getEngine() { return engine; }
    public VoteStorage getStorage() { return storage; }
    public DankVotesConfig getConfig() { return config; }
    public ApiPoller getPoller() { return poller; }
    public VotifierServer getVotifierServer() { return votifierServer; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public VoteForwarder getForwarder() { return forwarder; }
}
