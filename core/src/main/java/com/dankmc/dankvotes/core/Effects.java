package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which vote effect a player gets, and when. The platform draws it.
 *
 * Every delivered vote earns one effect, the biggest moment winning: a milestone's or streak
 * reward's own effect over a default one, then a milestone over a streak day over a plain
 * vote. Votes saved for an offline player play a single effect, a few seconds after they join.
 * Several sites voted on at once don't stack effects: for a few seconds after one starts,
 * only a bigger one can follow (a vote party, say), and the platform won't let a smaller one
 * cut off a bigger one that is still playing. An admin's preview always plays, and counts
 * as the moment it shows.
 */
public final class Effects {

    /** What an effect celebrates, least to most important. */
    public enum Trigger {
        REMINDER("reminder"),
        VOTE("vote"),
        STREAK("streak"),
        MILESTONE("milestone"),
        PARTY("vote-party");

        private final String key;

        Trigger(String key) {
            this.key = key;
        }

        /** The name used in config.yml (effects.&lt;key&gt;) and by /dankvotes effect. */
        public String key() {
            return key;
        }

        /** "vote", "streak", "milestone", "party" / "vote-party" or "reminder"; null for anything else. */
        public static Trigger parse(String name) {
            if (name == null) return null;
            String n = name.trim().toLowerCase(Locale.ROOT);
            if (n.equals("party") || n.equals("voteparty") || n.equals("vote-party")) return PARTY;
            for (Trigger t : values()) {
                if (t.key.equals(n) || (t.key + "s").equals(n)) return t;
            }
            return null;
        }
    }

    /** The names /dankvotes effect takes, for help and tab completion. */
    public static final List<String> PREVIEW_NAMES = java.util.Collections.unmodifiableList(
        java.util.Arrays.asList("vote", "streak", "milestone", "party", "reminder"));

    /** Ticks between a player joining and the effect of the votes saved for them (the world has to load first). */
    public static final int JOIN_DELAY_TICKS = 60;

    /** For this long after an effect starts, only a bigger one may follow. */
    static final long QUIET_MILLIS = 3000L;

    /**
     * How big a moment is: a vote party above all, then a milestone's or streak reward's own
     * effect, then the trigger (milestone over streak over vote over reminder).
     */
    static int rank(Trigger trigger, boolean own) {
        if (trigger == Trigger.PARTY) return 100;
        return trigger.ordinal() + (own ? 10 : 0);
    }

    /** What a delivered vote earned: the effect to play and the numbers for its title. */
    public static final class Moment {
        final Trigger trigger;
        final DankVotesConfig.Effect effect;
        final Vote vote;
        final int count;
        final int streak;
        /** The effect is a milestone's or streak reward's own. */
        final boolean own;
        /** Its title and subtitle with the placeholders filled in (see {@link Effects#prepare}); null until then. */
        String title;
        String subtitle;

        Moment(Trigger trigger, DankVotesConfig.Effect effect, Vote vote, int count, int streak, boolean own) {
            this.trigger = trigger;
            this.effect = effect;
            this.vote = vote;
            this.count = count;
            this.streak = streak;
            this.own = own;
        }

        boolean shows() {
            return effect != null && effect.enabled && !effect.isBlank();
        }

        int rank() {
            return Effects.rank(trigger, own);
        }

        public Trigger getTrigger() { return trigger; }
        public DankVotesConfig.Effect getEffect() { return effect; }
    }

    private final Platform platform;
    private final DankVotesConfig config;
    private final RewardEngine engine;
    /** Lower-case player name -> {when the last effect started (epoch ms), its rank}. */
    private final Map<String, long[]> recent = new HashMap<String, long[]>();

    Effects(Platform platform, DankVotesConfig config, RewardEngine engine) {
        this.platform = platform;
        this.config = config;
        this.engine = engine;
    }

    // ── moments ──────────────────────────────────────────────────────

    /**
     * A vote on its own: the vote effect, or for the day's first vote of a running streak
     * (day 2 on) the streak effect - unless that one is switched off, then still the vote's.
     */
    Moment vote(Vote vote, int count, int streak) {
        Moment plain = new Moment(Trigger.VOTE, config.effectVote, vote, count, streak, false);
        int day = vote.getStreakDay();
        if (config.streaksActive() && day >= 2) {
            return bigger(plain, new Moment(Trigger.STREAK, config.effectStreak, vote, count, day, false));
        }
        return plain;
    }

    /** A milestone the vote reached. */
    Moment milestone(DankVotesConfig.Milestone m, Vote vote, int count, int streak) {
        boolean own = m.effect != null;
        return new Moment(Trigger.MILESTONE, own ? m.effect : config.effectMilestone, vote, count, streak, own);
    }

