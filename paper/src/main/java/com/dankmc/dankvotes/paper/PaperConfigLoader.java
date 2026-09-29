package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.ConfigMapper;
import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesConfig.Messages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Translates the Bukkit YAML config into the platform-agnostic DankVotesConfig POJO.
 * Every key has a sensible default so a partial/older config.yml still works. List entries
 * (vote links, rewards, milestones) are parsed by the shared {@link ConfigMapper} so every
 * platform reads them identically.
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

        c.forwardingEnabled = yml.getBoolean("forwarding.enabled", c.forwardingEnabled);
        c.forwardingMode    = yml.getString("forwarding.mode", c.forwardingMode).trim().toLowerCase(java.util.Locale.ROOT);
        c.forwardingServers = ConfigMapper.forwardTargets(yml.getList("forwarding.servers"));

        // ── Behaviour ────────────────────────────────────────────────
        c.queueOfflineVotes  = yml.getBoolean("behaviour.queue-offline-votes", c.queueOfflineVotes);
        c.requireVerified    = yml.getBoolean("behaviour.require-verified", c.requireVerified);
        c.broadcastEnabled   = yml.getBoolean("behaviour.broadcast.enabled", c.broadcastEnabled);
        c.broadcastMessage   = yml.getString("behaviour.broadcast.message", c.broadcastMessage);
        c.thankYouEnabled    = yml.getBoolean("behaviour.thank-you.enabled", c.thankYouEnabled);
        c.thankYouMessage    = yml.getString("behaviour.thank-you.message", c.thankYouMessage);

        // ── Vote links ───────────────────────────────────────────────
        c.voteLinks = ConfigMapper.voteLinks(yml.getList("vote-links"));

        // ── Rewards ──────────────────────────────────────────────────
        c.rewards = ConfigMapper.rewards(yml.getList("rewards"));
        c.milestones = ConfigMapper.milestones(yml.getList("milestones"));

        // ── Streaks ──────────────────────────────────────────────────
        c.streaksEnabled          = yml.getBoolean("streaks.enabled", c.streaksEnabled);
        c.streakRewards           = ConfigMapper.milestones(yml.getList("streaks.rewards"));
        c.streakBroadcastEnabled  = yml.getBoolean("streaks.broadcast.enabled", c.streakBroadcastEnabled);
        c.streakBroadcastMessage  = yml.getString("streaks.broadcast.message", c.streakBroadcastMessage);

        // ── Vote party ───────────────────────────────────────────────
        c.votePartyEnabled         = yml.getBoolean("vote-party.enabled", c.votePartyEnabled);
        c.votePartyGoal            = yml.getInt("vote-party.goal", c.votePartyGoal);
        c.votePartyRewards         = ConfigMapper.rewards(yml.getList("vote-party.rewards"));
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

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
