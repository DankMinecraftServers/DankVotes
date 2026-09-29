package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The reward engine. Takes a Vote and turns it into in-game rewards according to config:
 *   - duplicate suppression (same player + site within a window)
 *   - a platform-native event other plugins can listen to / cancel
 *   - base reward commands (per-command chance + optional permission gate)
 *   - milestone rewards (every N votes, or at exactly N votes)
 *   - day-streak tracking and streak rewards
 *   - broadcast + thank-you messages
 *   - offline queueing (replay when the player next joins)
 *   - server-wide vote parties with persistent progress
 *
 * All command dispatch goes through Platform, which handles thread-correctness
 * (critical for Folia's region scheduler). processVote() is synchronized: votes are rare
 * events, and serialising them keeps counts, streaks and party progress consistent.
 */
public class RewardEngine {

    private final Platform platform;
    private final DankVotesConfig config;
    private final VoteStorage storage;

    /** Dedupe: "user|service" -> epoch millis of the last accepted vote. */
    private final Map<String, Long> recent = new ConcurrentHashMap<String, Long>();

    /** Passes accepted votes on to backend servers (proxies); null when forwarding is off. */
    private volatile VoteForwarder forwarder;

    /** False once the core starts shutting down: new votes are refused so their source retries. */
    private volatile boolean accepting = true;

    /** What happened to a vote handed to {@link #processVoteDetailed}. */
    public enum Outcome {
        /** Counted and rewarded (or queued). */
        ACCEPTED,
        /** Refused for good: invalid name, duplicate, unverified, or cancelled by another plugin. */
        REJECTED,
        /** DankVotes is shutting down or reloading; the sender should deliver it again later. */
        STOPPING
    }

    public RewardEngine(Platform platform, DankVotesConfig config, VoteStorage storage) {
        this.platform = platform;
        this.config = config;
        this.storage = storage;
    }

    /**
     * Process an incoming vote. Safe to call from any thread; dispatches to the platform
     * scheduler as needed. Returns true if the vote was accepted (counted).
     */
    public boolean processVote(Vote vote) {
        return processVoteDetailed(vote) == Outcome.ACCEPTED;
    }

    /** Like {@link #processVote} but tells a refusal apart from "shutting down, try again". */
    public synchronized Outcome processVoteDetailed(Vote vote) {
        if (!accepting) return Outcome.STOPPING;
        return process(vote) ? Outcome.ACCEPTED : Outcome.REJECTED;
    }

    public boolean isAccepting() { return accepting; }

    /** Refuse new votes from now on; returns once any vote being processed has finished. */
    public void stopAccepting() {
        accepting = false;
        synchronized (this) {
            // Holding the lock means no processVote is mid-flight any more.
        }
    }

    private boolean process(Vote vote) {
        if (Strings.isBlank(vote.getUsername())) {
            platform.getLogger().warning("Ignoring vote with empty username from " + vote.getServiceName());
            return false;
        }
        if (!isValidUsername(vote.getUsername())) {
            platform.getLogger().warning("Ignoring vote with invalid username '" + vote.getUsername()
                + "' from " + vote.getServiceName());
            return false;
        }

        if (config.requireVerified && !vote.isVerified()) {
            platform.getLogger().info("Skipping unverified vote from " + vote.getUsername()
                + " (require-verified is on).");
            return false;
        }

        if (isDuplicate(vote)) {
            platform.getLogger().info("Ignoring duplicate vote from " + vote.getUsername() + " via "
                + vote.getServiceName() + " (within " + config.duplicateWindowSeconds + "s).");
            return false;
        }

        // Give other plugins a chance to react or cancel (Bukkit event on Paper).
        if (!platform.fireVoteEvent(vote)) {
            debug("Vote event for " + vote.getUsername() + " was cancelled by another plugin.");
            return false;
        }

        platform.getLogger().info("Vote received: " + vote.getUsername() + " via " + vote.getServiceName()
            + (vote.isVerified() ? " [verified]" : " [unverified]"));

        // Count it now regardless of online status (totals, streaks, party, leaderboard).
        int newCount = storage.recordVote(vote.getUsername(), vote.getTimestamp(), config.streaksEnabled);
        Vote counted = vote.withVoteNumber(newCount);
        int streak = storage.getStreak(vote.getUsername());

        // Hand it to the backend servers first, so their rewards never wait on ours.
        VoteForwarder f = forwarder;
        if (f != null) {
            try {
                f.forward(counted);
            } catch (Exception e) {
                platform.getLogger().warning("Could not queue the vote for forwarding: " + e.getMessage());
            }
        }

        if (config.broadcastEnabled) {
            platform.broadcast(applyPlaceholders(config.broadcastMessage, counted, newCount, streak));
        }
        if (config.streaksEnabled && config.streakBroadcastEnabled && streak > 1) {
            platform.broadcast(applyPlaceholders(config.streakBroadcastMessage, counted, newCount, streak));
        }

        boolean online = platform.isPlayerOnline(vote.getUsername());
        if (!online && config.queueOfflineVotes) {
            storage.queueOfflineVote(counted);
            platform.getLogger().info("Player " + vote.getUsername() + " is offline - reward queued for next join.");
        } else {
            deliverRewards(counted, newCount, streak);
        }

        trackVoteParty();
        return true;
    }

    /**
     * Replay any queued votes for a player who just joined.
     * Called by platform join listeners (async).
     */
    public synchronized void replayQueuedVotes(String username) {
        List<Vote> queued = storage.drainOfflineVotes(username);
        if (queued.isEmpty()) return;

        platform.getLogger().info("Delivering " + queued.size() + " queued vote reward(s) to " + username);
        int streak = storage.getStreak(username);
        for (Vote v : queued) {
            // Use the count snapshot taken when the vote was queued so EVERY/AT milestones
            // trigger correctly even when several votes are replayed at once.
            int count = v.getVoteNumber() > 0 ? v.getVoteNumber() : storage.getVoteCount(username);
            deliverRewards(v, count, streak);
        }
    }

    /** Manually trigger a vote party (admin command). */
    public synchronized void forceVoteParty() {
        runVoteParty();
    }

    // ── Internal ─────────────────────────────────────────────────────

    private void deliverRewards(Vote vote, int count, int streak) {
        deliverBaseRewards(vote, count, streak);
        deliverMilestones(config.milestones, count, vote, count, streak, "Milestone");
        if (config.streaksEnabled) {
            deliverMilestones(config.streakRewards, streak, vote, count, streak, "Streak");
        }
        sendThankYou(vote, count, streak);
    }

    private void deliverBaseRewards(Vote vote, int count, int streak) {
        for (DankVotesConfig.RewardCommand rc : config.rewards) {
            if (!permitted(vote.getUsername(), rc)) continue;
            if (rollChance(rc.chance)) {
                runCommand(rc.command, vote, count, streak);
            }
        }
    }

    /** Evaluate EVERY/AT milestones against {@code value} (a vote count or a streak length). */
    private void deliverMilestones(List<DankVotesConfig.Milestone> milestones, int value,
                                   Vote vote, int count, int streak, String label) {
        for (DankVotesConfig.Milestone m : milestones) {
            boolean trigger = false;
            switch (m.type) {
                case EVERY:
                    trigger = m.votes > 0 && value > 0 && value % m.votes == 0;
                    break;
                case AT:
                    trigger = value == m.votes;
                    break;
                default:
                    break;
            }
            if (trigger) {
                platform.getLogger().info(label + " hit for " + vote.getUsername() + ": " + m.type + " " + m.votes);
                for (DankVotesConfig.RewardCommand rc : m.commands) {
                    if (!permitted(vote.getUsername(), rc)) continue;
                    if (rollChance(rc.chance)) {
                        runCommand(rc.command, vote, count, streak);
                    }
                }
            }
        }
    }

    private void sendThankYou(Vote vote, int count, int streak) {
        if (config.thankYouEnabled && platform.isPlayerOnline(vote.getUsername())) {
            platform.messagePlayer(vote.getUsername(),
                applyPlaceholders(config.thankYouMessage, vote, count, streak));
        }
    }

    private void trackVoteParty() {
        if (!config.votePartyEnabled || config.votePartyGoal <= 0) return;
        int progress = storage.incrementPartyProgress();
        if (progress >= config.votePartyGoal) {
            runVoteParty();
        } else if (config.votePartyAnnounceEvery > 0 && progress % config.votePartyAnnounceEvery == 0) {
            platform.broadcast(applyPlaceholders(config.votePartyProgressMessage, null, 0, 0));
        }
    }

    private void runVoteParty() {
        storage.resetPartyProgress();
        platform.getLogger().info("Vote party triggered!");
        platform.broadcast(applyPlaceholders(config.votePartyStartMessage, null, 0, 0));

        List<String> online = platform.getOnlinePlayerNames();
        for (DankVotesConfig.RewardCommand rc : config.votePartyRewards) {
            if (rc.command == null || Strings.isBlank(rc.command)) continue;
            if (rc.command.contains("%player%")) {
                // Per-player command: run once for every online player.
                for (String name : online) {
                    if (!Strings.isBlank(rc.permission) && !platform.hasPermission(name, rc.permission)) continue;
                    if (rollChance(rc.chance)) {
                        platform.dispatchConsoleCommand(applyPlaceholders(rc.command,
                            new Vote(name, "party", "", System.currentTimeMillis(), true, 0),
                            storage.getVoteCount(name), storage.getStreak(name)));
                    }
                }
            } else if (rollChance(rc.chance)) {
                // Global command (e.g. "crate giveall vote 1"): run once.
                platform.dispatchConsoleCommand(applyPlaceholders(rc.command, null, 0, 0));
            }
        }
    }

    private boolean permitted(String username, DankVotesConfig.RewardCommand rc) {
        if (rc == null || Strings.isBlank(rc.permission)) return true;
        return platform.hasPermission(username, rc.permission);
    }

    private void runCommand(String command, Vote vote, int count, int streak) {
        if (command == null || Strings.isBlank(command)) return;
        platform.dispatchConsoleCommand(applyPlaceholders(command, vote, count, streak));
    }

    private boolean rollChance(double chance) {
        if (chance >= 100.0) return true;
        if (chance <= 0.0) return false;
        return ThreadLocalRandom.current().nextDouble(100.0) < chance;
    }

    private boolean isDuplicate(Vote vote) {
        int window = config.duplicateWindowSeconds;
        if (window <= 0) return false;
        long now = System.currentTimeMillis();
        String key = vote.getUsername().toLowerCase() + "|" + normaliseService(vote.getServiceName());
        Long last = recent.get(key);
        if (last != null && now - last < window * 1000L) {
            return true;
        }
        recent.put(key, now);
        // Opportunistic prune so the map can't grow unbounded on a busy network.
        if (recent.size() > 5000) {
            Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > window * 1000L) it.remove();
            }
        }
        return false;
    }

    /** "DankMinecraftServers", "dankminecraftservers.com" and "DankMinecraftServers.com" all match. */
    static String normaliseService(String service) {
        if (service == null) return "";
        String s = service.toLowerCase();
        if (s.endsWith(".com") || s.endsWith(".net") || s.endsWith(".org")) s = s.substring(0, s.lastIndexOf('.'));
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    static boolean isValidUsername(String name) {
        if (name == null) return false;
        int len = name.length();
        if (len < 1 || len > 16) return false;
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') continue;
            // Geyser/Floodgate prefixes (".Steve", "*Steve", "+Steve", "-Steve", "~Steve", "!Steve") - first character only
            if (i == 0 && ".*+-~!".indexOf(c) >= 0) continue;
            return false;
        }
        return true;
    }

    /**
     * Expand placeholders in messages and commands.
     * %player% %service% %votes% %streak% %best_streak% %party_progress% %party_goal% %party_remaining% %total_votes%
     */
    public String applyPlaceholders(String input, Vote vote, int count, int streak) {
        if (input == null) return "";
        String out = input;
        if (vote != null) {
            out = out.replace("%player%", vote.getUsername())
                     .replace("%service%", vote.getServiceName())
                     .replace("%best_streak%", String.valueOf(storage.getBestStreak(vote.getUsername())));
        }
        int goal = config.votePartyGoal;
        int progress = storage.getPartyProgress();
        out = out.replace("%votes%", String.valueOf(count))
                 .replace("%streak%", String.valueOf(streak))
                 .replace("%party_progress%", String.valueOf(progress))
                 .replace("%party_goal%", String.valueOf(goal))
                 .replace("%party_remaining%", String.valueOf(Math.max(0, goal - progress)))
                 .replace("%total_votes%", String.valueOf(storage.getTotalVotes()));
        return out;
    }

    private void debug(String msg) {
        if (config.debug) platform.getLogger().info("[debug] " + msg);
    }

    void setForwarder(VoteForwarder forwarder) { this.forwarder = forwarder; }

    public int getVotePartyProgress() { return storage.getPartyProgress(); }
    public int getVotePartyGoal() { return config.votePartyGoal; }
    public VoteStorage getStorage() { return storage; }
    public DankVotesConfig getConfig() { return config; }
}