    /** A streak reward the vote's streak day reached. */
    Moment streakReward(DankVotesConfig.Milestone m, Vote vote, int count, int day) {
        boolean own = m.effect != null;
        return new Moment(Trigger.STREAK, own ? m.effect : config.effectStreak, vote, count, day, own);
    }

    /**
     * The bigger of two moments: one that shows something beats one that doesn't, then the
     * higher {@link #rank}, and between equals the later one (milestones are listed smallest first).
     */
    static Moment bigger(Moment a, Moment b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.shows() != b.shows()) return a.shows() ? a : b;
        if (a.rank() != b.rank()) return a.rank() > b.rank() ? a : b;
        return b;
    }

    // ── playing ──────────────────────────────────────────────────────

    /**
     * Fill in a moment's title and subtitle now, with the numbers the vote's own messages
     * showed: the vote party's count moves on before the effect is handed over.
     */
    void prepare(Moment moment) {
        if (moment == null || moment.vote == null || !moment.shows() || !config.effectsEnabled || !config.effectsPlayable) return;
        moment.title = engine.applyPlaceholders(moment.effect.title, moment.vote, moment.count, moment.streak);
        moment.subtitle = engine.applyPlaceholders(moment.effect.subtitle, moment.vote, moment.count, moment.streak);
    }

    /** Play what a vote earned, after {@code delayTicks}. Does nothing for a player who isn't online here. */
    void celebrate(Moment moment, int delayTicks) {
        if (moment == null || moment.vote == null) return;
        if (moment.title == null) prepare(moment);
        play(moment.vote.getUsername(), moment.trigger, moment.rank(), moment.effect, moment.title, moment.subtitle, delayTicks, false);
    }

    /** The vote-party effect, for everyone online. */
    void party(List<String> online) {
        DankVotesConfig.Effect effect = config.effectParty;
        if (!active(effect)) return;
        int rank = rank(Trigger.PARTY, false);
        for (String name : online) {
            Vote context = context(name, "party");
            int votes = engine.votesOf(name);
            int streak = engine.streakOf(name);
            play(name, Trigger.PARTY, rank, effect, engine.applyPlaceholders(effect.title, context, votes, streak),
                engine.applyPlaceholders(effect.subtitle, context, votes, streak), 0, false);
        }
    }

    /** The reminder effect, with a reminder message just sent to the player. */
    void remind(String player) {
        DankVotesConfig.Effect effect = config.effectReminder;
        if (!active(effect)) return;
        Vote context = context(player, "");
        int votes = engine.votesOf(player);
        int streak = engine.streakOf(player);
        play(player, Trigger.REMINDER, rank(Trigger.REMINDER, false), effect, engine.applyPlaceholders(effect.title, context, votes, streak),
            engine.applyPlaceholders(effect.subtitle, context, votes, streak), 0, false);
    }

    private boolean active(DankVotesConfig.Effect effect) {
        return config.effectsEnabled && config.effectsPlayable && effect != null && effect.enabled && !effect.isBlank();
    }

    /**
     * Hand an effect to the platform, its title and subtitle already filled in. {@code force}
     * (a preview) plays it even when it is switched off or another effect has just started;
     * once started it counts as the moment it shows, so a vote party can still follow.
     */
    private synchronized boolean play(String player, Trigger trigger, int rank, DankVotesConfig.Effect effect, String title,
                                      String subtitle, int delayTicks, boolean force) {
        if (!config.effectsPlayable || effect == null || effect.isBlank()) return false;
        if (!force && (!config.effectsEnabled || !effect.enabled)) return false;
        String key = player.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        if (!force) {
            long[] last = recent.get(key);
            if (last != null && now - last[0] < QUIET_MILLIS && rank <= last[1]) {
                debug("Skipped the " + trigger.key() + " effect for " + player + ": a bigger or equal one has just started.");
                return false;
            }
        }
        EffectPlan plan = new EffectPlan(trigger.key(), effect, title, subtitle, delayTicks, rank, force);
        boolean started;
        try {
            started = platform.playEffect(player, plan);
        } catch (RuntimeException | LinkageError e) {
            // Effects are a nicety: whatever goes wrong, the vote itself is never affected.
            platform.getLogger().warning("Could not play the " + trigger.key() + " effect for " + player + ": " + e);
            return false;
        }
        if (!started) return false;
        debug("Playing " + plan + " for " + player);
        recent.put(key, new long[] {now + delayTicks * 50L, rank});
        if (recent.size() > 256) {
            Iterator<long[]> it = recent.values().iterator();
            while (it.hasNext()) {
                if (now - it.next()[0] > QUIET_MILLIS) it.remove();
            }
        }
        return true;
    }

    // ── /dankvotes effect ────────────────────────────────────────────

    /**
     * Play an effect on a player so an admin can see it, whatever the switches say.
     * {@code number} picks a milestone (a vote count) or streak reward (a day); 0 uses the
     * player's own. Returns the reply, in legacy colour codes.
     */
    public String preview(Trigger trigger, String player, int number) {
        if (!config.effectsPlayable) return "&7Effects can't be shown on this server (a proxy plays them on the servers behind it).";
        if (!platform.isPlayerOnline(player)) return "&c" + player + " isn't online on this server.";
        int votes = Math.max(1, engine.votesOf(player));
        int streak = Math.max(2, engine.streakOf(player));
        DankVotesConfig.Effect effect;
        boolean own = false;
        String which = "";
        switch (trigger) {
            case STREAK: {
                if (number > 0) streak = number;
                Moment best = null;
                for (DankVotesConfig.Milestone m : config.streakRewards) {
                    if (m.reachedAt(streak)) best = bigger(best, streakReward(m, null, votes, streak));
                }
                effect = best != null ? best.effect : config.effectStreak;
                own = best != null && best.own;
                if (own) which = " &7(the " + streak + "-day streak reward's own effect)";
                break;
            }
            case MILESTONE: {
                if (number > 0) votes = number;
                Moment best = null;
                for (DankVotesConfig.Milestone m : config.milestones) {
                    if (m.reachedAt(votes)) best = bigger(best, milestone(m, null, votes, streak));
                }
                effect = best != null ? best.effect : config.effectMilestone;
                own = best != null && best.own;
                if (own) which = " &7(the " + votes + "-vote milestone's own effect)";
                else if (best == null && number > 0) which = " &7(no milestone is reached at " + votes + " votes; this is effects.milestone)";
                break;
            }
            case PARTY: effect = config.effectParty; break;
            case REMINDER: effect = config.effectReminder; break;
            default: effect = config.effectVote; break;
        }
        if (effect == null || effect.isBlank()) {
            List<String> kindsOff = config.effectKindsOff();
            return "&7The " + trigger.key() + " effect is empty: no animation, sound, firework or title"
                + (kindsOff.isEmpty() ? "." : " (config.yml leaves out effect " + FeatureReview.join(kindsOff) + ").");
        }
        Vote context = context(player, trigger == Trigger.PARTY ? "party" : "preview");
        String title = engine.applyPlaceholders(effect.title, context, votes, streak);
        String subtitle = engine.applyPlaceholders(effect.subtitle, context, votes, streak);
        if (!play(player, trigger, rank(trigger, own), effect, title, subtitle, 0, true)) {
            return "&cCould not play the " + trigger.key() + " effect for " + player + " - see the console.";
        }
        String note = "";
        if (!config.effectsEnabled) note = " &7(effects.enabled is false: votes don't play effects until you turn it on.)";
        else if (!effect.enabled) note = " &7(It is switched off, so votes don't play it.)";
        List<String> kindsOff = config.effectKindsOff();
        if (!kindsOff.isEmpty()) note += " &7(Without " + FeatureReview.join(kindsOff) + ": config.yml leaves them out.)";
        return "&aPlaying the &d" + trigger.key() + " &aeffect for &d" + player + "&a." + which + note;
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** A stand-in vote for placeholders outside of a vote (party, reminder, preview). */
    private static Vote context(String player, String service) {
        return new Vote(player, service, "", System.currentTimeMillis(), true, 0);
    }

    /** The effect triggers that are switched on and can play here, for /dankvotes status. */
    List<String> activeTriggers() {
        List<String> out = new ArrayList<String>();
        if (!config.effectsEnabled || !config.effectsPlayable) return out;
        if (active(config.effectVote)) out.add("vote");
        if (config.streaksActive() && (active(config.effectStreak) || anyOwn(config.streakRewards))) out.add("streak");
        if (config.statisticsEnabled && config.rewardsEnabled && (active(config.effectMilestone) || anyOwn(config.milestones))) {
            out.add("milestone");
        }
        if (config.votePartyEnabled && active(config.effectParty)) out.add("vote party");
        if (config.reminderEnabled && active(config.effectReminder)) out.add("reminder");
        return out;
    }

    private boolean anyOwn(List<DankVotesConfig.Milestone> milestones) {
        for (DankVotesConfig.Milestone m : milestones) if (m.effect != null && active(m.effect)) return true;
        return false;
    }

    private void debug(String message) {
        if (config.debug) platform.getLogger().info("[debug] " + message);
    }
}
