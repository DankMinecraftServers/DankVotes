package com.dankmc.dankvotes.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The particle animations of vote effects, worked out once for every platform.
 *
 * An animation is a number of frames, one per server tick. Each frame is a list of points
 * relative to the player's feet (y up), so the animation follows a player who moves. A point
 * may carry a velocity: particles that fly (FIREWORK, END_ROD, FLAME, TOTEM_OF_UNDYING...)
 * use it, the rest are drawn where the point is. The shapes are built from positions alone,
 * so they look right with any particle. Nothing here is random: the same frame always gives
 * the same points.
 */
public final class EffectShapes {

    private EffectShapes() {}

    /** One particle to draw. */
    public static final class Point {
        /** Offset from the player's feet, in blocks. */
        public final double x, y, z;
        /** Velocity in blocks per tick, for particles that move (all 0 = drawn still). */
        public final double vx, vy, vz;
        /** Which of the effect's colours it takes (any number; wraps around), or -1 for a white spark. */
        public final int colour;

        Point(double x, double y, double z, double vx, double vy, double vz, int colour) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.colour = colour;
        }

        public boolean moving() {
            return vx != 0 || vy != 0 || vz != 0;
        }
    }

    /** Animation names, in the order config.yml lists them. */
    public static final List<String> ANIMATIONS = Collections.unmodifiableList(Arrays.asList(
        "burst", "sparkle", "helix", "tornado", "fountain", "ring", "halo", "hearts", "none"));

    /** Frames of the firework that {@link #firework} draws. */
    public static final int FIREWORK_FRAMES = 16;

    private static final double TAU = Math.PI * 2;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

    /** "Burst", " burst " and "BURST" are all burst; "" is none. */
    public static String normalise(String animation) {
        String a = animation == null ? "" : animation.trim().toLowerCase(Locale.ROOT);
        return a.isEmpty() ? "none" : a;
    }

    public static boolean exists(String animation) {
        return ANIMATIONS.contains(normalise(animation));
    }

    /** How many frames (ticks) the animation lasts; 0 for none or an unknown name. */
    public static int frames(String animation) {
        String a = normalise(animation);
        if (a.equals("burst")) return 16;
        if (a.equals("sparkle")) return 30;
        if (a.equals("helix")) return 46;
        if (a.equals("tornado")) return 40;
        if (a.equals("fountain")) return 38;
        if (a.equals("ring")) return 30;
        if (a.equals("halo")) return 40;
        if (a.equals("hearts")) return 30;
        return 0;
    }

    /** The particle an animation draws with when the effect doesn't name one. */
    public static String defaultParticle(String animation) {
        String a = normalise(animation);
        if (a.equals("sparkle")) return "HAPPY_VILLAGER";
        if (a.equals("helix")) return "TOTEM_OF_UNDYING";
        if (a.equals("tornado")) return "FLAME";
        if (a.equals("fountain")) return "DUST";
        if (a.equals("ring")) return "DUST";
        if (a.equals("halo")) return "END_ROD";
        if (a.equals("hearts")) return "HEART";
        return "FIREWORK";
    }

    /** Every frame of every animation, worked out once: points never change, and a vote party draws the same frame for everyone. */
    private static final java.util.Map<String, List<List<Point>>> FRAMES = new java.util.concurrent.ConcurrentHashMap<String, List<List<Point>>>();

    /** The points of frame {@code t} (0-based), unmodifiable; empty when the animation has no such frame. */
    public static List<Point> frame(String animation, int t) {
        String a = normalise(animation);
        int frames = frames(a);
        if (t < 0 || t >= frames) return Collections.emptyList();
        List<List<Point>> all = FRAMES.get(a);
        if (all == null) {
            List<List<Point>> built = new ArrayList<List<Point>>(frames);
            for (int i = 0; i < frames; i++) built.add(Collections.unmodifiableList(compute(a, i)));
            all = Collections.unmodifiableList(built);
            FRAMES.put(a, all);
        }
        return all.get(t);
    }

    private static List<Point> compute(String a, int t) {
        List<Point> out = new ArrayList<Point>();
        if (a.equals("burst")) burst(out, t);
        else if (a.equals("sparkle")) sparkle(out, t);
        else if (a.equals("helix")) helix(out, t);
        else if (a.equals("tornado")) tornado(out, t);
        else if (a.equals("fountain")) fountain(out, t);
        else if (a.equals("ring")) ring(out, t);
        else if (a.equals("halo")) halo(out, t);
        else if (a.equals("hearts")) hearts(out, t);
        return out;
    }

    /** An effect's colours as 0xRRGGBB values to pick from by {@link Point#colour}; white when it has none. */
    public static int[] palette(List<Integer> colours) {
        if (colours == null || colours.isEmpty()) return new int[] {0xFFFFFF};
        int[] out = new int[colours.size()];
        for (int i = 0; i < out.length; i++) out[i] = colours.get(i) & 0xFFFFFF;
        return out;
    }

    /**
     * A firework drawn with particles, for platforms that don't launch real ones: frame
     * {@code t}, relative to the point where it bursts. Points with colour -1 are white sparks
     * flying outwards; the others take the effect's colours.
     */
    public static List<Point> firework(int t) {
        List<Point> out = new ArrayList<Point>();
        if (t == 0) sphere(out, 30, 0, 0, 0, 0.2, 0.3, -1);
        else if (t == 1) sphere(out, 40, 0, 0, 0, 0.6, 0, 0);
        else if (t == 3) sphere(out, 40, 0, 0, 0, 1.3, 0, 1);
        else if (t == 5) sphere(out, 44, 0, 0, 0, 1.9, 0, 2);
        else if (t >= 7 && t < FIREWORK_FRAMES && (t - 7) % 2 == 0) {
            double drop = 0.12 * (t - 7);
            for (int i = 0; i < 10; i++) {
                double[] d = direction(t * 31 + i);
                double r = 0.6 + 1.4 * rand(t * 97 + i);
                out.add(new Point(d[0] * r, d[1] * r - drop, d[2] * r, 0, -0.02, 0, i));
            }
        }
        return out;
    }

    // ── the animations ───────────────────────────────────────────────

    /** Sparks fly out from the chest, then rise around the player. */
    private static void burst(List<Point> out, int t) {
        if (t == 0) sphere(out, 36, 0, 1.0, 0, 0.35, 0.32, 0);
        else if (t == 2) sphere(out, 28, 0, 1.0, 0, 0.9, 0.10, 1);
        else if (t == 4) {
            // The widest shell reaches the ground; what would be under the player's feet is left out.
            List<Point> shell = new ArrayList<Point>();
            sphere(shell, 24, 0, 1.0, 0, 1.5, 0.05, 2);
            for (Point p : shell) if (p.y >= 0.05) out.add(p);
        } else if (t >= 6 && t % 2 == 0) {
            for (int i = 0; i < 5; i++) {
                double angle = TAU * rand(t * 13 + i);
                double radius = 0.3 + 1.0 * rand(t * 17 + i + 5);
                double y = 0.2 + 2.0 * rand(t * 19 + i + 11);
                out.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius, 0, 0.06, 0, i));
            }
        }
    }

    /** Twinkles appear all around the player. */
    private static void sparkle(List<Point> out, int t) {
        if (t % 2 != 0) return;
        for (int i = 0; i < 5; i++) {
            double angle = TAU * rand(t * 7 + i);
            double radius = 0.4 + 0.8 * rand(t * 11 + i + 3);
            double y = 0.1 + 2.2 * rand(t * 23 + i + 7);
            out.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius, 0, 0, 0, t / 2 + i));
        }
    }

    /** Two strands wind up around the player, then burst into a crown above their head. */
    private static void helix(List<Point> out, int t) {
        if (t < 40) {
            for (int strand = 0; strand < 2; strand++) {
                for (int k = 0; k < 2; k++) {
                    double u = (t * 2 + k) / 80.0;
                    double angle = u * 2 * TAU + strand * Math.PI;
                    out.add(new Point(Math.cos(angle) * 0.85, 0.1 + u * 2.3, Math.sin(angle) * 0.85, 0, 0.015, 0, strand));
                }
            }
        } else if (t == 40) {
            sphere(out, 30, 0, 2.55, 0, 0.3, 0.22, 0);
        } else if (t == 42) {
            circle(out, 24, 0, 2.55, 0, 1.1, 0, 1);
        } else if (t == 44) {
            circle(out, 28, 0, 2.55, 0, 1.6, 0.4, 2);
        }
    }

    /** A spinning cone that widens upwards, like a whirlwind of fire. */
    private static void tornado(List<Point> out, int t) {
        for (int arm = 0; arm < 2; arm++) {
            for (int h = 0; h < 6; h++) {
                double y = (h + (t % 4) / 4.0) / 6.0 * 2.4;
                double radius = 0.2 + 0.45 * y;
                double angle = t * 0.5 + y * 2.2 + arm * Math.PI;
                out.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius, 0, 0.03, 0, h));
            }
        }
    }

    /** Droplets shoot up from the player's head and rain down around them. */
    private static void fountain(List<Point> out, int t) {
        for (int i = 0; i < 40; i++) {
            int born = i / 2;
            int age = t - born;
            if (age < 0 || age > 17) continue;
            double angle = i * GOLDEN_ANGLE;
            double outwards = 0.08 + 0.04 * rand(i + 101);
            double up = 0.30 + 0.05 * rand(i + 202);
            double y = 1.9 + up * age - 0.5 * 0.045 * age * age;
            if (y < 0.05) continue;
            out.add(new Point(Math.cos(angle) * outwards * age, y, Math.sin(angle) * outwards * age, 0, 0, 0, i));
        }
    }

    /** Three shockwaves roll out across the ground. */
    private static void ring(List<Point> out, int t) {
        for (int wave = 0; wave < 3; wave++) {
            int age = t - wave * 8;
            if (age < 0 || age > 13) continue;
            double radius = 0.4 + 2.8 * age / 13.0;
            int n = (int) Math.max(12, Math.min(24, Math.round(radius * 8)));
            circle(out, n, 0, 0.15, 0, radius, wave * 0.3, wave);
        }
    }

    /** A turning halo above the player's head, now and then shedding a spark. */
    private static void halo(List<Point> out, int t) {
        for (int i = 0; i < 6; i++) {
            double angle = TAU * i / 6 + t * 0.22;
            out.add(new Point(Math.cos(angle) * 0.42, 2.2, Math.sin(angle) * 0.42, 0, 0, 0, i));
        }
        if (t % 10 == 0) {
            for (int i = 0; i < 3; i++) {
                double angle = TAU * rand(t * 5 + i);
                out.add(new Point(Math.cos(angle) * 0.42, 2.15, Math.sin(angle) * 0.42, 0, -0.05, 0, i));
            }
        }
    }

    /** Hearts pop up around the player's head. */
    private static void hearts(List<Point> out, int t) {
        if (t % 3 != 0) return;
        for (int i = 0; i < 2; i++) {
            double angle = TAU * rand(t * 3 + i);
            double radius = 0.5 + 0.35 * rand(t * 5 + i + 1);
            double y = 1.75 + 0.6 * rand(t * 7 + i + 2);
            out.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius, 0, 0, 0, i));
        }
    }

    // ── building blocks ──────────────────────────────────────────────

    /** {@code n} points spread evenly over a sphere, each flying outwards at {@code speed}. */
    private static void sphere(List<Point> out, int n, double cx, double cy, double cz, double radius, double speed, int colour) {
        for (int i = 0; i < n; i++) {
            double y = 1 - 2 * (i + 0.5) / n;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = i * GOLDEN_ANGLE;
            double dx = Math.cos(angle) * r;
            double dz = Math.sin(angle) * r;
            out.add(new Point(cx + dx * radius, cy + y * radius, cz + dz * radius,
                dx * speed, y * speed, dz * speed, colour < 0 ? -1 : colour + i));
        }
    }

    /** {@code n} points on a flat circle, starting at angle {@code turn}. */
    private static void circle(List<Point> out, int n, double cx, double cy, double cz, double radius, double turn, int colour) {
        for (int i = 0; i < n; i++) {
            double angle = TAU * i / n + turn;
            out.add(new Point(cx + Math.cos(angle) * radius, cy, cz + Math.sin(angle) * radius, 0, 0, 0, colour + i));
        }
    }

    /** A direction on the unit sphere for a seed. */
    private static double[] direction(int seed) {
        double y = 2 * rand(seed) - 1;
        double r = Math.sqrt(Math.max(0, 1 - y * y));
        double angle = TAU * rand(seed + 7919);
        return new double[] {Math.cos(angle) * r, y, Math.sin(angle) * r};
    }

    /** A fixed "random" number in [0, 1) for a seed, so frames never change between runs. */
    static double rand(int seed) {
        long h = (seed + 0x632BE59BD9B4E019L) * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
