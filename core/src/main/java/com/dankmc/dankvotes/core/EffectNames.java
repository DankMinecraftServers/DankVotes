package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Particle, sound and colour names for vote effects, across Minecraft versions.
 *
 * Owners write names the way current docs and /playsound show them: particles by their
 * name from 1.20.5 on (the vanilla id in capitals: HAPPY_VILLAGER, FIREWORK, DUST...) and
 * sounds by key (entity.player.levelup). The older Bukkit names (VILLAGER_HAPPY, REDSTONE,
 * ENTITY_PLAYER_LEVELUP...) work too. These tables give each platform the names its server
 * version knows, newest first, and a similar particle or sound for versions that don't
 * have one at all.
 */
public final class EffectNames {

    private EffectNames() {}

    // ═════════════════════════════════════════════════════════════════
    // Particles
    // ═════════════════════════════════════════════════════════════════

    /** One particle: its Bukkit names (newest first), its 1.7/1.8 Effect name and a stand-in for versions without it. */
    private static final class ParticleInfo {
        final String[] bukkit;
        final String legacy;
        final String fallback;

        ParticleInfo(String[] bukkit, String legacy, String fallback) {
            this.bukkit = bukkit;
            this.legacy = legacy;
            this.fallback = fallback;
        }
    }

    private static final Map<String, ParticleInfo> PARTICLES = new LinkedHashMap<String, ParticleInfo>();
    /** Every other name a particle has had (old Bukkit and 1.7/1.8 Effect names) -> its current name. */
    private static final Map<String, String> PARTICLE_ALIASES = new HashMap<String, String>();

    private static void particle(String name, String legacy, String fallback, String... olderBukkitNames) {
        String[] bukkit = new String[olderBukkitNames.length + 1];
        bukkit[0] = name;
        System.arraycopy(olderBukkitNames, 0, bukkit, 1, olderBukkitNames.length);
        PARTICLES.put(name, new ParticleInfo(bukkit, legacy, fallback));
        for (String old : olderBukkitNames) PARTICLE_ALIASES.put(old, name);
        if (legacy != null && !legacy.equals(name)) PARTICLE_ALIASES.put(legacy, name);
    }

    static {
        //        current name         1.7/1.8 Effect       stand-in           older Bukkit names
        particle("FIREWORK",          "FIREWORKS_SPARK",    null,              "FIREWORKS_SPARK");
        particle("HAPPY_VILLAGER",    "HAPPY_VILLAGER",     null,              "VILLAGER_HAPPY");
        particle("ANGRY_VILLAGER",    "VILLAGER_THUNDERCLOUD", null,           "VILLAGER_ANGRY");
        particle("DUST",              "COLOURED_DUST",      null,              "REDSTONE");
        particle("WITCH",             "WITCH_MAGIC",        null,              "SPELL_WITCH");
        particle("ENCHANT",           "FLYING_GLYPH",       null,              "ENCHANTMENT_TABLE");
        particle("ENCHANTED_HIT",     "MAGIC_CRIT",         null,              "CRIT_MAGIC");
        particle("CRIT",              "CRIT",               null);
        particle("FLAME",             "FLAME",              null);
        particle("HEART",             "HEART",              null);
        particle("NOTE",              "NOTE",               null);
        particle("PORTAL",            "PORTAL",             null);
        particle("CLOUD",             "CLOUD",              null);
        particle("LAVA",              "LAVA_POP",           null);
        particle("SMOKE",             "PARTICLE_SMOKE",     null,              "SMOKE_NORMAL");
        particle("LARGE_SMOKE",       "LARGE_SMOKE",        null,              "SMOKE_LARGE");
        particle("SPLASH",            "SPLASH",             null,              "WATER_SPLASH");
        particle("POOF",              null,                 "CLOUD",           "EXPLOSION_NORMAL");
        particle("ENTITY_EFFECT",     "POTION_SWIRL",       null,              "SPELL_MOB");
        particle("EFFECT",            "SPELL",              "WITCH",           "SPELL");
        particle("INSTANT_EFFECT",    "INSTANT_SPELL",      "WITCH",           "SPELL_INSTANT");
        particle("END_ROD",           null,                 "FIREWORK");
        particle("TOTEM_OF_UNDYING",  null,                 "HAPPY_VILLAGER",  "TOTEM");
        particle("DRAGON_BREATH",     null,                 "PORTAL");
        particle("SOUL_FIRE_FLAME",   null,                 "FLAME");
        particle("SOUL",              null,                 "PORTAL");
        particle("SNOWFLAKE",         null,                 "FIREWORK");
        particle("ELECTRIC_SPARK",    null,                 "CRIT");
        particle("GLOW",              null,                 "HAPPY_VILLAGER");
        particle("WAX_ON",            null,                 "FLAME");
        particle("WAX_OFF",           null,                 "FIREWORK");
        particle("SCRAPE",            null,                 "FIREWORK");
        particle("CHERRY_LEAVES",     null,                 "DUST");
        particle("NAUTILUS",          null,                 "ENCHANT");
        particle("DOLPHIN",           null,                 "SPLASH");
        particle("COMPOSTER",         null,                 "HAPPY_VILLAGER");
        particle("SPORE_BLOSSOM_AIR", null,                 "HAPPY_VILLAGER");
        particle("TRIAL_SPAWNER_DETECTION", null,           "FLAME");
    }

