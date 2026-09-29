package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed plugin configuration. Populated by each platform from its own YAML loader,
 * then handed to the core engine. A plain POJO so core never depends on a YAML library.
 *
 * Field defaults here are the fallbacks used when a key is missing from config.yml.
 */
public class DankVotesConfig {

    // ── Connection modes ─────────────────────────────────────────────
    /** Enable the outbound polling client (recommended — no port forwarding needed). */
    public boolean pollingEnabled = true;
    public String apiBaseUrl = "https://dankminecraftservers.com";
    public String apiToken = "";
    public int pollIntervalSeconds = 15;

    /** Enable the inbound Votifier protocol server (compatible with all vote sites). */
    public boolean votifierEnabled = false;
    public String votifierHost = "0.0.0.0";
    public int votifierPort = 8192;
    /** Votifier v2 shared token (HMAC-SHA256). */
    public String votifierToken = "";
    /** Also accept classic Votifier v1 (RSA) connections on the same port. */
    public boolean votifierV1Enabled = true;

    /** If NuVotifier is installed, also consume votes it receives (no config needed on sites). */
    public boolean nuVotifierHookEnabled = true;

    /**
     * Ignore a vote if the same player already voted on the same service within this
     * many seconds. Protects against the same vote arriving via two channels (e.g.
     * Votifier push + API poll) or a site retrying a delivery. 0 disables.
     */
    public int duplicateWindowSeconds = 120;

    // ── Behaviour ────────────────────────────────────────────────────
    public boolean queueOfflineVotes = true;
    public boolean requireVerified = false;
    public boolean broadcastEnabled = true;
    public String broadcastMessage = "&d%player% &7voted for the server and earned a reward! &d/vote";
    public boolean thankYouEnabled = true;
    public String thankYouMessage = "&aThanks for voting, %player%! &7(&e%votes% &7total votes, &e%streak%&7-day streak)";

    // ── /vote links ──────────────────────────────────────────────────
    public List<VoteLink> voteLinks = new ArrayList<VoteLink>();

    // ── Rewards ──────────────────────────────────────────────────────
    /** Commands run for every vote. */
    public List<RewardCommand> rewards = new ArrayList<RewardCommand>();

    /** Cumulative milestone rewards (e.g. every 5th vote, or at vote #100). */
    public List<Milestone> milestones = new ArrayList<Milestone>();

    // ── Streaks ──────────────────────────────────────────────────────
    /** Track consecutive-day voting streaks. */
    public boolean streaksEnabled = true;
    /** Streak rewards keyed on consecutive days (EVERY n days, or AT exactly n). */
    public List<Milestone> streakRewards = new ArrayList<Milestone>();
    public boolean streakBroadcastEnabled = false;
    public String streakBroadcastMessage = "&d%player% &7is on a &d%streak%-day &7voting streak!";

    // ── Vote party ───────────────────────────────────────────────────
    public boolean votePartyEnabled = false;
    public int votePartyGoal = 50;
    public List<RewardCommand> votePartyRewards = new ArrayList<RewardCommand>();
    public String votePartyStartMessage = "&d&lVOTE PARTY! &7The server reached &d%party_goal% &7votes — rewards for everyone!";
    /** Broadcast progress every N votes (0 = never). */
    public int votePartyAnnounceEvery = 10;
    public String votePartyProgressMessage = "&7Vote party progress: &d%party_progress%&7/&d%party_goal% &7— &d%party_remaining% &7more votes to go! &d/vote";

    // ── Reminders ────────────────────────────────────────────────────
    public boolean reminderEnabled = true;
    public int reminderIntervalMinutes = 30;
    public boolean reminderOnJoin = true;
    public int reminderOnJoinDelaySeconds = 10;
    public String reminderMessage = "&7You haven't voted today! Support the server with &d/vote &7and earn rewards.";

    // ── Misc ─────────────────────────────────────────────────────────
    public boolean updateCheckEnabled = true;
    /** GitHub "owner/repo" used by the update checker. */
    public String updateRepo = "DankMinecraftServers/DankVotes";
    public boolean metricsEnabled = true;
    public boolean debug = false;

    // ── Messages ─────────────────────────────────────────────────────
    public Messages messages = new Messages();

    // ═════════════════════════════════════════════════════════════════
    // Nested types
    // ═════════════════════════════════════════════════════════════════

    /** A single reward command, optionally chance-based and permission-gated. */
    public static class RewardCommand {
        public String command;          // e.g. "give %player% diamond 3"
        public double chance = 100.0;   // 0-100; rolled per vote
        public String permission = "";  // if set, only runs when the player has it

        public RewardCommand() {}
        public RewardCommand(String command, double chance) {
            this.command = command;
            this.chance = chance;
        }
        public RewardCommand(String command, double chance, String permission) {
            this.command = command;
            this.chance = chance;
            this.permission = permission == null ? "" : permission;
        }
    }

    /**
     * A milestone keyed on a number (cumulative votes, or streak days).
     * type=EVERY runs every N; type=AT runs once when the value hits exactly N.
     */
    public static class Milestone {
        public enum Type { EVERY, AT }
        public Type type = Type.EVERY;
        public int votes = 5;
        public List<RewardCommand> commands = new ArrayList<RewardCommand>();
    }

    /** A vote site shown by /vote. */
    public static class VoteLink {
        public String name;
        public String url;
        public VoteLink(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }
    }

    /** All player-facing text. Legacy '&' colour codes are supported everywhere. */
    public static class Messages {
        public String prefix = "&8[&dDankVotes&8] &7";
        public String noPermission = "&cYou don't have permission to do that.";
        public String playerOnly = "&cThat command can only be used by a player.";
        public String playerNotFound = "&cNo vote data found for &f%player%&c.";

        // /vote
        public String voteHeader = "&8&m----------&r &d&lVOTE &8&m----------";
        public String voteLine = "&d» &f%name%&7: &b%url%";
        public String voteFooter = "&7Vote daily to earn rewards! Your total: &d%votes% &7| Streak: &d%streak% &7days";
        public String voteNoLinks = "&7No vote links have been configured yet.";

        // /votes
        public String votesSelf = "&7You have voted &d%votes% &7times (&d%streak%&7-day streak, best &d%best_streak%&7).";
        public String votesOther = "&d%player% &7has voted &d%votes% &7times (&d%streak%&7-day streak, best &d%best_streak%&7).";

        // /votetop
        public String voteTopHeader = "&8&m--------&r &d&lTOP VOTERS &8&m--------";
        public String voteTopLine = "&7#%rank% &d%player% &8- &f%votes% votes";
        public String voteTopEmpty = "&7Nobody has voted yet. Be the first with &d/vote&7!";

        // /voteparty
        public String votePartyStatus = "&7Vote party: &d%party_progress%&7/&d%party_goal% &7votes (&d%party_remaining% &7to go)";
        public String votePartyDisabled = "&7Vote parties are not enabled on this server.";

        // admin
        public String reloaded = "&aDankVotes configuration reloaded.";
        public String testVote = "&aSimulated a vote for &d%player%&a.";
        public String votesSet = "&aSet &d%player%&a's vote count to &d%votes%&a.";
        public String votesReset = "&aReset vote data for &d%player%&a.";
        public String partyForced = "&aForced a vote party.";
        public String usage = "&7Usage: &f%usage%";
    }
}
