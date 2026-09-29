package com.dankmc.dankvotes.core;

import com.dankmc.dankvotes.core.DankVotesConfig.Messages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Turns the plain Map/List tree a YAML parser produces into a {@link DankVotesConfig}.
 *
 * Used by every platform that parses config.yml with SnakeYAML (Velocity, BungeeCord,
 * Sponge). It mirrors the Bukkit loader key for key and value for value (a boolean must be a
 * boolean, a number a number, anything else falls back to the default), so one config.yml
 * behaves the same everywhere. Every key has a default, so a partial or older file works.
 */
public final class ConfigMapper {

    private ConfigMapper() {}

    public static DankVotesConfig fromMap(Map<?, ?> root) {
        DankVotesConfig c = new DankVotesConfig();
        if (root == null) return c;

        // ── Connection ───────────────────────────────────────────────
        Map<?, ?> polling = section(root, "polling");
        c.pollingEnabled      = bool(polling, "enabled", c.pollingEnabled);
        c.apiBaseUrl          = stripTrailingSlash(str(polling, "api-url", c.apiBaseUrl));
        c.apiToken            = str(polling, "api-token", "").trim();
        c.pollIntervalSeconds = integer(polling, "interval-seconds", c.pollIntervalSeconds);

        Map<?, ?> votifier = section(root, "votifier");
        c.votifierEnabled   = bool(votifier, "enabled", c.votifierEnabled);
        c.votifierHost      = str(votifier, "host", c.votifierHost);
        c.votifierPort      = integer(votifier, "port", c.votifierPort);
        c.votifierToken     = str(votifier, "token", "").trim();
        c.votifierV1Enabled = bool(votifier, "v1-rsa", c.votifierV1Enabled);

        c.nuVotifierHookEnabled  = bool(root, "nuvotifier-hook", c.nuVotifierHookEnabled);
        c.duplicateWindowSeconds = integer(root, "duplicate-window-seconds", c.duplicateWindowSeconds);

        Map<?, ?> forwarding = section(root, "forwarding");
        c.forwardingEnabled = bool(forwarding, "enabled", c.forwardingEnabled);
        c.forwardingMode    = str(forwarding, "mode", c.forwardingMode).trim().toLowerCase(java.util.Locale.ROOT);
        c.forwardingServers = forwardTargets(forwarding.get("servers"));

        // ── Behaviour ────────────────────────────────────────────────
        Map<?, ?> behaviour = section(root, "behaviour");
        c.queueOfflineVotes = bool(behaviour, "queue-offline-votes", c.queueOfflineVotes);
        c.requireVerified   = bool(behaviour, "require-verified", c.requireVerified);
        Map<?, ?> broadcast = section(behaviour, "broadcast");
        c.broadcastEnabled  = bool(broadcast, "enabled", c.broadcastEnabled);
        c.broadcastMessage  = str(broadcast, "message", c.broadcastMessage);
        Map<?, ?> thankYou  = section(behaviour, "thank-you");
        c.thankYouEnabled   = bool(thankYou, "enabled", c.thankYouEnabled);
        c.thankYouMessage   = str(thankYou, "message", c.thankYouMessage);

        // ── Vote links ───────────────────────────────────────────────
        c.voteLinks = voteLinks(root.get("vote-links"));

        // ── Rewards ──────────────────────────────────────────────────
        c.rewards    = rewards(root.get("rewards"));
        c.milestones = milestones(root.get("milestones"));

        // ── Streaks ──────────────────────────────────────────────────
        Map<?, ?> streaks = section(root, "streaks");
        c.streaksEnabled         = bool(streaks, "enabled", c.streaksEnabled);
        c.streakRewards          = milestones(streaks.get("rewards"));
        Map<?, ?> streakBroadcast = section(streaks, "broadcast");
        c.streakBroadcastEnabled = bool(streakBroadcast, "enabled", c.streakBroadcastEnabled);
        c.streakBroadcastMessage = str(streakBroadcast, "message", c.streakBroadcastMessage);

        // ── Vote party ───────────────────────────────────────────────
        Map<?, ?> party = section(root, "vote-party");
        c.votePartyEnabled         = bool(party, "enabled", c.votePartyEnabled);
        c.votePartyGoal            = integer(party, "goal", c.votePartyGoal);
        c.votePartyRewards         = rewards(party.get("rewards"));
        c.votePartyStartMessage    = str(party, "start-message", c.votePartyStartMessage);
        c.votePartyAnnounceEvery   = integer(party, "announce-every", c.votePartyAnnounceEvery);
        c.votePartyProgressMessage = str(party, "progress-message", c.votePartyProgressMessage);

        // ── Reminders ────────────────────────────────────────────────
        Map<?, ?> reminders = section(root, "reminders");
        c.reminderEnabled            = bool(reminders, "enabled", c.reminderEnabled);
        c.reminderIntervalMinutes    = integer(reminders, "interval-minutes", c.reminderIntervalMinutes);
        c.reminderOnJoin             = bool(reminders, "on-join", c.reminderOnJoin);
        c.reminderOnJoinDelaySeconds = integer(reminders, "on-join-delay-seconds", c.reminderOnJoinDelaySeconds);
        c.reminderMessage            = str(reminders, "message", c.reminderMessage);

        // ── Misc ─────────────────────────────────────────────────────
        Map<?, ?> update = section(root, "update-check");
        c.updateCheckEnabled = bool(update, "enabled", c.updateCheckEnabled);
        c.updateRepo         = str(update, "repo", c.updateRepo);
        c.metricsEnabled     = bool(root, "metrics", c.metricsEnabled);
        c.debug              = bool(root, "debug", c.debug);

        // ── Messages ─────────────────────────────────────────────────
        Messages m = c.messages;
        Map<?, ?> ms = section(root, "messages");
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

    /** vote-links: a list of { name, url } maps, or plain URL strings. */
    public static List<DankVotesConfig.VoteLink> voteLinks(Object raw) {
        List<DankVotesConfig.VoteLink> out = new ArrayList<DankVotesConfig.VoteLink>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                String url = String.valueOf(m.get("url"));
                String name = String.valueOf(m.get("name"));
                if (!Strings.isBlank(url) && !"null".equals(url)) {
                    out.add(new DankVotesConfig.VoteLink("null".equals(name) ? url : name, url));
                }
            } else if (o instanceof String && !Strings.isBlank((String) o)) {
                out.add(new DankVotesConfig.VoteLink((String) o, (String) o));
            }
        }
        return out;
    }

    /**
     * Reward entries: a plain string ("give %player% diamond 1") or a map
     * { command: "...", chance: 50, permission: "some.perm" }.
     */
    public static List<DankVotesConfig.RewardCommand> rewards(Object raw) {
        List<DankVotesConfig.RewardCommand> out = new ArrayList<DankVotesConfig.RewardCommand>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
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

    /** forwarding.servers: a list of { name, host, port, token } maps. */
    public static List<DankVotesConfig.ForwardTarget> forwardTargets(Object raw) {
        List<DankVotesConfig.ForwardTarget> out = new ArrayList<DankVotesConfig.ForwardTarget>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            String host = m.get("host") == null ? "" : String.valueOf(m.get("host")).trim();
            int port = toInt(m.get("port"), 8192);
            String name = m.get("name") == null ? "" : String.valueOf(m.get("name")).trim();
            if (name.isEmpty()) name = host + ":" + port;
            String token = m.get("token") == null ? "" : String.valueOf(m.get("token")).trim();
            if (!Strings.isBlank(host)) out.add(new DankVotesConfig.ForwardTarget(name, host, port, token));
        }
        return out;
    }

    /** Milestones and streak rewards: { type: EVERY|AT, votes|days: n, commands: [...] }. */
    public static List<DankVotesConfig.Milestone> milestones(Object raw) {
        List<DankVotesConfig.Milestone> out = new ArrayList<DankVotesConfig.Milestone>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> map = (Map<?, ?>) o;
            DankVotesConfig.Milestone m = new DankVotesConfig.Milestone();
            String type = String.valueOf(map.get("type") == null ? "every" : map.get("type")).toUpperCase(java.util.Locale.ROOT);
            try { m.type = DankVotesConfig.Milestone.Type.valueOf(type); }
            catch (Exception e) { m.type = DankVotesConfig.Milestone.Type.EVERY; }
            Object votes = map.get("votes") != null ? map.get("votes") : map.get("days");
            m.votes = toInt(votes, 5);
            m.commands = rewards(map.get("commands"));
            out.add(m);
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Map<?, ?> section(Map<?, ?> parent, String key) {
        Object o = parent == null ? null : parent.get(key);
        return o instanceof Map ? (Map<?, ?>) o : Collections.emptyMap();
    }

    private static String str(Map<?, ?> m, String key, String def) {
        Object o = m.get(key);
        return o == null ? def : String.valueOf(o);
    }

    private static boolean bool(Map<?, ?> m, String key, boolean def) {
        Object o = m.get(key);
        return o instanceof Boolean ? (Boolean) o : def;
    }

    private static int integer(Map<?, ?> m, String key, int def) {
        Object o = m.get(key);
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return def; }
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o).trim()); } catch (Exception e) { return def; }
    }

    static String stripTrailingSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