    /**
     * The current name of a particle as an owner wrote it: "happy_villager",
     * "minecraft:happy_villager", "VILLAGER_HAPPY" and "HAPPY_VILLAGER" are all HAPPY_VILLAGER.
     * A name DankVotes doesn't know is returned in capitals, for the server to judge.
     */
    public static String canonicalParticle(String configured) {
        if (configured == null) return "";
        String s = configured.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("minecraft:")) s = s.substring("minecraft:".length());
        s = s.replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        String alias = PARTICLE_ALIASES.get(s);
        return alias != null ? alias : s;
    }

    /** The names to try on a Bukkit server's Particle enum (1.9+), newest first. */
    public static List<String> bukkitParticleNames(String canonical) {
        ParticleInfo info = PARTICLES.get(canonical);
        if (info == null) return Collections.singletonList(canonical);
        List<String> out = new ArrayList<String>();
        Collections.addAll(out, info.bukkit);
        return out;
    }

    /** The particle's name in the 1.7/1.8 Spigot particle API (org.bukkit.Effect), or null when those versions don't have it. */
    public static String legacyParticleName(String canonical) {
        ParticleInfo info = PARTICLES.get(canonical);
        return info == null ? null : info.legacy;
    }

    /** A similar particle for servers that don't have this one (TOTEM_OF_UNDYING before 1.11...), or null. */
    public static String particleFallback(String canonical) {
        ParticleInfo info = PARTICLES.get(canonical);
        return info == null ? null : info.fallback;
    }

    /**
     * The particles to try, in order, for an effect that asks for {@code wanted} with an
     * animation whose own particle is {@code own}: the particle, the stand-ins older versions
     * have for it, then the same for the animation's own particle. All by current name.
     */
    public static List<String> particleCandidates(String wanted, String own) {
        List<String> out = new ArrayList<String>();
        for (String start : new String[] {canonicalParticle(wanted), canonicalParticle(own)}) {
            String name = start;
            for (int hops = 0; name != null && !name.isEmpty() && hops < 4; hops++) {
                if (!out.contains(name)) out.add(name);
                name = particleFallback(name);
            }
        }
        return out;
    }

    /** DUST takes its colour from the effect's colours; so does ENTITY_EFFECT. */
    public static boolean isColoured(String canonical) {
        return "DUST".equals(canonical) || "ENTITY_EFFECT".equals(canonical);
    }

    /** The vanilla particle id ("happy_villager"), as Sponge's registry knows it. */
    public static String particleId(String canonical) {
        return canonical.toLowerCase(Locale.ROOT);
    }

    // ═════════════════════════════════════════════════════════════════
    // Sounds
    // ═════════════════════════════════════════════════════════════════

    /** Sound key -> the Bukkit Sound names it had (newest first). */
    private static final Map<String, String[]> SOUNDS = new LinkedHashMap<String, String[]>();
    /** Sound key -> a similar sound for servers that are too old for it. */
    private static final Map<String, String> SOUND_FALLBACKS = new HashMap<String, String>();
    /** Any Bukkit Sound name in the table -> its key, so Sponge understands enum-style names too. */
    private static final Map<String, String> SOUND_KEYS = new HashMap<String, String>();

    private static void sound(String key, String fallback, String... bukkitNames) {
        SOUNDS.put(key, bukkitNames);
        if (fallback != null) SOUND_FALLBACKS.put(key, fallback);
        for (String n : bukkitNames) SOUND_KEYS.put(n, key);
    }

    static {
        sound("entity.player.levelup", null, "ENTITY_PLAYER_LEVELUP", "LEVEL_UP");
        sound("entity.experience_orb.pickup", null, "ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
        sound("entity.firework_rocket.launch", null, "ENTITY_FIREWORK_ROCKET_LAUNCH", "ENTITY_FIREWORK_LAUNCH", "FIREWORK_LAUNCH");
        sound("entity.firework_rocket.blast", null, "ENTITY_FIREWORK_ROCKET_BLAST", "ENTITY_FIREWORK_BLAST", "FIREWORK_BLAST");
        sound("entity.firework_rocket.large_blast", null, "ENTITY_FIREWORK_ROCKET_LARGE_BLAST", "ENTITY_FIREWORK_LARGE_BLAST", "FIREWORK_LARGE_BLAST");
        sound("entity.firework_rocket.twinkle", null, "ENTITY_FIREWORK_ROCKET_TWINKLE", "ENTITY_FIREWORK_TWINKLE", "FIREWORK_TWINKLE");
        sound("block.note_block.pling", null, "BLOCK_NOTE_BLOCK_PLING", "BLOCK_NOTE_PLING", "NOTE_PLING");
        sound("block.note_block.harp", null, "BLOCK_NOTE_BLOCK_HARP", "BLOCK_NOTE_HARP", "NOTE_PIANO");
        sound("block.note_block.chime", "block.note_block.pling", "BLOCK_NOTE_BLOCK_CHIME", "BLOCK_NOTE_CHIME");
        sound("block.note_block.bell", "block.note_block.pling", "BLOCK_NOTE_BLOCK_BELL", "BLOCK_NOTE_BELL");
        sound("block.note_block.xylophone", "block.note_block.pling", "BLOCK_NOTE_BLOCK_XYLOPHONE", "BLOCK_NOTE_XYLOPHONE");
        sound("block.amethyst_block.chime", "block.note_block.chime", "BLOCK_AMETHYST_BLOCK_CHIME");
        sound("ui.toast.challenge_complete", "entity.player.levelup", "UI_TOAST_CHALLENGE_COMPLETE");
        sound("item.totem.use", "entity.player.levelup", "ITEM_TOTEM_USE");
        sound("item.firecharge.use", null, "ITEM_FIRECHARGE_USE", "FIRE_IGNITE");
        sound("block.beacon.activate", "entity.player.levelup", "BLOCK_BEACON_ACTIVATE");
        sound("entity.villager.yes", null, "ENTITY_VILLAGER_YES", "VILLAGER_YES");
        sound("entity.villager.celebrate", "entity.villager.yes", "ENTITY_VILLAGER_CELEBRATE");
        sound("entity.ender_dragon.growl", null, "ENTITY_ENDER_DRAGON_GROWL", "ENTITY_ENDERDRAGON_GROWL", "ENDERDRAGON_GROWL");
        sound("entity.wither.spawn", null, "ENTITY_WITHER_SPAWN", "WITHER_SPAWN");
        sound("entity.generic.explode", null, "ENTITY_GENERIC_EXPLODE", "EXPLODE");
        sound("entity.arrow.hit_player", null, "ENTITY_ARROW_HIT_PLAYER", "SUCCESSFUL_HIT");
        sound("block.anvil.land", null, "BLOCK_ANVIL_LAND", "ANVIL_LAND");
        sound("entity.chicken.egg", null, "ENTITY_CHICKEN_EGG", "CHICKEN_EGG_POP");
        sound("entity.item.pickup", null, "ENTITY_ITEM_PICKUP", "ITEM_PICKUP");
        sound("block.enchantment_table.use", "entity.player.levelup", "BLOCK_ENCHANTMENT_TABLE_USE");
    }

    /** "minecraft:entity.player.levelup" or "entity.player.levelup" (a key: lower case with dots or a namespace). */
    public static boolean looksLikeKey(String configured) {
        if (configured == null) return false;
        String s = configured.trim();
        return !s.isEmpty() && (s.indexOf('.') >= 0 || s.indexOf(':') >= 0) && s.equals(s.toLowerCase(Locale.ROOT));
    }

    /**
     * The sound as a key: "entity.player.levelup" for "minecraft:entity.player.levelup",
     * "ENTITY_PLAYER_LEVELUP" or "LEVEL_UP". A key in another namespace keeps it
     * ("mypack:vote.ding"); "" for none.
     */
    public static String soundKey(String configured) {
        if (configured == null) return "";
        String s = configured.trim();
        if (s.isEmpty()) return "";
        if (looksLikeKey(s)) return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
        String upper = s.toUpperCase(Locale.ROOT);
        String known = SOUND_KEYS.get(upper);
        if (known != null) return known;
        // Bukkit's Sound names are the key in capitals with '.' as '_'; the split is a guess.
        return upper.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /**
     * The names to look up in a Bukkit server's Sound class, best first: the name as written
     * (when it is one), every name the sound has had, then the same for a similar sound that
     * older versions have.
     */
    public static List<String> bukkitSoundNames(String configured) {
        return bukkitSoundNames(configured, true);
    }

    /** Like {@link #bukkitSoundNames(String)}; without {@code standIns}, only the names of the sound itself. */
    public static List<String> bukkitSoundNames(String configured, boolean standIns) {
        List<String> out = new ArrayList<String>();
        if (configured == null || configured.trim().isEmpty()) return out;
        String s = configured.trim();
        if (!looksLikeKey(s)) add(out, s.toUpperCase(Locale.ROOT).replace('.', '_'));
        String key = soundKey(s);
        for (int hops = 0; key != null && hops < (standIns ? 4 : 1); hops++) {
            String[] names = SOUNDS.get(key);
            if (names != null) {
                for (String n : names) add(out, n);
            } else if (key.indexOf(':') < 0) {
                add(out, key.toUpperCase(Locale.ROOT).replace('.', '_'));
            }
            key = SOUND_FALLBACKS.get(key);
        }
        return out;
    }

    private static void add(List<String> list, String value) {
        if (!list.contains(value)) list.add(value);
    }

    // ═════════════════════════════════════════════════════════════════
    // Colours
    // ═════════════════════════════════════════════════════════════════

    private static final Map<String, Integer> COLOURS = new HashMap<String, Integer>();

    static {
        // The chat colours owners know from & codes...
        COLOURS.put("black", 0x000000);
        COLOURS.put("dark_blue", 0x0000AA);
        COLOURS.put("dark_green", 0x00AA00);
        COLOURS.put("dark_aqua", 0x00AAAA);
        COLOURS.put("dark_red", 0xAA0000);
        COLOURS.put("dark_purple", 0xAA00AA);
        COLOURS.put("gold", 0xFFAA00);
        COLOURS.put("gray", 0xAAAAAA);
        COLOURS.put("dark_gray", 0x555555);
        COLOURS.put("blue", 0x5555FF);
        COLOURS.put("green", 0x55FF55);
        COLOURS.put("aqua", 0x55FFFF);
        COLOURS.put("red", 0xFF5555);
        COLOURS.put("light_purple", 0xFF55FF);
        COLOURS.put("yellow", 0xFFFF55);
        COLOURS.put("white", 0xFFFFFF);
        // ...and the everyday names.
        COLOURS.put("pink", 0xFF55FF);
        COLOURS.put("magenta", 0xFF55FF);
        COLOURS.put("purple", 0xAA00AA);
        COLOURS.put("orange", 0xFF8C1A);
        COLOURS.put("lime", 0x55FF55);
        COLOURS.put("cyan", 0x00AAAA);
        COLOURS.put("light_blue", 0x55CCFF);
        COLOURS.put("brown", 0x8B5A2B);
        COLOURS.put("light_gray", 0xC6C6C6);
        COLOURS.put("silver", 0xC6C6C6);
        COLOURS.put("grey", 0xAAAAAA);
    }

    /** A colour name (pink, gold, light_blue...) or #RRGGBB as 0xRRGGBB, or null if it isn't one. */
    public static Integer colour(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            long v = ((Number) value).longValue();
            return v >= 0 && v <= 0xFFFFFF ? Integer.valueOf((int) v) : null;
        }
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (s.isEmpty()) return null;
        Integer named = COLOURS.get(s);
        if (named != null) return named;
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x")) s = s.substring(2);
        if (s.length() != 6) return null;
        try {
            return Integer.valueOf(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
