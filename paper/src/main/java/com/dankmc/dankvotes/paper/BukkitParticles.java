package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.EffectNames;
import com.dankmc.dankvotes.core.EffectShapes;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws one particle of a vote effect, on whatever particle API the server has.
 *
 * <ul>
 *   <li>1.9 and newer: {@code org.bukkit.Particle}. Coloured dust takes DustOptions from 1.13;
 *       on 1.9-1.12 the colour rides in the offsets, as the client reads it then. The names
 *       changed in 1.20.5 (VILLAGER_HAPPY became HAPPY_VILLAGER...); both are tried.</li>
 *   <li>1.7.10 and 1.8: Spigot's particle API, {@code player.spigot().playEffect(...)}, with
 *       org.bukkit.Effect names.</li>
 * </ul>
 *
 * Everything version-specific is reached through reflection, so this Java 8 class compiled
 * against the Spigot 1.8.8 API loads on every server and only uses what that server has.
 *
 * Paper (and Folia) can also send one particle to everyone near it who can see a given
 * player, building the packet once ({@link #drawAround}); elsewhere the effect sends it to
 * each player it picks.
 */
abstract class BukkitParticles {

    /** Players this far from an effect see its particles when it is shown to everyone nearby. */
    static final int RADIUS = 32;

    /** The particle API this server has. */
    static BukkitParticles detect() {
        try {
            return new Modern(Class.forName("org.bukkit.Particle"));
        } catch (Throwable notModern) {
            // 1.8 or older.
        }
        BukkitParticles legacy = Legacy.create();
        return legacy != null ? legacy : NONE;
    }

    /** The particle for a name (see {@link EffectNames#canonicalParticle}), or null if this server can't draw it. */
    abstract Object find(String canonical);

    /** Whether the particle takes the effect's colours. */
    abstract boolean coloured(Object particle);

    /**
     * Draw {@code particle} at x/y/z for one player. The point's velocity is used by particles
     * that move; {@code rgb} colours dust. False if it failed.
     */
    abstract boolean draw(Player player, Object particle, double x, double y, double z, EffectShapes.Point point, int rgb);

    /** Whether this server can {@link #drawAround}. */
    boolean canDrawAround() {
        return false;
    }

    /**
     * Draw {@code particle} for everyone within {@link #RADIUS} blocks of it who can see
     * {@code source} ({@code source} included), the server picking who - also across Folia's
     * regions. Must run on the thread that owns {@code source}. False if this server can't, or
     * it failed.
     */
    boolean drawAround(Player source, Object particle, double x, double y, double z, EffectShapes.Point point, int rgb) {
        return false;
    }

    /** For the log: which API is in use. */
    abstract String describe();

    /** The colour as the 1.8-1.12 client reads it from a particle's offsets (a red of exactly 0 means "default"). */
    static double channel(int value) {
        return value <= 0 ? 0.0001 : value / 255.0;
    }

    static final BukkitParticles NONE = new BukkitParticles() {
        @Override Object find(String canonical) { return null; }
        @Override boolean coloured(Object particle) { return false; }
        @Override boolean draw(Player p, Object particle, double x, double y, double z, EffectShapes.Point pt, int rgb) {
            return false;
        }
        @Override String describe() { return "no particle API"; }
    };

    // ═════════════════════════════════════════════════════════════════
    // 1.9+
    // ═════════════════════════════════════════════════════════════════

    static final class Modern extends BukkitParticles {

        private static final int PLAIN = 0;          // no data; still, or moving with count 0
        private static final int DUST_OPTIONS = 1;   // 1.13+: data = DustOptions(colour, size)
        private static final int DUST_OFFSETS = 2;   // 1.9-1.12: count 0, colour in the offsets
        private static final int COLOUR = 3;         // data = org.bukkit.Color (1.20.5+ ENTITY_EFFECT...)
        private static final int FLOAT = 4;          // data = a float (DRAGON_BREATH, SCULK_CHARGE)
        private static final int INTEGER = 5;        // data = an int (SHRIEK)

        private final Class<?> type;
        private final Method dataType;
        private final Constructor<?> dustOptions;
        private final Method playerSpawn;
        private final Method playerSpawnData;
        /** Paper: World#spawnParticle(particle, receivers, source, x, y, z, count, offsets, speed, data, force); null elsewhere. */
        private final Method worldSpawnAround;
        private final Map<Object, Integer> modes = new ConcurrentHashMap<Object, Integer>();
        /** DustOptions / Color per colour: an effect only has a few, and a vote party draws them thousands of times. */
        private final Map<Integer, Object> dust = new ConcurrentHashMap<Integer, Object>();
        private final Map<Integer, Color> colours = new ConcurrentHashMap<Integer, Color>();

        Modern(Class<?> type) throws NoSuchMethodException {
            this.type = type;
            this.dataType = optional(type, "getDataType");
            Constructor<?> dust;
            try {
                dust = Class.forName("org.bukkit.Particle$DustOptions").getConstructor(Color.class, float.class);
            } catch (Throwable before113) {
                dust = null;
            }
            this.dustOptions = dust;
            Class<?>[] plain = {type, double.class, double.class, double.class, int.class,
                double.class, double.class, double.class, double.class};
            Class<?>[] data = {type, double.class, double.class, double.class, int.class,
                double.class, double.class, double.class, double.class, Object.class};
            this.playerSpawn = Player.class.getMethod("spawnParticle", plain);
            this.playerSpawnData = Player.class.getMethod("spawnParticle", data);
            Method around;
            try {
                around = World.class.getMethod("spawnParticle", type, List.class, Player.class, double.class, double.class,
                    double.class, int.class, double.class, double.class, double.class, double.class, Object.class, boolean.class);
            } catch (Throwable notPaper) {
                around = null;
            }
            this.worldSpawnAround = around;
        }

        @Override
        Object find(String canonical) {
            for (String name : EffectNames.bukkitParticleNames(canonical)) {
                Object particle;
                try {
                    particle = type.getField(name).get(null);
                } catch (Throwable missing) {
                    continue;
                }
                if (particle == null) continue;
                Integer mode = mode(particle, canonical);
                if (mode == null) return null;   // it exists, but needs data we can't give (a block, an item...)
                modes.put(particle, mode);
                return particle;
            }
            return null;
        }

        private Integer mode(Object particle, String canonical) {
            Class<?> data = Void.class;
            if (dataType != null) {
                try {
                    Object d = dataType.invoke(particle);
                    if (d instanceof Class) data = (Class<?>) d;
                } catch (Exception e) {
                    data = Void.class;
                }
            }
            if (data == Void.class || data == void.class) return EffectNames.isColoured(canonical) ? DUST_OFFSETS : PLAIN;
            if (dustOptions != null && data == dustOptions.getDeclaringClass()) return DUST_OPTIONS;
            if (data == Color.class) return COLOUR;
            if (data == Float.class || data == float.class) return FLOAT;
            if (data == Integer.class || data == int.class) return INTEGER;
            return null;
        }

        @Override
        boolean coloured(Object particle) {
            Integer mode = modes.get(particle);
            return mode != null && (mode == DUST_OPTIONS || mode == DUST_OFFSETS || mode == COLOUR);
        }

        @Override
        boolean draw(Player player, Object particle, double x, double y, double z, EffectShapes.Point pt, int rgb) {
            return spawn(player, null, particle, x, y, z, pt, rgb);
        }

        @Override
        boolean canDrawAround() {
            return worldSpawnAround != null;
        }

        @Override
        boolean drawAround(Player source, Object particle, double x, double y, double z, EffectShapes.Point pt, int rgb) {
            return worldSpawnAround != null && spawn(null, source, particle, x, y, z, pt, rgb);
        }

        /** One particle for {@code player}, or (with a {@code source}) for the players around who can see the source. */
        private boolean spawn(Player player, Player source, Object particle, double x, double y, double z, EffectShapes.Point pt, int rgb) {
            Integer m = modes.get(particle);
            int mode = m == null ? PLAIN : m;
            int count = 1;
            double ox = 0.0;
            double oy = 0.0;
            double oz = 0.0;
            double speed = 0.0;
            Object data = null;
            try {
                switch (mode) {
                    case DUST_OPTIONS:
                        data = dust(rgb);
                        break;
                    case DUST_OFFSETS:
                        // Count 0: one particle, coloured by the offsets.
                        count = 0;
                        ox = channel((rgb >> 16) & 0xFF);
                        oy = channel((rgb >> 8) & 0xFF);
                        oz = channel(rgb & 0xFF);
                        speed = 1.0;
                        break;
                    case COLOUR:
                        data = colour(rgb);
                        break;
                    case FLOAT:
                        data = Float.valueOf(1.0f);
                        break;
                    case INTEGER:
                        data = Integer.valueOf(0);
                        break;
                    default:
                        break;
                }
                if ((mode == PLAIN || mode == FLOAT || mode == INTEGER) && pt.moving()) {
                    // Count 0 sends one particle that flies along the offsets (times the speed).
                    count = 0;
                    ox = pt.vx;
                    oy = pt.vy;
                    oz = pt.vz;
                    speed = 1.0;
                }
                if (source != null) {
                    // Not forced: the usual 32 blocks, and the viewers' own particle settings.
                    worldSpawnAround.invoke(source.getWorld(), particle, null, source, x, y, z, count, ox, oy, oz, speed, data, false);
                } else if (data != null) {
                    playerSpawnData.invoke(player, particle, x, y, z, count, ox, oy, oz, speed, data);
                } else {
                    playerSpawn.invoke(player, particle, x, y, z, count, ox, oy, oz, speed);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private Object dust(int rgb) throws Exception {
            Object d = dust.get(rgb);
            if (d == null) {
                d = dustOptions.newInstance(colour(rgb), 1.0f);
                dust.put(rgb, d);
            }
            return d;
        }

        private Color colour(int rgb) {
            Color c = colours.get(rgb);
            if (c == null) {
                c = Color.fromRGB(rgb & 0xFFFFFF);
                colours.put(rgb, c);
            }
            return c;
        }

        @Override
        String describe() {
            return "Particle API" + (dustOptions != null ? " (dust colours)" : " (dust colours in offsets)")
                + (worldSpawnAround != null ? ", Paper's" : "");
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 1.7.10 and 1.8
    // ═════════════════════════════════════════════════════════════════

    static final class Legacy extends BukkitParticles {

        private final Method playerSpigot;   // Player#spigot()
        private final Method playerEffect;   // Player.Spigot#playEffect(Location, Effect, int, int, float, float, float, float, int, int)
        /** 1.8 clients read a count of 0 as "one particle moving along the offsets" (and dust colour); 1.7 ones don't. */
        private final boolean countZero;

        private Legacy(Method playerSpigot, Method playerEffect, boolean countZero) {
            this.playerSpigot = playerSpigot;
            this.playerEffect = playerEffect;
            this.countZero = countZero;
        }

        static Legacy create() {
            try {
                Method ps = Player.class.getMethod("spigot");
                Method pe = ps.getReturnType().getMethod("playEffect", Location.class, Effect.class, int.class, int.class,
                    float.class, float.class, float.class, float.class, int.class, int.class);
                return new Legacy(ps, pe, minorVersion() >= 8);
            } catch (Throwable t) {
                return null;   // CraftBukkit without Spigot's particle API
            }
        }

        /** 7 for "1.7.10-R0.1-SNAPSHOT", 8 for "1.8.8-R0.1-SNAPSHOT"; 8 when it can't tell. */
        static int minorVersion() {
            try {
                String[] parts = Bukkit.getBukkitVersion().split("[.-]");
                return Integer.parseInt(parts[1]);
            } catch (Exception e) {
                return 8;
            }
        }

        @Override
        Object find(String canonical) {
            String name = EffectNames.legacyParticleName(canonical);
            if (name == null) return null;
            try {
                Object effect = Effect.class.getField(name).get(null);
                return effect instanceof Effect ? effect : null;
            } catch (Throwable missing) {
                return null;
            }
        }

        @Override
        boolean coloured(Object particle) {
            return countZero && particle instanceof Effect
                && ("COLOURED_DUST".equals(((Effect) particle).name()) || "POTION_SWIRL".equals(((Effect) particle).name()));
        }

        @Override
        boolean draw(Player player, Object particle, double x, double y, double z, EffectShapes.Point pt, int rgb) {
            float ox = 0f;
            float oy = 0f;
            float oz = 0f;
            float speed = 0f;
            int count = 1;
            if (coloured(particle)) {
                ox = (float) channel((rgb >> 16) & 0xFF);
                oy = (float) channel((rgb >> 8) & 0xFF);
                oz = (float) channel(rgb & 0xFF);
                speed = 1f;
                count = 0;
            } else if (countZero && pt.moving()) {
                ox = (float) pt.vx;
                oy = (float) pt.vy;
                oz = (float) pt.vz;
                speed = 1f;
                count = 0;
            }
            try {
                Location at = new Location(player.getWorld(), x, y, z);
                playerEffect.invoke(playerSpigot.invoke(player), at, particle, 0, 0, ox, oy, oz, speed, count, RADIUS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        String describe() {
            return "Spigot particle API (1.7/1.8)";
        }
    }

    private static Method optional(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
