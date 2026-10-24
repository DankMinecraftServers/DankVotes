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

    // ── Forwarding (proxy -> backend servers) ────────────────────────
    /** Forward every accepted vote to the servers below over the Votifier v2 protocol. */
    public boolean forwardingEnabled = false;
    /** "all": every server gets every vote. "current": only the server the player is on. */
    public String forwardingMode = "all";
    public List<ForwardTarget> forwardingServers = new ArrayList<ForwardTarget>();

    // ── Features ─────────────────────────────────────────────────────
    // Each part can be switched off for servers that leave it to another plugin. A part that
    // is off does nothing at all; votes are still received, checked, announced to other
    // plugins (DankVoteEvent on Bukkit) and forwarded.
    /** Count votes per player: totals, streaks and the leaderboard (/votes, /votetop, placeholders). */
    public boolean statisticsEnabled = true;
    /** Run reward commands: per-vote rewards, milestones and streak rewards (saved for offline players). */
    public boolean rewardsEnabled = true;
    /** Send the vote broadcast, thank-you and streak messages. */
    public boolean voteMessagesEnabled = true;

    // ── Commands ─────────────────────────────────────────────────────
    // A player command that is off is never registered, so another plugin can have the name.
    public boolean commandVote = true;
    public boolean commandVotes = true;
    public boolean commandVoteTop = true;
    public boolean commandVoteParty = true;
    // /dankvotes subcommands.
    public boolean commandSetVotes = true;
    public boolean commandReset = true;
    public boolean commandParty = true;

    // ── Behaviour ────────────────────────────────────────────────────
    /**
     * Count votes from players who aren't online (on this server; on a proxy, the network).
     * false = such a vote is ignored completely: not counted, saved, rewarded, announced or
     * forwarded. Votes a DankVotes proxy forwards are counted as the proxy decided.
     */
    public boolean offlineVotes = true;
    /** With offline votes counted: save an offline player's rewards for their next join (false = run them at once). */
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

    // ── Effects ──────────────────────────────────────────────────────
    /**
     * Particles, sounds, fireworks and titles that celebrate votes (Bukkit-based servers and
     * Sponge; proxies have no worlds to show them in).
     */
    public boolean effectsEnabled = true;
    /** Whether config.yml has an effects section; a file from before 1.3.0 has none. */
    public boolean effectsConfigured = false;
    /**
     * Kinds of effect, across every effect at once (effects.particles, sounds, fireworks and
     * titles): false leaves that kind out everywhere, milestones' own effects included, and the
     * rest of each effect still plays. effectsEnabled switches all of it off.
     */
    public boolean effectParticles = true;
    public boolean effectSounds = true;
    public boolean effectFireworks = true;
    public boolean effectTitles = true;
    /** Every vote of a player who is online (and saved votes, when the player joins). */
    public Effect effectVote = Effect.vote();
    /** The day's first vote when it keeps a streak going (day 2 on); also streak rewards. */
    public Effect effectStreak = Effect.streak();
    /** A milestone is reached. */
    public Effect effectMilestone = Effect.milestone();
    /** Everyone online when a vote party starts. */
    public Effect effectParty = Effect.party();
    /** Goes with each vote reminder. */
    public Effect effectReminder = Effect.reminder();
    /** Effect settings that couldn't be used as written, one line each, for the console. */
    public List<String> effectProblems = new ArrayList<String>();
    /** Set by the core: false on proxies, which have no worlds, so the servers behind them play effects. */
    public boolean effectsPlayable = true;

    /** The kinds of effect switched off in config.yml ("particles", "sounds", "fireworks", "titles"). */
    public List<String> effectKindsOff() {
        List<String> off = new ArrayList<String>();
        if (!effectParticles) off.add("particles");
        if (!effectSounds) off.add("sounds");
        if (!effectFireworks) off.add("fireworks");
        if (!effectTitles) off.add("titles");
        return off;
    }

    // ── Misc ─────────────────────────────────────────────────────────
    public boolean updateCheckEnabled = true;
    /** GitHub "owner/repo" used by the update checker. */
    public String updateRepo = "DankMinecraftServers/DankVotes";
    public boolean metricsEnabled = true;
    public boolean debug = false;

    // ── Messages ─────────────────────────────────────────────────────
    public Messages messages = new Messages();

    // ═════════════════════════════════════════════════════════════════
    // What is switched on
    // ═════════════════════════════════════════════════════════════════

    /** Streaks are part of the statistics, so they only run while statistics are on. */
    public boolean streaksActive() {
        return statisticsEnabled && streaksEnabled;
    }

    /**
     * Whether a command is on, by canonical name (vote, votes, votetop, voteparty, dankvotes).
     * A command is off when its {@code commands.*} switch is false or the part it shows is off:
     * /votes and /votetop need statistics, /voteparty needs the vote party. /dankvotes is always on.
     */
    public boolean commandEnabled(String command) {
        return offReason(command) == null;
    }

    /**
     * Whether a /dankvotes subcommand is on (setvotes/addvotes and reset need statistics, party
     * the vote party, effect a server with worlds).
     */
    public boolean subcommandEnabled(String subcommand) {
        return subcommandOffReason(subcommand) == null;
    }

    /** The config.yml setting that keeps a command off ("commands.votes: false"), or null when it is on. */
    public String offReason(String command) {
        if ("vote".equals(command)) return commandVote ? null : "commands.vote: false";
        if ("votes".equals(command)) return !commandVotes ? "commands.votes: false" : statisticsOff();
        if ("votetop".equals(command)) return !commandVoteTop ? "commands.votetop: false" : statisticsOff();
        if ("voteparty".equals(command)) return !commandVoteParty ? "commands.voteparty: false" : votePartyOff();
        return null;
    }

    /** The config.yml setting that keeps a /dankvotes subcommand off, or null when it is on. */
    public String subcommandOffReason(String subcommand) {
        String sub = subcommand == null ? "" : subcommand.toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("setvotes") || sub.equals("addvotes")) return !commandSetVotes ? "commands.setvotes: false" : statisticsOff();
        if (sub.equals("reset")) return !commandReset ? "commands.reset: false" : statisticsOff();
        if (sub.equals("party")) return !commandParty ? "commands.party: false" : votePartyOff();
        if (sub.equals("effect")) return effectsPlayable ? null : "effects can't be shown on this server";
        return null;
    }

    private String statisticsOff() {
        return statisticsEnabled ? null : "features.statistics: false";
    }

    private String votePartyOff() {
        return votePartyEnabled ? null : "vote-party.enabled: false";
    }

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
        /**
         * This milestone's own effect: the trigger's effect (effects.milestone, or effects.streak
         * for streak rewards) with the keys config.yml sets here. Null = that effect unchanged.
         */
        public Effect effect;

        /** Whether reaching {@code value} (a vote count or a streak day) triggers it. */
        public boolean reachedAt(int value) {
            switch (type) {
                case EVERY:
                    return votes > 0 && value > 0 && value % votes == 0;
                case AT:
                    return value == votes;
                default:
                    return false;
            }
        }
    }

    /**
     * One vote effect: a particle animation, a sound, a firework and a title. Each trigger
     * (vote, streak, milestone, vote party, reminder) has its own; milestones and streak
     * rewards can change theirs.
     */
    public static class Effect {
        public static final List<String> FIREWORK_TYPES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("BALL", "BALL_LARGE", "STAR", "BURST", "CREEPER"));

        public boolean enabled = true;
        /** The particle animation (see {@link EffectShapes#ANIMATIONS}); "none" draws nothing. */
        public String animation = "none";
        /** The particle it draws with ("" = the animation's own), by name from 1.20.5 on or an older one. */
        public String particle = "";
        /** Colours (0xRRGGBB) for DUST particles and fireworks; empty = white. */
        public List<Integer> colors = new ArrayList<Integer>();
        /** A sound key as /playsound takes it ("" = none). */
        public String sound = "";
        public float volume = 1.0f;
        public float pitch = 1.0f;
        public boolean firework = false;
        /** BALL, BALL_LARGE, STAR, BURST or CREEPER. */
        public String fireworkType = "BALL_LARGE";
        /** Shown on the player's screen ("" = none); placeholders and & colour codes work. */
        public String title = "";
        public String subtitle = "";
        /** Players within 32 blocks see the particles and hear the sound too (a real firework, on Bukkit servers: always). */
        public boolean nearby = true;

        public Effect copy() {
            Effect e = new Effect();
            e.enabled = enabled;
            e.animation = animation;
            e.particle = particle;
            e.colors = new ArrayList<Integer>(colors);
            e.sound = sound;
            e.volume = volume;
            e.pitch = pitch;
            e.firework = firework;
            e.fireworkType = fireworkType;
            e.title = title;
            e.subtitle = subtitle;
            e.nearby = nearby;
            return e;
        }

        /** A switched-off copy (an override of "effect: none"). */
        public Effect off() {
            Effect e = copy();
            e.enabled = false;
            return e;
        }

        /** True when it has nothing to show or play, whether or not it is enabled. */
        public boolean isBlank() {
            return EffectShapes.frames(animation) == 0 && Strings.isBlank(sound) && !firework
                && Strings.isBlank(title) && Strings.isBlank(subtitle);
        }

        // The built-in effects. config.yml repeats these, so a missing key changes nothing.

        public static Effect vote() {
            Effect e = new Effect();
            e.animation = "burst";
            e.particle = "FIREWORK";
            e.colors = colours(0xFF55FF, 0xAA00AA, 0xFFFFFF);
            e.sound = "entity.player.levelup";
            e.fireworkType = "BURST";
            e.title = "&d&lTHANK YOU!";
            e.subtitle = "&7for voting, &f%player%&7! Vote again tomorrow";
            return e;
        }

        public static Effect streak() {
            Effect e = new Effect();
            e.animation = "tornado";
            e.particle = "FLAME";
            e.colors = colours(0xFFAA00, 0xFF5555, 0xFFFF55);
            e.sound = "item.firecharge.use";
            e.fireworkType = "BURST";
            e.title = "&6&l%streak%-DAY STREAK!";
            e.subtitle = "&7Vote again tomorrow to keep it going";
            return e;
        }

        public static Effect milestone() {
            Effect e = new Effect();
            e.animation = "helix";
            e.particle = "TOTEM_OF_UNDYING";
            e.colors = colours(0xFFAA00, 0xFFFF55, 0xFFFFFF);
            e.sound = "item.totem.use";
            e.volume = 0.8f;
            e.firework = true;
            e.fireworkType = "BALL_LARGE";
            e.title = "&6&lMILESTONE!";
            e.subtitle = "&e%votes% votes &7- thank you, &f%player%&7!";
            return e;
        }

        public static Effect party() {
            Effect e = new Effect();
            e.animation = "ring";
            e.particle = "DUST";
            e.colors = colours(0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x55FFFF, 0xFF55FF);
            e.sound = "ui.toast.challenge_complete";
            e.firework = true;
            e.fireworkType = "STAR";
            e.title = "&d&lVOTE PARTY!";
            e.subtitle = "&7Thanks to everyone who voted";
            e.nearby = false;
            return e;
        }

        public static Effect reminder() {
            Effect e = new Effect();
            e.animation = "none";
            e.sound = "block.note_block.chime";
            e.volume = 0.6f;
            e.nearby = false;
            return e;
        }

        private static List<Integer> colours(int... rgb) {
            List<Integer> out = new ArrayList<Integer>();
            for (int c : rgb) out.add(c);
            return out;
        }
    }

    /** A backend server that receives forwarded votes (its Votifier listener + v2 token). */
    public static class ForwardTarget {
        public final String name;
        public final String host;
        public final int port;
        public final String token;
        public ForwardTarget(String name, String host, int port, String token) {
            this.name = name == null ? "" : name;
            this.host = host == null ? "" : host;
            this.port = port;
            this.token = token == null ? "" : token;
        }
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
        public String commandDisabled = "&cThat command is turned off on this server.";

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
        public String testVoteRefused = "&cThe test vote for &f%player% &cwas not counted - the console says why.";
        public String votesSet = "&aSet &d%player%&a's vote count to &d%votes%&a.";
        public String votesReset = "&aReset vote data for &d%player%&a.";
        public String partyForced = "&aForced a vote party.";
        public String usage = "&7Usage: &f%usage%";
    }
}
