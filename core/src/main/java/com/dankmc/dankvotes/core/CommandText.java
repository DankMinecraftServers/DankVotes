package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the text for player and admin commands. Platform-agnostic: returns lines with
 * legacy '&' colour codes which each platform converts and sends. Keeping this in core
 * means /vote, /votetop, /votes, /voteparty and /dankvotes status behave identically on
 * Paper and Velocity.
 */
public final class CommandText {

    private final DankVotesCore core;
    private final DankVotesConfig cfg;
    private final DankVotesConfig.Messages m;
    private final VoteStorage storage;

    public CommandText(DankVotesCore core) {
        this.core = core;
        this.cfg = core.getConfig();
        this.m = cfg.messages;
        this.storage = core.getStorage();
    }

    public String prefix() { return m.prefix; }
    public String noPermission() { return m.prefix + m.noPermission; }
    public String playerOnly() { return m.prefix + m.playerOnly; }
    public String usage(String usage) { return m.prefix + m.usage.replace("%usage%", usage); }
    public String commandDisabled() { return m.prefix + m.commandDisabled; }

    /** /vote - show the configured vote links. */
    public List<String> vote(String viewer) {
        List<String> out = new ArrayList<String>();
        out.add(m.voteHeader);
        if (cfg.voteLinks.isEmpty()) {
            out.add(m.voteNoLinks);
        } else {
            for (DankVotesConfig.VoteLink link : cfg.voteLinks) {
                out.add(m.voteLine.replace("%name%", link.name).replace("%url%", link.url));
            }
        }
        if (viewer != null && !Strings.isBlank(m.voteFooter)) {
            out.add(fill(m.voteFooter, viewer));
        }
        return out;
    }

    /** /votes [player] */
    public String votes(String viewer, String target) {
        boolean self = target == null || target.equalsIgnoreCase(viewer);
        String name = self ? viewer : target;
        if (!self && !storage.hasData(name)) {
            return m.prefix + m.playerNotFound.replace("%player%", name);
        }
        return m.prefix + fill(self ? m.votesSelf : m.votesOther, name);
    }

    /** /votetop [page] */
    public List<String> voteTop(int page, int perPage) {
        List<String> out = new ArrayList<String>();
        List<VoteStorage.TopEntry> all = storage.getTopVoters(Integer.MAX_VALUE);
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.max(1, Math.min(page, pages));
        out.add(m.voteTopHeader.replace("%page%", String.valueOf(page)).replace("%pages%", String.valueOf(pages)));
        if (all.isEmpty()) {
            out.add(m.voteTopEmpty);
            return out;
        }
        int start = (page - 1) * perPage;
        for (int i = start; i < Math.min(all.size(), start + perPage); i++) {
            VoteStorage.TopEntry e = all.get(i);
            out.add(m.voteTopLine
                .replace("%rank%", String.valueOf(i + 1))
                .replace("%player%", e.name)
                .replace("%votes%", String.valueOf(e.votes)));
        }
        if (pages > 1) {
            out.add("&7Page &d" + page + "&7/&d" + pages + " &8- &7/votetop <page>");
        }
        return out;
    }

    /** /voteparty */
    public String voteParty() {
        if (!cfg.votePartyEnabled) return m.prefix + m.votePartyDisabled;
        return m.prefix + core.getEngine().applyPlaceholders(m.votePartyStatus, null, 0, 0);
    }

