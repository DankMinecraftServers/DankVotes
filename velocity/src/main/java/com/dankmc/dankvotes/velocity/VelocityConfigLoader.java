package com.dankmc.dankvotes.velocity;

import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesConfig.Messages;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads config.yml for Velocity using SnakeYAML (bundled with the proxy).
 * Mirrors PaperConfigLoader key-for-key so one config.yml works on both platforms.
 */
public final class VelocityConfigLoader {

    private VelocityConfigLoader() {}

    @SuppressWarnings("unchecked")
    public static DankVotesConfig load(Path configFile) {
        DankVotesConfig c = new DankVotesConfig();

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(configFile)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            System.err.println("[DankVotes] Could not read config.yml, using defaults: " + e.getMessage());
            return c;
        }
        if (root == null) return c;

        Map<String, Object> polling = section(root, "polling");
        c.pollingEnabled      = bool(polling, "enabled", c.pollingEnabled);
        c.apiBaseUrl          = stripSlash(str(polling, "api-url", c.apiBaseUrl));
        c.apiToken            = str(polling, "api-token", "").trim();
        c.pollIntervalSeconds = intVal(polling, "interval-seconds", c.pollIntervalSeconds);

        Map<String, Object> votifier = section(root, "votifier");
        c.votifierEnabled   = bool(votifier, "enabled", c.votifierEnabled);
        c.votifierHost      = str(votifier, "host", c.votifierHost);
        c.votifierPort      = intVal(votifier, "port", c.votifierPort);
        c.votifierToken     = str(votifier, "token", "").trim();
        c.votifierV1Enabled = bool(votifier, "v1-rsa", c.votifierV1Enabled);

        c.nuVotifierHookEnabled  = bool(root, "nuvotifier-hook", c.nuVotifierHookEnabled);
        c.duplicateWindowSeconds = intVal(root, "duplicate-window-seconds", c.duplicateWindowSeconds);

        Map<String, Object> behaviour = section(root, "behaviour");
        c.queueOfflineVotes = bool(behaviour, "queue-offline-votes", c.queueOfflineVotes);
        c.requireVerified   = bool(behaviour, "require-verified", c.requireVerified);
        Map<String, Object> broadcast = section(behaviour, "broadcast");
        c.broadcastEnabled = bool(broadcast, "enabled", c.broadcastEnabled);
        c.broadcastMessage = str(broadcast, "message", c.broadcastMessage);
        Map<String, Object> thankYou = section(behaviour, "thank-you");
        c.thankYouEnabled = bool(thankYou, "enabled", c.thankYouEnabled);
        c.thankYouMessage = str(thankYou, "message", c.thankYouMessage);

        c.voteLinks = new ArrayList<>();
        if (root.get("vote-links") instanceof List<?> links) {
            for (Object o : links) {
                if (o instanceof Map<?, ?> m) {
                    String url = String.valueOf(m.get("url"));
                    String name = m.get("name") == null ? url : String.valueOf(m.get("name"));
                    if (!url.isBlank() && !"null".equals(url)) c.voteLinks.add(new DankVotesConfig.VoteLink(name, url));
                } else if (o instanceof String s && !s.isBlank()) {
                    c.voteLinks.add(new DankVotesConfig.VoteLink(s, s));
                }
            }
        }

        c.rewards = parseRewards(root.get("rewards"));
        c.milestones = parseMilestones(root.get("milestones"));

        Map<String, Object> streaks = section(root, "streaks");
        c.streaksEnabled = bool(streaks, "enabled", c.streaksEnabled);
        c.streakRewards  = parseMilestones(streaks.get("rewards"));
        Map<String, Object> streakBroadcast = section(streaks, "broadcast");
        c.streakBroadcastEnabled = bool(streakBroadcast, "enabled", c.streakBroadcastEnabled);
        c.streakBroadcastMessage = str(streakBroadcast, "message", c.streakBroadcastMessage);

        Map<String, Object> party = section(root, "vote-party");
        c.votePartyEnabled         = bool(party, "enabled", c.votePartyEnabled);
        c.votePartyGoal            = intVal(party, "goal", c.votePartyGoal);
        c.votePartyRewards         = parseRewards(party.get("rewards"));
        c.votePartyStartMessage    = str(party, "start-message", c.votePartyStartMessage);
        c.votePartyAnnounceEvery   = intVal(party, "announce-every", c.votePartyAnnounceEvery);
        c.votePartyProgressMessage = str(party, "progress-message", c.votePartyProgressMessage);

