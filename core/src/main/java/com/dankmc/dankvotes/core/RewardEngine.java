package com.dankmc.dankvotes.core;

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
 *   - day-streak tracking and streak rewards (once a day, with the vote that moves the streak)
 *   - broadcast + thank-you messages
 *   - offline queueing (replay when the player next joins)
 *   - server-wide vote parties with persistent progress
 *
 * Statistics, rewards and vote messages can each be switched off (features.* in config.yml)
 * for servers that leave them to another plugin; the duplicate check, the event and
 * forwarding always run.
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

        // behaviour.offline-votes: false - only players who are online when their vote arrives
        // count. Anything else stops here: not counted, saved, rewarded, announced or forwarded.
        // A vote a DankVotes proxy forwarded has already passed the proxy's (network-wide) check.
        if (!config.offlineVotes && !vote.isForwarded() && !platform.isPlayerOnline(vote.getUsername())) {
            platform.getLogger().info("Ignoring vote from " + vote.getUsername() + " via " + vote.getServiceName()
                + ": the player is offline and behaviour.offline-votes is false.");
            return false;
        }

        // Give other plugins a chance to react or cancel (Bukkit event on Paper).
        if (!platform.fireVoteEvent(vote)) {
            debug("Vote event for " + vote.getUsername() + " was cancelled by another plugin.");
            return false;
        }

        platform.getLogger().info("Vote received: " + vote.getUsername() + " via " + vote.getServiceName()
            + (vote.isVerified() ? " [verified]" : " [unverified]"));

        // Count it now regardless of online status (totals, streaks, leaderboard). With statistics
        // off nothing is counted; reminders still need to know who voted today, so only that is kept.
        int newCount = 0;
        int streak = 0;
        int streakDay = 0;
        if (config.statisticsEnabled) {
            VoteStorage.Recorded recorded = storage.countVote(vote.getUsername(), vote.getTimestamp(), config.streaksEnabled);
            newCount = recorded.votes;
            streakDay = recorded.streakDay;
            if (config.streaksEnabled) streak = storage.getStreak(vote.getUsername());
        } else if (config.reminderEnabled) {
            storage.markVoted(vote.getUsername(), vote.getTimestamp());
        }
        Vote counted = vote.withCount(newCount, streakDay);

        // Hand it to the backend servers first, so their rewards never wait on ours.
        VoteForwarder f = forwarder;
        if (f != null) {
            try {
                f.forward(counted);
            } catch (Exception e) {
                platform.getLogger().warning("Could not queue the vote for forwarding: " + e.getMessage());
            }
        }

        if (config.voteMessagesEnabled) {
            if (config.broadcastEnabled) {
                broadcast(config.broadcastMessage, counted, newCount, streak);
            }
            // Once a day, with the vote that moved the streak, not again for every site voted on.
            if (config.streaksActive() && config.streakBroadcastEnabled && streakDay > 1) {
                broadcast(config.streakBroadcastMessage, counted, newCount, streakDay);
            }
        }

        if (config.rewardsEnabled && config.queueOfflineVotes && !platform.isPlayerOnline(vote.getUsername())) {
            storage.queueOfflineVote(counted);
            platform.getLogger().info("Player " + vote.getUsername() + " is offline - reward queued for next join.");
        } else {
            deliverRewards(counted, newCount, streak, config.statisticsEnabled);
        }

        trackVoteParty();
        return true;
    }

    /**
     * Replay any queued votes for a player who just joined.
     * Called by platform join listeners (async).
     */
    public synchronized void replayQueuedVotes(String username) {
        // Rewards switched off since these were saved: keep them for when rewards are back on.
        if (!config.rewardsEnabled) return;
        List<Vote> queued = storage.drainOfflineVotes(username);
        if (queued.isEmpty()) return;

        platform.getLogger().info("Delivering " + queued.size() + " queued vote reward(s) to " + username);
        int streak = streakOf(username);
        for (Vote v : queued) {
            // Use the count snapshot taken when the vote was queued so EVERY/AT milestones
            // trigger correctly even when several votes are replayed at once. A vote saved
            // with no count (statistics were off) was never counted: it gets its rewards but
            // no milestones, which would otherwise be paid again on the player's current total.
            // Streak rewards follow the streak day saved with each vote (see deliverRewards).
            boolean counted = v.getVoteNumber() > 0;
            int count = !config.statisticsEnabled ? 0 : counted ? v.getVoteNumber() : votesOf(username);
            deliverRewards(v, count, streak, counted);
        }
    }

    /** Manually trigger a vote party (admin command). */
    public synchronized void forceVoteParty() {
        runVoteParty();
    }

    // ── Internal ─────────────────────────────────────────────────────

    /** @param counted whether this vote was counted, i.e. {@code count} is the player's total including it */
    private void deliverRewards(Vote vote, int count, int streak, boolean counted) {
        if (config.rewardsEnabled) {
            deliverBaseRewards(vote, count, streak);
            // Milestones and streak rewards are keyed on the player's counted votes.
            if (config.statisticsEnabled && counted) {
                deliverMilestones(config.milestones, count, vote, count, streak, "Milestone");
                // Streak rewards go with the vote that moved the streak: once a day, however many
                // sites the player votes on, and for the day that vote reached.
                int day = vote.getStreakDay();
                if (config.streaksEnabled && day > 0) {
                    deliverMilestones(config.streakRewards, day, vote, count, day, "Streak");
                }
            }
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
        if (config.voteMessagesEnabled && config.thankYouEnabled && !Strings.isBlank(config.thankYouMessage)
                && platform.isPlayerOnline(vote.getUsername())) {
            platform.messagePlayer(vote.getUsername(),
                applyPlaceholders(config.thankYouMessage, vote, count, streak));
        }
    }

    /** Broadcast a message template; a message set to "" in config.yml is not sent at all. */
    private void broadcast(String template, Vote vote, int count, int streak) {
        if (Strings.isBlank(template)) return;
        platform.broadcast(applyPlaceholders(template, vote, count, streak));
    }

    private void trackVoteParty() {
        if (!config.votePartyEnabled || config.votePartyGoal <= 0) return;
        int progress = storage.incrementPartyProgress();
        if (progress >= config.votePartyGoal) {
            runVoteParty();
        } else if (config.votePartyAnnounceEvery > 0 && progress % config.votePartyAnnounceEvery == 0) {
            broadcast(config.votePartyProgressMessage, null, 0, 0);
        }
    }

    private void runVoteParty() {
        storage.resetPartyProgress();
        platform.getLogger().info("Vote party triggered!");
        broadcast(config.votePartyStartMessage, null, 0, 0);

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
                            votesOf(name), streakOf(name)));
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
     * While statistics (or the vote party) are off, their placeholders are 0, never a stale count.
     */
    public String applyPlaceholders(String input, Vote vote, int count, int streak) {
        if (input == null) return "";
        boolean stats = config.statisticsEnabled;
        String out = input;
        if (vote != null) {
            out = out.replace("%player%", vote.getUsername())
                     .replace("%service%", vote.getServiceName())
                     .replace("%best_streak%", String.valueOf(stats ? storage.getBestStreak(vote.getUsername()) : 0));
        }
        boolean party = config.votePartyEnabled;
        int goal = party ? config.votePartyGoal : 0;
        int progress = party ? storage.getPartyProgress() : 0;
        out = out.replace("%votes%", String.valueOf(count))
                 .replace("%streak%", String.valueOf(streak))
                 .replace("%party_progress%", String.valueOf(progress))
                 .replace("%party_goal%", String.valueOf(goal))
                 .replace("%party_remaining%", String.valueOf(Math.max(0, goal - progress)))
                 .replace("%total_votes%", String.valueOf(stats ? storage.getTotalVotes() : 0));
        return out;
    }

    /** Placeholders for a player outside of a vote (commands, reminders), with their own count and streak. */
    public String applyPlayerPlaceholders(String input, String player) {
        Vote ctx = new Vote(player, "", "", System.currentTimeMillis(), true, 0);
        return applyPlaceholders(input, ctx, votesOf(player), streakOf(player));
    }

    /** The player's counted votes, or 0 while statistics are off. */
    int votesOf(String player) {
        return config.statisticsEnabled ? storage.getVoteCount(player) : 0;
    }

    /** The player's current streak, or 0 while streaks (or statistics) are off. */
    int streakOf(String player) {
        return config.streaksActive() ? storage.getStreak(player) : 0;
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
