package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesConfig.Messages;
import com.dankmc.dankvotes.core.Strings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates the Bukkit YAML config into the platform-agnostic DankVotesConfig POJO.
 * Every key has a sensible default so a partial/older config.yml still works.
 */
public final class PaperConfigLoader {

    private PaperConfigLoader() {}

    public static DankVotesConfig load(FileConfiguration yml) {
        DankVotesConfig c = new DankVotesConfig();

        // ── Connection ───────────────────────────────────────────────
        c.pollingEnabled      = yml.getBoolean("polling.enabled", c.pollingEnabled);
        c.apiBaseUrl          = stripTrailingSlash(yml.getString("polling.api-url", c.apiBaseUrl));
        c.apiToken            = yml.getString("polling.api-token", "").trim();
        c.pollIntervalSeconds = yml.getInt("polling.interval-seconds", c.pollIntervalSeconds);

        c.votifierEnabled     = yml.getBoolean("votifier.enabled", c.votifierEnabled);
        c.votifierHost        = yml.getString("votifier.host", c.votifierHost);
        c.votifierPort        = yml.getInt("votifier.port", c.votifierPort);
        c.votifierToken       = yml.getString("votifier.token", "").trim();
        c.votifierV1Enabled   = yml.getBoolean("votifier.v1-rsa", c.votifierV1Enabled);

        c.nuVotifierHookEnabled  = yml.getBoolean("nuvotifier-hook", c.nuVotifierHookEnabled);
        c.duplicateWindowSeconds = yml.getInt("duplicate-window-seconds", c.duplicateWindowSeconds);

        // ── Behaviour ────────────────────────────────────────────────
        c.queueOfflineVotes  = yml.getBoolean("behaviour.queue-offline-votes", c.queueOfflineVotes);
        c.requireVerified    = yml.getBoolean("behaviour.require-verified", c.requireVerified);
        c.broadcastEnabled   = yml.getBoolean("behaviour.broadcast.enabled", c.broadcastEnabled);
        c.broadcastMessage   = yml.getString("behaviour.broadcast.message", c.broadcastMessage);
        c.thankYouEnabled    = yml.getBoolean("behaviour.thank-you.enabled", c.thankYouEnabled);
        c.thankYouMessage    = yml.getString("behaviour.thank-you.message", c.thankYouMessage);

        // ── Vote links ───────────────────────────────────────────────
        c.voteLinks = new ArrayList<DankVotesConfig.VoteLink>();
        List<?> links = yml.getList("vote-links");
        if (links != null) {
            for (Object o : links) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    String name = String.valueOf(m.get("name"));
                    String url = String.valueOf(m.get("url"));
                    if (!Strings.isBlank(url) && !"null".equals(url)) {
                        c.voteLinks.add(new DankVotesConfig.VoteLink("null".equals(name) ? url : name, url));
                    }
                } else if (o instanceof String && !Strings.isBlank((String) o)) {
                    c.voteLinks.add(new DankVotesConfig.VoteLink((String) o, (String) o));
                }
            }
        }

        // ── Rewards ──────────────────────────────────────────────────
        c.rewards = parseRewardList(yml.getList("rewards"));
        c.milestones = parseMilestones(yml.getList("milestones"));

        // ── Streaks ──────────────────────────────────────────────────
        c.streaksEnabled          = yml.getBoolean("streaks.enabled", c.streaksEnabled);
        c.streakRewards           = parseMilestones(yml.getList("streaks.rewards"));
        c.streakBroadcastEnabled  = yml.getBoolean("streaks.broadcast.enabled", c.streakBroadcastEnabled);
        c.streakBroadcastMessage  = yml.getString("streaks.broadcast.message", c.streakBroadcastMessage);

        // ── Vote party ───────────────────────────────────────────────
        c.votePartyEnabled         = yml.getBoolean("vote-party.enabled", c.votePartyEnabled);
        c.votePartyGoal            = yml.getInt("vote-party.goal", c.votePartyGoal);
        c.votePartyRewards         = parseRewardList(yml.getList("vote-party.rewards"));
        c.votePartyStartMessage    = yml.getString("vote-party.start-message", c.votePartyStartMessage);
        c.votePartyAnnounceEvery   = yml.getInt("vote-party.announce-every", c.votePartyAnnounceEvery);
        c.votePartyProgressMessage = yml.getString("vote-party.progress-message", c.votePartyProgressMessage);

        // ── Reminders ────────────────────────────────────────────────
        c.reminderEnabled            = yml.getBoolean("reminders.enabled", c.reminderEnabled);
        c.reminderIntervalMinutes    = yml.getInt("reminders.interval-minutes", c.reminderIntervalMinutes);
        c.reminderOnJoin             = yml.getBoolean("reminders.on-join", c.reminderOnJoin);
        c.reminderOnJoinDelaySeconds = yml.getInt("reminders.on-join-delay-seconds", c.reminderOnJoinDelaySeconds);
        c.reminderMessage            = yml.getString("reminders.message", c.reminderMessage);

        // ── Misc ─────────────────────────────────────────────────────
        c.updateCheckEnabled = yml.getBoolean("update-check.enabled", c.updateCheckEnabled);
        c.updateRepo         = yml.getString("update-check.repo", c.updateRepo);
        c.metricsEnabled     = yml.getBoolean("metrics", c.metricsEnabled);
        c.debug              = yml.getBoolean("debug", c.debug);

        // ── Messages ─────────────────────────────────────────────────
        Messages m = c.messages;
        ConfigurationSection ms = yml.getConfigurationSection("messages");
        if (ms != null) {
            m.prefix            = ms.getString("prefix", m.prefix);
            m.noPermission      = ms.getString("no-permission", m.noPermission);
            m.playerOnly        = ms.getString("player-only", m.playerOnly);
            m.playerNotFound    = ms.getString("player-not-found", m.playerNotFound);
            m.voteHeader        = ms.getString("vote-header", m.voteHeader);
            m.voteLine          = ms.getString("vote-line", m.voteLine);
            m.voteFooter        = ms.getString("vote-footer", m.voteFooter);
            m.voteNoLinks       = ms.getString("vote-no-links", m.voteNoLinks);
            m.votesSelf         = ms.getString("votes-self", m.votesSelf);
            m.votesOther        = ms.getString("votes-other", m.votesOther);
            m.voteTopHeader     = ms.getString("votetop-header", m.voteTopHeader);
            m.voteTopLine       = ms.getString("votetop-line", m.voteTopLine);
            m.voteTopEmpty      = ms.getString("votetop-empty", m.voteTopEmpty);
            m.votePartyStatus   = ms.getString("voteparty-status", m.votePartyStatus);
            m.votePartyDisabled = ms.getString("voteparty-disabled", m.votePartyDisabled);
            m.reloaded          = ms.getString("reloaded", m.reloaded);
            m.testVote          = ms.getString("test-vote", m.testVote);
            m.votesSet          = ms.getString("votes-set", m.votesSet);
            m.votesReset        = ms.getString("votes-reset", m.votesReset);
            m.partyForced       = ms.getString("party-forced", m.partyForced);
            m.usage             = ms.getString("usage", m.usage);
        }

        return c;
    }

    /**
     * Reward entries can be a plain string ("give %player% diamond 1") or a map
     * { command: "...", chance: 50, permission: "some.perm" }.
     */
    static List<DankVotesConfig.RewardCommand> parseRewardList(List<?> raw) {
        List<DankVotesConfig.RewardCommand> out = new ArrayList<DankVotesConfig.RewardCommand>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (o instanceof String) {
                if (!Strings.isBlank((String) o)) out.add(new DankVotesConfig.RewardCommand((String) o, 100.0));
            } else if (o instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) o;
                String cmd = String.valueOf(map.get("command"));
                double chance = toDouble(map.get("chance"), 100.0);
                String perm = map.get("permission") == null ? "" : String.valueOf(map.get("permission"));
                if (!Strings.isBlank(cmd) && !"null".equals(cmd)) {
                    out.add(new DankVotesConfig.RewardCommand(cmd, chance, perm));
                }
            }
        }
        return out;
    }

    static List<DankVotesConfig.Milestone> parseMilestones(List<?> raw) {
        List<DankVotesConfig.Milestone> out = new ArrayList<DankVotesConfig.Milestone>();
        if (raw == null) return out;
        for (Object o : raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> map = (Map<?, ?>) o;
            DankVotesConfig.Milestone m = new DankVotesConfig.Milestone();
            String type = String.valueOf(map.get("type") == null ? "every" : map.get("type")).toUpperCase();
            try { m.type = DankVotesConfig.Milestone.Type.valueOf(type); }
            catch (Exception e) { m.type = DankVotesConfig.Milestone.Type.EVERY; }
            Object votes = map.get("votes") != null ? map.get("votes") : map.get("days");
            m.votes = toInt(votes, 5);
            m.commands = parseRewardList(map.get("commands") instanceof List ? (List<?>) map.get("commands") : null);
            out.add(m);
        }
        return out;
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