        Map<String, Object> reminders = section(root, "reminders");
        c.reminderEnabled            = bool(reminders, "enabled", c.reminderEnabled);
        c.reminderIntervalMinutes    = intVal(reminders, "interval-minutes", c.reminderIntervalMinutes);
        c.reminderOnJoin             = bool(reminders, "on-join", c.reminderOnJoin);
        c.reminderOnJoinDelaySeconds = intVal(reminders, "on-join-delay-seconds", c.reminderOnJoinDelaySeconds);
        c.reminderMessage            = str(reminders, "message", c.reminderMessage);

        Map<String, Object> update = section(root, "update-check");
        c.updateCheckEnabled = bool(update, "enabled", c.updateCheckEnabled);
        c.updateRepo         = str(update, "repo", c.updateRepo);
        c.metricsEnabled     = bool(root, "metrics", c.metricsEnabled);
        c.debug              = bool(root, "debug", c.debug);

        Messages m = c.messages;
        Map<String, Object> ms = section(root, "messages");
        m.prefix            = str(ms, "prefix", m.prefix);
        m.noPermission      = str(ms, "no-permission", m.noPermission);
        m.playerOnly        = str(ms, "player-only", m.playerOnly);
        m.playerNotFound    = str(ms, "player-not-found", m.playerNotFound);
        m.voteHeader        = str(ms, "vote-header", m.voteHeader);
        m.voteLine          = str(ms, "vote-line", m.voteLine);
        m.voteFooter        = str(ms, "vote-footer", m.voteFooter);
        m.voteNoLinks       = str(ms, "vote-no-links", m.voteNoLinks);
        m.votesSelf         = str(ms, "votes-self", m.votesSelf);
        m.votesOther        = str(ms, "votes-other", m.votesOther);
        m.voteTopHeader     = str(ms, "votetop-header", m.voteTopHeader);
        m.voteTopLine       = str(ms, "votetop-line", m.voteTopLine);
        m.voteTopEmpty      = str(ms, "votetop-empty", m.voteTopEmpty);
        m.votePartyStatus   = str(ms, "voteparty-status", m.votePartyStatus);
        m.votePartyDisabled = str(ms, "voteparty-disabled", m.votePartyDisabled);
        m.reloaded          = str(ms, "reloaded", m.reloaded);
        m.testVote          = str(ms, "test-vote", m.testVote);
        m.votesSet          = str(ms, "votes-set", m.votesSet);
        m.votesReset        = str(ms, "votes-reset", m.votesReset);
        m.partyForced       = str(ms, "party-forced", m.partyForced);
        m.usage             = str(ms, "usage", m.usage);

        return c;
    }

    private static List<DankVotesConfig.RewardCommand> parseRewards(Object raw) {
        List<DankVotesConfig.RewardCommand> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (o instanceof String s) {
                if (!s.isBlank()) out.add(new DankVotesConfig.RewardCommand(s, 100.0));
            } else if (o instanceof Map<?, ?> map) {
                String cmd = String.valueOf(map.get("command"));
                double chance = map.get("chance") instanceof Number n ? n.doubleValue() : 100.0;
                String perm = map.get("permission") == null ? "" : String.valueOf(map.get("permission"));
                if (!cmd.isBlank() && !"null".equals(cmd)) out.add(new DankVotesConfig.RewardCommand(cmd, chance, perm));
            }
        }
        return out;
    }

    private static List<DankVotesConfig.Milestone> parseMilestones(Object raw) {
        List<DankVotesConfig.Milestone> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) continue;
            DankVotesConfig.Milestone m = new DankVotesConfig.Milestone();
            String type = String.valueOf(map.get("type") == null ? "every" : map.get("type")).toUpperCase();
            try { m.type = DankVotesConfig.Milestone.Type.valueOf(type); }
            catch (Exception e) { m.type = DankVotesConfig.Milestone.Type.EVERY; }
            Object votes = map.get("votes") != null ? map.get("votes") : map.get("days");
            m.votes = votes instanceof Number n ? n.intValue() : 5;
            m.commands = parseRewards(map.get("commands"));
            out.add(m);
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object o = parent == null ? null : parent.get(key);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
    private static String str(Map<String, Object> m, String k, String def) {
        Object o = m.get(k); return o == null ? def : String.valueOf(o);
    }
    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m.get(k); return o instanceof Boolean b ? b : def;
    }
    private static int intVal(Map<String, Object> m, String k, int def) {
        Object o = m.get(k); return o instanceof Number n ? n.intValue() : def;
    }
    private static String stripSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
