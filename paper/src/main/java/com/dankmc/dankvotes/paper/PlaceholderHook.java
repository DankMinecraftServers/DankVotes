package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.DankVotesCore;
import com.dankmc.dankvotes.core.VoteStorage;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;

/**
 * PlaceholderAPI expansion. Only loaded when PlaceholderAPI is installed.
 *
 * Placeholders:
 *   %dankvotes_votes%            player's total votes
 *   %dankvotes_streak%           current day-streak
 *   %dankvotes_best_streak%      best day-streak
 *   %dankvotes_rank%             leaderboard position (0 if unranked)
 *   %dankvotes_voted_today%      true / false
 *   %dankvotes_total_votes%      server-wide total
 *   %dankvotes_party_progress%   votes towards the next party
 *   %dankvotes_party_goal%       party goal
 *   %dankvotes_party_remaining%  votes remaining
 *   %dankvotes_top_name_<n>%     name of the n-th top voter (1-based)
 *   %dankvotes_top_votes_<n>%    votes of the n-th top voter
 */
public class PlaceholderHook extends PlaceholderExpansion {

    private final DankVotesPaper plugin;

    public PlaceholderHook(DankVotesPaper plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "dankvotes"; }
    @Override public String getAuthor() { return "DankMinecraftServers"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        DankVotesCore core = plugin.getCore();
        if (core == null) return "";
        VoteStorage st = core.getStorage();
        String p = params.toLowerCase();
        String name = player == null ? null : player.getName();

        if (p.equals("total_votes")) return String.valueOf(st.getTotalVotes());
        if (p.equals("party_progress")) return String.valueOf(st.getPartyProgress());
        if (p.equals("party_goal")) return String.valueOf(core.getConfig().votePartyGoal);
        if (p.equals("party_remaining")) return String.valueOf(Math.max(0, core.getConfig().votePartyGoal - st.getPartyProgress()));

        if (p.startsWith("top_name_") || p.startsWith("top_votes_")) {
            boolean wantName = p.startsWith("top_name_");
            int n;
            try { n = Integer.parseInt(p.substring(p.lastIndexOf('_') + 1)); } catch (Exception e) { return ""; }
            List<VoteStorage.TopEntry> top = st.getTopVoters(Math.max(1, n));
            if (n < 1 || n > top.size()) return wantName ? "---" : "0";
            VoteStorage.TopEntry e = top.get(n - 1);
            return wantName ? e.name : String.valueOf(e.votes);
        }

        if (name == null) return "";
        if (p.equals("votes")) return String.valueOf(st.getVoteCount(name));
        if (p.equals("streak")) return String.valueOf(st.getStreak(name));
        if (p.equals("best_streak")) return String.valueOf(st.getBestStreak(name));
        if (p.equals("rank")) return String.valueOf(st.getRank(name));
        if (p.equals("voted_today")) return String.valueOf(st.hasVotedToday(name));
        return null; // unknown placeholder -> PAPI shows it unparsed
    }
}
