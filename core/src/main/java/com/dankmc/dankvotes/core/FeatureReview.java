package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks over the features.* switches in config.yml once per (re)load: keeps parts that are
 * off from showing up in the bundled messages, and explains settings that can't take effect.
 */
final class FeatureReview {

    /** Placeholders that show statistics; they have nothing to show while statistics are off. */
    static final String[] STATISTICS_PLACEHOLDERS = {"%votes%", "%streak%", "%best_streak%", "%total_votes%"};
    /** Placeholders that show vote-party progress; 0 while the vote party is off. */
    static final String[] PARTY_PLACEHOLDERS = {"%party_progress%", "%party_goal%", "%party_remaining%"};

    /** Used instead of the bundled thank-you message (which shows totals) while statistics are off. */
    static final String THANK_YOU_WITHOUT_STATISTICS = "&aThanks for voting, %player%!";
    /** Used instead of the bundled /vote footer (which shows totals) while statistics are off. */
    static final String VOTE_FOOTER_WITHOUT_STATISTICS = "&7Vote daily to earn rewards!";

    private FeatureReview() {}

    /**
     * While statistics are off, swap the bundled messages that show a player's totals for
     * versions that don't. Only unchanged messages are swapped: an edited one is the owner's,
     * and {@link #warnings} points it out instead.
     */
    static void applyDefaults(DankVotesConfig c) {
        if (c.statisticsEnabled) return;
        DankVotesConfig bundled = new DankVotesConfig();
        if (bundled.thankYouMessage.equals(c.thankYouMessage)) c.thankYouMessage = THANK_YOU_WITHOUT_STATISTICS;
        if (bundled.messages.voteFooter.equals(c.messages.voteFooter)) c.messages.voteFooter = VOTE_FOOTER_WITHOUT_STATISTICS;
    }

    /** Settings that can't do what they say with the current switches, one log line each. */
    static List<String> warnings(DankVotesConfig c) {
        List<String> out = new ArrayList<String>();
        if (!c.votePartyEnabled) {
            List<String> where = usedIn(c, PARTY_PLACEHOLDERS);
            if (!where.isEmpty()) {
                out.add("vote-party.enabled is false, so %party_progress%, %party_goal% and %party_remaining% show 0 in "
                    + join(where) + ".");
            }
        }
        if (c.statisticsEnabled) return out;

        if (c.rewardsEnabled && (!c.milestones.isEmpty() || (c.streaksEnabled && !c.streakRewards.isEmpty()))) {
            out.add("features.statistics is off, so milestones and streak rewards won't run (they count each player's votes).");
        }
        List<String> where = usedIn(c, STATISTICS_PLACEHOLDERS);
        if (!where.isEmpty()) {
            out.add("features.statistics is off, so %votes%, %streak%, %best_streak% and %total_votes% show 0 in "
                + join(where) + ". Remove them there or turn statistics back on.");
        }
        return out;
    }

    /** One line naming the parts that are off, or null when everything is on. */
    static String summary(DankVotesConfig c) {
        List<String> off = new ArrayList<String>();
        if (!c.statisticsEnabled) off.add("statistics");
        if (!c.rewardsEnabled) off.add("rewards");
        if (!c.voteMessagesEnabled) off.add("vote messages");
        if (!c.reminderEnabled) off.add("reminders");
        if (off.isEmpty()) return null;
        if (off.size() == 4 && !c.votePartyEnabled) {
            return "Statistics, rewards, vote messages, reminders and vote parties are all off: DankVotes only receives votes"
                + (c.forwardingEnabled ? " and forwards them." : ".");
        }
        return "Turned off in config.yml: " + join(off) + ".";
    }

    /** The messages and reward lists that are in use and contain any of these placeholders. */
    private static List<String> usedIn(DankVotesConfig c, String[] placeholders) {
        List<String> where = new ArrayList<String>();
        if (c.voteMessagesEnabled && c.broadcastEnabled && contains(c.broadcastMessage, placeholders)) where.add("behaviour.broadcast.message");
        if (c.voteMessagesEnabled && c.thankYouEnabled && contains(c.thankYouMessage, placeholders)) where.add("behaviour.thank-you.message");
        if (c.voteMessagesEnabled && c.streaksActive() && c.streakBroadcastEnabled && contains(c.streakBroadcastMessage, placeholders)) {
            where.add("streaks.broadcast.message");
        }
        if (c.reminderEnabled && contains(c.reminderMessage, placeholders)) where.add("reminders.message");
        if (c.votePartyEnabled && (contains(c.votePartyStartMessage, placeholders) || contains(c.votePartyProgressMessage, placeholders))) {
            where.add("the vote-party messages");
        }
        if (c.commandEnabled("vote") && contains(c.messages.voteFooter, placeholders)) where.add("messages.vote-footer");
        if (c.rewardsEnabled && contains(c.rewards, placeholders)) where.add("rewards");
        if (c.votePartyEnabled && contains(c.votePartyRewards, placeholders)) where.add("vote-party.rewards");
        return where;
    }

    static boolean showsStatistics(String text) {
        return contains(text, STATISTICS_PLACEHOLDERS);
    }

    private static boolean contains(String text, String[] placeholders) {
        if (text == null) return false;
        for (String p : placeholders) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    private static boolean contains(List<DankVotesConfig.RewardCommand> commands, String[] placeholders) {
        for (DankVotesConfig.RewardCommand rc : commands) {
            if (rc != null && contains(rc.command, placeholders)) return true;
        }
        return false;
    }

    /** "a", "a and b", "a, b and c". */
    static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " and " : ", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