    /** /dankvotes status */
    public List<String> status() {
        List<String> out = new ArrayList<String>();
        ApiPoller p = core.getPoller();
        VotifierServer v = core.getVotifierServer();
        out.add("&8&m----------&r &d&lDankVotes &7v" + core.getPlatform().getPluginVersion() + " &8&m----------");
        out.add("&7Platform: &f" + core.getPlatform().getPlatformName() + " &8| &7Uptime: &f" + duration(core.getUptimeMillis()));
        out.add("&7Polling: " + (cfg.pollingEnabled
            ? (p.isRunning() ? "&aactive" : "&cnot running &7(token missing?)")
            : "&7disabled")
            + (cfg.pollingEnabled ? " &8| &7last OK: &f" + ago(p.getLastSuccessMillis()) + " &8| &7received: &f" + p.getTotalReceived() : ""));
        if (p.getLastError() != null && p.getLastErrorMillis() > p.getLastSuccessMillis()) {
            out.add("&7Last poll error: &c" + p.getLastError());
        }
        out.add("&7Votifier: " + (cfg.votifierEnabled
            ? (v.isRunning() ? "&alistening on &f" + cfg.votifierHost + ":" + cfg.votifierPort : "&cnot running")
            : "&7disabled")
            + (cfg.votifierEnabled ? " &8| &7received: &f" + v.getTotalReceived() : ""));
        out.addAll(core.getForwarder().statusLines());
        out.add("&7NuVotifier hook: " + (cfg.nuVotifierHookEnabled ? "&aenabled" : "&7disabled")
            + " &8| &7Offline votes: " + (!cfg.offlineVotes ? "&cignored"
                : "&acounted" + (!cfg.rewardsEnabled ? "" : cfg.queueOfflineVotes ? " &7(rewards saved for next join)" : " &7(rewards run at once)")));
        out.add("&7Statistics: " + onOff(cfg.statisticsEnabled)
            + (cfg.statisticsEnabled ? " &7(streaks " + (cfg.streaksEnabled ? "on" : "off") + ")" : "")
            + " &8| &7Rewards: " + onOff(cfg.rewardsEnabled)
            + " &8| &7Vote messages: " + onOff(cfg.voteMessagesEnabled)
            + " &8| &7Reminders: " + onOff(cfg.reminderEnabled)
            + " &8| &7Vote party: " + onOff(cfg.votePartyEnabled));
        if (cfg.statisticsEnabled || cfg.rewardsEnabled) {
            String counts = cfg.statisticsEnabled
                ? "&7Total votes: &f" + storage.getTotalVotes() + " &8| &7Players: &f" + storage.getPlayerCount()
                : "";
            String queued = cfg.rewardsEnabled ? "&7Queued offline: &f" + storage.getQueuedVoteCount() : "";
            out.add(counts + (!counts.isEmpty() && !queued.isEmpty() ? " &8| " : "") + queued);
        }
        if (cfg.votePartyEnabled) {
            out.add("&7Vote party: &f" + storage.getPartyProgress() + "&7/&f" + cfg.votePartyGoal);
        }
        List<String> off = new ArrayList<String>();
        for (String[] names : CommandHandler.COMMANDS) {
            if (!cfg.commandEnabled(names[0])) off.add("/" + names[0]);
        }
        if (!off.isEmpty()) {
            out.add("&7Commands turned off: &f" + join(off, " "));
        }
        String update = core.getUpdateChecker().getAvailableUpdate();
        if (update != null) {
            out.add("&eUpdate available: &fv" + update + " &7- " + core.getUpdateChecker().getDownloadUrl());
        }
        return out;
    }

    /** /dankvotes key - show the v1 public key for vote sites. */
    public List<String> key() {
        List<String> out = new ArrayList<String>();
        String key = core.getVotifierServer().getPublicKeyBase64();
        if (key == null) {
            out.add(m.prefix + "&7Votifier v1 is not enabled (set votifier.enabled and votifier.v1-rsa to true).");
        } else {
            out.add(m.prefix + "&7Votifier v1 public key (paste into vote sites):");
            out.add("&f" + key);
        }
        return out;
    }

    /** /dankvotes help: only the commands that are switched on. */
    public String help(boolean admin) {
        List<String> player = new ArrayList<String>();
        if (cfg.commandEnabled("vote")) player.add("&7/vote &8- &7vote links");
        if (cfg.commandEnabled("votes")) player.add("&7/votes [player]");
        if (cfg.commandEnabled("votetop")) player.add("&7/votetop [page]");
        if (cfg.commandEnabled("voteparty")) player.add("&7/voteparty");
        StringBuilder sb = new StringBuilder();
        if (!player.isEmpty()) sb.append(m.prefix).append(join(player, " &8| "));
        if (admin) {
            List<String> subs = new ArrayList<String>();
            subs.add("&f/dankvotes reload");
            subs.add("&fstatus");
            subs.add("&ftest <player>");
            if (cfg.subcommandEnabled("setvotes")) subs.add("&fsetvotes|addvotes <player> <n>");
            if (cfg.subcommandEnabled("reset")) subs.add("&freset <player>");
            if (cfg.subcommandEnabled("party")) subs.add("&fparty");
            subs.add("&fkey");
            if (sb.length() > 0) sb.append("\n");
            sb.append(m.prefix).append("&7Admin: ").append(join(subs, " &8| "));
        }
        if (sb.length() == 0) {
            sb.append(m.prefix).append("&7DankVotes &fv").append(core.getPlatform().getPluginVersion());
        }
        return sb.toString();
    }

    private String fill(String template, String player) {
        return core.getEngine().applyPlayerPlaceholders(template, player);
    }

    private static String onOff(boolean on) {
        return on ? "&aon" : "&7off";
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String ago(long millis) {
        if (millis <= 0) return "never";
        return duration(System.currentTimeMillis() - millis) + " ago";
    }

    static String duration(long ms) {
        long s = ms / 1000L;
        if (s < 60) return s + "s";
        long mnt = s / 60;
        if (mnt < 60) return mnt + "m";
        long h = mnt / 60;
        if (h < 48) return h + "h " + (mnt % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }
}
