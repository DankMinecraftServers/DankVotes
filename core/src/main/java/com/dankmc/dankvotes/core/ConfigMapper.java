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

        // ── Features and commands ────────────────────────────────────
        Map<?, ?> features = section(root, "features");
        c.statisticsEnabled   = bool(features, "statistics", c.statisticsEnabled);
        c.rewardsEnabled      = bool(features, "rewards", c.rewardsEnabled);
        c.voteMessagesEnabled = bool(features, "vote-messages", c.voteMessagesEnabled);

        Map<?, ?> commands = section(root, "commands");
        c.commandVote      = bool(commands, "vote", c.commandVote);
        c.commandVotes     = bool(commands, "votes", c.commandVotes);
        c.commandVoteTop   = bool(commands, "votetop", c.commandVoteTop);
        c.commandVoteParty = bool(commands, "voteparty", c.commandVoteParty);
        c.commandSetVotes  = bool(commands, "setvotes", c.commandSetVotes);
        c.commandReset     = bool(commands, "reset", c.commandReset);
        c.commandParty     = bool(commands, "party", c.commandParty);

        // ── Behaviour ────────────────────────────────────────────────
        Map<?, ?> behaviour = section(root, "behaviour");
        c.offlineVotes      = bool(behaviour, "offline-votes", c.offlineVotes);
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

        // ── Effects (before milestones, which can change them) ───────
        effects(c, root.get("effects"));

        // ── Rewards ──────────────────────────────────────────────────
        c.rewards    = rewards(root.get("rewards"));
        c.milestones = milestones(root.get("milestones"), c.effectMilestone, "milestones", c.effectProblems);

        // ── Streaks ──────────────────────────────────────────────────
        Map<?, ?> streaks = section(root, "streaks");
        c.streaksEnabled         = bool(streaks, "enabled", c.streaksEnabled);
        c.streakRewards          = milestones(streaks.get("rewards"), c.effectStreak, "streaks.rewards", c.effectProblems);
        Map<?, ?> streakBroadcast = section(streaks, "broadcast");
        c.streakBroadcastEnabled = bool(streakBroadcast, "enabled", c.streakBroadcastEnabled);
        c.streakBroadcastMessage = str(streakBroadcast, "message", c.streakBroadcastMessage);
        applyEffectKinds(c);   // now that milestones and streak rewards have their own effects

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
        m.commandDisabled   = str(ms, "command-disabled", m.commandDisabled);
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
        m.testVoteRefused   = str(ms, "test-vote-refused", m.testVoteRefused);
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
        return milestones(raw, DankVotesConfig.Effect.milestone(), "milestones", new ArrayList<String>());
    }

    /**
     * Milestones and streak rewards: { type: EVERY|AT, votes|days: n, commands: [...], effect: {...} }.
     * An entry's effect is {@code base} (effects.milestone or effects.streak) with the keys it sets.
     */
    public static List<DankVotesConfig.Milestone> milestones(Object raw, DankVotesConfig.Effect base, String path, List<String> problems) {
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
            if (map.get("effect") != null) {
                // An entry's own effect plays even while the default one is switched off.
                DankVotesConfig.Effect on = base.copy();
                on.enabled = true;
                m.effect = effect(map.get("effect"), on, path + " (" + m.type + " " + m.votes + ") effect", problems);
            }
            out.add(m);
        }
        return out;
    }

    // ── effects ──────────────────────────────────────────────────────

    private static final List<String> EFFECT_TRIGGERS = java.util.Arrays.asList(
        "enabled", "particles", "sounds", "fireworks", "titles", "vote", "streak", "milestone", "vote-party", "party", "reminder");
    private static final List<String> EFFECT_KEYS = java.util.Arrays.asList(
        "enabled", "animation", "particle", "colors", "colours", "sound", "volume", "pitch",
        "firework", "firework-type", "title", "subtitle", "nearby");

    /**
     * The effects section (a map, or just true/false). Effects are on unless config.yml says
     * otherwise: a config.yml without the section (from before 1.3.0) gets the built-in effects,
     * switched on. enabled: false switches every effect off; particles, sounds, fireworks or
     * titles: false leaves just that kind out (see {@link #applyEffectKinds}).
     */
    public static void effects(DankVotesConfig c, Object raw) {
        List<String> problems = c.effectProblems;
        c.effectsConfigured = raw != null;
        if (raw instanceof Boolean) {
            c.effectsEnabled = (Boolean) raw;
            return;
        }
        Boolean word = onOff(raw);
        if (word != null) {
            c.effectsEnabled = word;
            return;
        }
        if (raw != null && !(raw instanceof Map)) problems.add("effects: expected settings, true/false or none - using the built-in effects.");
        Map<?, ?> e = raw instanceof Map ? (Map<?, ?>) raw : Collections.emptyMap();
        c.effectsEnabled  = onOffSetting(e, "enabled", true, "effects.enabled", problems);
        c.effectParticles = onOffSetting(e, "particles", true, "effects.particles", problems);
        c.effectSounds    = onOffSetting(e, "sounds", true, "effects.sounds", problems);
        c.effectFireworks = onOffSetting(e, "fireworks", true, "effects.fireworks", problems);
        c.effectTitles    = onOffSetting(e, "titles", true, "effects.titles", problems);
        c.effectVote      = effect(e.get("vote"), DankVotesConfig.Effect.vote(), "effects.vote", problems);
        c.effectStreak    = effect(e.get("streak"), DankVotesConfig.Effect.streak(), "effects.streak", problems);
        c.effectMilestone = effect(e.get("milestone"), DankVotesConfig.Effect.milestone(), "effects.milestone", problems);
        c.effectParty     = effect(e.get("vote-party") != null ? e.get("vote-party") : e.get("party"),
                                   DankVotesConfig.Effect.party(), "effects.vote-party", problems);
        c.effectReminder  = effect(e.get("reminder"), DankVotesConfig.Effect.reminder(), "effects.reminder", problems);
        for (Object key : e.keySet()) {
            if (!EFFECT_TRIGGERS.contains(String.valueOf(key))) {
                problems.add("effects: unknown setting '" + key + "' (the switches are enabled, particles, sounds, fireworks and titles;"
                    + " the effects are vote, streak, milestone, vote-party and reminder).");
            }
        }
    }

    /**
     * effects.particles, sounds, fireworks and titles: false leaves that kind out of every effect,
     * the milestones' and streak rewards' own included, so call it once the whole file is read.
     * Applying it twice changes nothing.
     */
    public static void applyEffectKinds(DankVotesConfig c) {
        List<DankVotesConfig.Effect> all = new ArrayList<DankVotesConfig.Effect>(java.util.Arrays.asList(
            c.effectVote, c.effectStreak, c.effectMilestone, c.effectParty, c.effectReminder));
        for (DankVotesConfig.Milestone m : c.milestones) all.add(m.effect);
        for (DankVotesConfig.Milestone m : c.streakRewards) all.add(m.effect);
        for (DankVotesConfig.Effect e : all) {
            if (e == null) continue;
            if (!c.effectParticles) e.animation = "none";
            if (!c.effectSounds) e.sound = "";
            if (!c.effectFireworks) e.firework = false;
            if (!c.effectTitles) {
                e.title = "";
                e.subtitle = "";
            }
        }
    }

    /**
     * One effect: settings over {@code base}, true (= base), or false / none (= off). Values
     * that can't be used are reported in {@code problems} and the base value is kept.
     */
    public static DankVotesConfig.Effect effect(Object raw, DankVotesConfig.Effect base, String path, List<String> problems) {
        if (raw == null) return base.copy();
        if (raw instanceof Boolean) return (Boolean) raw ? base.copy() : base.off();
        Boolean word = onOff(raw);
        if (word != null) return word ? base.copy() : base.off();
        if (!(raw instanceof Map)) {
            problems.add(path + ": expected settings, true/false or none - using the default.");
            return base.copy();
        }
        Map<?, ?> m = (Map<?, ?>) raw;
        DankVotesConfig.Effect e = base.copy();
        e.enabled = onOffSetting(m, "enabled", e.enabled, path + ".enabled", problems);
        if (m.get("animation") != null) {
            String a = EffectShapes.normalise(String.valueOf(m.get("animation")));
            if (EffectShapes.exists(a)) e.animation = a;
            else problems.add(path + ".animation: unknown animation '" + m.get("animation") + "' (use "
                + join(EffectShapes.ANIMATIONS) + ").");
        }
        if (m.get("particle") != null) e.particle = String.valueOf(m.get("particle")).trim();
        Object colours = m.get("colors") != null ? m.get("colors") : m.get("colours");
        if (colours != null) e.colors = colours(colours, e.colors, path, problems);
        // An empty value (sound:) keeps the default, as Bukkit drops such keys; "" means none.
        if (m.get("sound") != null) e.sound = String.valueOf(m.get("sound")).trim();
        if (m.get("volume") != null) e.volume = number(m.get("volume"), e.volume, 0f, 10f, path + ".volume", problems);
        if (m.get("pitch") != null) e.pitch = number(m.get("pitch"), e.pitch, 0.5f, 2f, path + ".pitch", problems);
        e.firework = onOffSetting(m, "firework", e.firework, path + ".firework", problems);
        if (m.get("firework-type") != null) {
            String type = String.valueOf(m.get("firework-type")).trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
            if (DankVotesConfig.Effect.FIREWORK_TYPES.contains(type)) e.fireworkType = type;
            else problems.add(path + ".firework-type: unknown type '" + m.get("firework-type") + "' (use "
                + join(DankVotesConfig.Effect.FIREWORK_TYPES) + ").");
        }
        if (m.get("title") != null) e.title = String.valueOf(m.get("title"));
        if (m.get("subtitle") != null) e.subtitle = String.valueOf(m.get("subtitle"));
        e.nearby = onOffSetting(m, "nearby", e.nearby, path + ".nearby", problems);
        for (Object key : m.keySet()) {
            String k = String.valueOf(key);
            if (!EFFECT_KEYS.contains(k)) problems.add(path + ": unknown setting '" + k + "'" + keyHint(k) + ".");
        }
        return e;
    }

    /**
     * An on/off switch: true or false, or a word {@link #onOff} knows (a quoted "false" or "off"
     * means off). Anything else is reported and {@code def} is used.
     */
    private static boolean onOffSetting(Map<?, ?> m, String key, boolean def, String path, List<String> problems) {
        Object o = m.get(key);
        if (o == null) return def;
        if (o instanceof Boolean) return (Boolean) o;
        Boolean word = onOff(o);
        if (word != null) return word;
        problems.add(path + ": expected true or false - using " + def + ".");
        return def;
    }

    /** What an unknown key inside one effect was probably meant to be. */
    private static String keyHint(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        if (k.startsWith("particles")) return " (for no particles in this effect use animation: none; effects.particles: false leaves them out everywhere)";
        if (k.equals("sounds")) return " (for no sound in this effect use sound: \"\"; effects.sounds: false leaves them out everywhere)";
        if (k.equals("fireworks")) return " (did you mean firework? effects.fireworks: false leaves them out everywhere)";
        if (k.equals("titles")) return " (for no title in this effect use title: \"\"; effects.titles: false leaves them out everywhere)";
        return "";
    }

    /** "none", "off", "false", "disabled" or "" -> false; "on", "true", "default" -> true; anything else null. */
    private static Boolean onOff(Object raw) {
        if (!(raw instanceof String)) return null;
        String word = ((String) raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (word.isEmpty() || word.equals("none") || word.equals("off") || word.equals("false") || word.equals("disabled")) return Boolean.FALSE;
        if (word.equals("on") || word.equals("true") || word.equals("default")) return Boolean.TRUE;
        return null;
    }

    /** colors: a list of names / #RRGGBB, or one string of them separated by commas. */
    private static List<Integer> colours(Object raw, List<Integer> fallback, String path, List<String> problems) {
        List<Object> items = new ArrayList<Object>();
        if (raw instanceof List) items.addAll((List<?>) raw);
        else for (String part : String.valueOf(raw).split("[,\\s]+")) if (!part.isEmpty()) items.add(part);
        List<Integer> out = new ArrayList<Integer>();
        for (Object item : items) {
            Integer rgb = EffectNames.colour(item);
            if (rgb != null) out.add(rgb);
            else problems.add(path + ".colors: '" + item + "' is not a colour (use a name like pink or gold, or #RRGGBB).");
        }
        return out.isEmpty() && !items.isEmpty() ? new ArrayList<Integer>(fallback) : out;
    }

    private static float number(Object o, float def, float min, float max, String path, List<String> problems) {
        double v;
        if (o instanceof Number) v = ((Number) o).doubleValue();
        else {
            try { v = Double.parseDouble(String.valueOf(o).trim()); }
            catch (NumberFormatException e) {
                problems.add(path + ": expected a number, got '" + o + "'.");
                return def;
            }
        }
        if (Double.isNaN(v)) return def;
        return (float) Math.max(min, Math.min(max, v));
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " or " : ", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
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
