package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.EffectNames;
import com.dankmc.dankvotes.core.EffectPlan;
import com.dankmc.dankvotes.core.EffectShapes;
import com.dankmc.dankvotes.core.Strings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays vote effects on Bukkit-based servers: a particle animation that follows the player,
 * a sound, a title and a firework.
 *
 * Everything that touches the world runs on the thread that owns the player: the main
 * thread, or on Folia the player's region (the entity scheduler follows them across regions
 * and stops when they leave). Particles, sounds and titles use whatever API the server
 * version has (see {@link BukkitParticles}); a particle or sound the version lacks is
 * swapped for a similar one, and the console says so once.
 *
 * Who sees and hears it: the player, and with "nearby" the players within 32 blocks who can
 * see them (a vanished player's effect is theirs alone, and launches no firework). On Paper
 * and Folia the server picks who gets each particle, building it once; otherwise the effect
 * picks them (on Folia, from the players of the voter's own region), and skips a player it
 * can't reach. Fireworks are tracked and never hurt anyone, and at most one is launched per
 * 8x8-block area a second, so a vote party in a crowded spawn stays a show rather than a lag
 * spike. One effect plays per player: a new one replaces it, unless the playing one is bigger
 * (see {@link EffectPlan#mayReplace}).
 */
final class BukkitEffects implements Listener {

    /** Ticks after launch when a firework bursts, in front of the player where they can see it. */
    private static final int FIREWORK_FUSE = 5;
    /** At most one firework per area this often. */
    private static final long FIREWORK_SPACING_MS = 1000L;
    /** How long a launched firework is remembered (for its damage); they burst within a second or two. */
    private static final long FIREWORK_MEMORY_MS = 30000L;
    private static final double NEARBY_SQUARED = (double) BukkitParticles.RADIUS * BukkitParticles.RADIUS;
    private static final Object NOTHING = new Object();

    private final Plugin plugin;
    private final SchedulerAdapter scheduler;
    private final Map<UUID, Run> playing = new ConcurrentHashMap<UUID, Run>();
    /** Fireworks we launched lately; their damage is cancelled, and those in the air are removed on disable. */
    private final Map<UUID, Launched> fireworks = new ConcurrentHashMap<UUID, Launched>();
    /** World + 8x8 area -> when a firework was last launched there. */
    private final Map<String, Long> fireworkAreas = new ConcurrentHashMap<String, Long>();
    private final Map<String, Object> particleCache = new ConcurrentHashMap<String, Object>();
    private final Map<String, Object> soundCache = new ConcurrentHashMap<String, Object>();
    private final Set<String> told = ConcurrentHashMap.<String>newKeySet();
    private volatile BukkitParticles particles;
    private volatile Titles titles;

    BukkitEffects(Plugin plugin) {
        this(plugin, new SchedulerAdapter(plugin));
    }

    BukkitEffects(Plugin plugin, SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /** Start an effect for an online player (any thread). False if they aren't online or it can't be scheduled. */
    boolean play(String username, EffectPlan plan) {
        Player player = Bukkit.getPlayerExact(username);
        if (player == null || !player.isOnline()) return false;
        final Run run = new Run(player, plan);
        boolean scheduled = scheduler.runAtEntity(player, new Runnable() {
            @Override public void run() { run.start(); }
        }, null, plan.getDelayTicks());
        if (!scheduled && plugin.isEnabled() && player.isOnline()) {
            tell("schedule", "Vote effects can't be scheduled on this server (no way to run a task for a player), so none play.");
        }
        return scheduled;
    }

    /** Our fireworks burst near players; never let them hurt anyone. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Firework && fireworks.containsKey(damager.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * On disable: once DankVotes is gone nobody cancels a firework's damage, and one saved
     * mid-flight would burst after a restart, so remove those still in the air.
     */
    void shutdown() {
        for (Launched l : fireworks.values()) {
            Firework firework = l.firework.get();
            try {
                if (firework != null && firework.isValid()) firework.remove();
            } catch (Throwable ignored) {
                // Folia: not this thread's region; it bursts within a second.
            }
        }
        fireworks.clear();
        playing.clear();
    }

    // ── one effect for one player ────────────────────────────────────

    private final class Run {
        private final Player player;
        private final UUID id;
        private final EffectPlan plan;
        private final DankVotesConfig.Effect fx;
        private final String animation;
        private final int frames;
        private int[] palette;
        private Object particle;
        /** Shown to the players around: the effect says so and the player isn't vanished. */
        private boolean nearby;
        /** The server picks the players around for each particle (Paper); else this effect does. */
        private boolean serverPicks;
        /** Players around a particle couldn't be sent to (an NPC, say): skipped from then on. */
        private Set<UUID> unreachable;
        /** Folia: the player left before the next frame. */
        private final Runnable retire = new Runnable() {
            @Override public void run() { playing.remove(id, Run.this); }
        };

        Run(Player player, EffectPlan plan) {
            this.player = player;
            this.id = player.getUniqueId();
            this.plan = plan;
            this.fx = plan.getEffect();
            this.animation = EffectShapes.normalise(fx.animation);
            this.frames = EffectShapes.frames(animation);
        }

        /** On the player's thread: sound, title and firework now, then the animation frame by frame. */
        void start() {
            try {
                begin();
            } catch (Throwable t) {
                playing.remove(id, this);
                tell("run", "Vote effects: could not play an effect on this server: " + t);
            }
        }

        private void begin() {
            if (!alive()) return;
            Run current = playing.get(id);
            if (current != null && current != this && !plan.mayReplace(current.plan)) return;   // a bigger one is playing
            playing.put(id, this);
            boolean hidden = vanished(player);
            nearby = fx.nearby && !hidden;
            serverPicks = nearby && particles().canDrawAround();
            palette = EffectShapes.palette(fx.colors);
            Location at = player.getLocation();
            if (!Strings.isBlank(fx.sound)) playSound(at, fx.sound, fx.volume, fx.pitch);
            if (plan.hasTitle()) titles().show(player, PaperPlatform.color(plan.getTitle()), PaperPlatform.color(plan.getSubtitle()));
            if (fx.firework && !hidden) launchFirework(player, fx, palette);
            particle = frames > 0 ? particleFor(fx.particle, animation) : null;
            if (particle != null) frame(0);
            else playing.remove(id, this);
        }

        void frame(final int t) {
            try {
                drawFrame(t);
            } catch (Throwable e) {
                playing.remove(id, this);
                tell("frame", "Vote effects: could not draw an effect on this server: " + e);
            }
        }

        private void drawFrame(final int t) {
            if (playing.get(id) != this) return;   // a newer effect took over
            if (!alive()) {
                playing.remove(id, this);
                return;
            }
            Location base = player.getLocation();
            BukkitParticles api = particles();
            boolean colour = api.coloured(particle);
            List<Player> others = null;   // the players around, when this effect picks them: once a frame
            for (EffectShapes.Point pt : EffectShapes.frame(animation, t)) {
                int rgb = colour ? palette[Math.abs(pt.colour) % palette.length] : 0xFFFFFF;
                double x = base.getX() + pt.x;
                double y = base.getY() + pt.y;
                double z = base.getZ() + pt.z;
                boolean aroundFailed = false;
                if (serverPicks) {
                    if (api.drawAround(player, particle, x, y, z, pt, rgb)) continue;
                    // Pick the players here from now on (one the server can't send to fails the whole call).
                    serverPicks = false;
                    aroundFailed = true;
                }
                // The voter's own particle decides whether this particle can be drawn at all.
                if (!api.draw(player, particle, x, y, z, pt, rgb)) {
                    tell("draw:" + particle, "Vote effects: could not draw " + particle + " particles on this server; effects skip them.");
                    particleCache.clear();
                    playing.remove(id, this);
                    return;
                }
                if (aroundFailed) {
                    tell("around", "Vote effects: the server could not send particles to everyone near a voter, so DankVotes "
                        + "sends them to each player itself.");
                }
                if (!nearby) continue;
                if (others == null) others = others(base);
                Iterator<Player> it = others.iterator();
                while (it.hasNext()) {
                    Player other = it.next();
                    if (api.draw(other, particle, x, y, z, pt, rgb)) continue;
                    it.remove();
                    if (unreachable == null) unreachable = new HashSet<UUID>();
                    unreachable.add(other.getUniqueId());
                }
            }
            if (t + 1 >= frames || !scheduler.runAtEntity(player, new Runnable() {
                @Override public void run() { frame(t + 1); }
            }, retire, 1)) {
                playing.remove(id, this);
            }
        }

        /**
         * The players besides the voter who see and hear a nearby effect, when this effect
         * picks them: those within 32 blocks who can see the voter. On Folia only players of
         * the voter's own region are looked at (the others belong to other threads; anyone
         * within 32 blocks almost always shares the region).
         */
        private List<Player> others(Location at) {
            List<Player> out = new ArrayList<Player>();
            boolean folia = scheduler.isFolia();
            try {
                for (Player other : at.getWorld().getPlayers()) {
                    try {
                        if (other.getUniqueId().equals(id) || (unreachable != null && unreachable.contains(other.getUniqueId()))) continue;
                        if (folia && !scheduler.ownsEntity(other)) continue;
                        if (other.getLocation().distanceSquared(at) <= NEARBY_SQUARED && other.canSee(player)) out.add(other);
                    } catch (RuntimeException ignored) {
                        // left or changed world this very moment
                    }
                }
            } catch (RuntimeException listChanged) {
                // The world's player list changed while we looked (Folia): those found so far.
            }
            return out;
        }

        /** Play the effect's sound at {@code at} for the player, and when shown nearby for the {@link #others}. */
        private void playSound(Location at, String configured, float volume, float pitch) {
            Object sound = sound(configured);
            if (sound == NOTHING) return;
            try {
                hear(player, at, sound, volume, pitch);
            } catch (Exception e) {
                tell("sound-failed:" + configured, "Vote effects: could not play the sound '" + configured + "': " + e);
                return;
            }
            if (!nearby) return;
            for (Player other : others(at)) {
                try {
                    hear(other, at, sound, volume, pitch);
                } catch (Exception ignored) {
                    // That player can't be sent sounds (an NPC, say).
                }
            }
        }

        private boolean alive() {
            return plugin.isEnabled() && player.isOnline();
        }
    }

    /** Vanish plugins (SuperVanish, PremiumVanish, Essentials...) mark a hidden player with "vanished" metadata. */
    private static boolean vanished(Player player) {
        try {
            for (MetadataValue value : player.getMetadata("vanished")) {
                if (value.asBoolean()) return true;
            }
        } catch (Throwable ignored) {
            // No metadata support: treat as visible.
        }
        return false;
    }

    // ── particles ────────────────────────────────────────────────────

    /**
     * The particle to draw for an effect: the one it names, else a similar one this version
     * has, else the animation's own. Null when nothing can be drawn.
     */
    private Object particleFor(String configured, String animation) {
        String own = EffectShapes.defaultParticle(animation);
        String wanted = Strings.isBlank(configured) ? own : configured.trim();
        String key = wanted + "|" + animation;
        Object cached = particleCache.get(key);
        if (cached != null) return cached == NOTHING ? null : cached;
        BukkitParticles api = particles();
        String canonical = EffectNames.canonicalParticle(wanted);
        Object found = null;
        String used = null;
        for (String name : EffectNames.particleCandidates(wanted, own)) {
            found = api.find(name);
            if (found != null) {
                used = name;
                break;
            }
        }
        if (found == null) {
            tell("particle:" + key, "Vote effects: no particle like '" + wanted + "' can be drawn on this server ("
                + api.describe() + "), so the " + animation + " animation is skipped.");
        } else if (!used.equals(canonical)) {
            tell("particle:" + key, "Vote effects: the " + canonical + " particle can't be drawn on this server version, so "
                + used + " is drawn instead.");
        }
        particleCache.put(key, found == null ? NOTHING : found);
        return found;
    }

    private BukkitParticles particles() {
        BukkitParticles p = particles;
        if (p == null) {
            synchronized (this) {
                p = particles;
                if (p == null) {
                    p = BukkitParticles.detect();
                    particles = p;
                }
            }
        }
        return p;
    }

    // ── sounds ───────────────────────────────────────────────────────

    /**
     * The sound to play for a configured one: a Sound the server's version knows when there
     * is one (with a similar sound standing in on older versions), otherwise its key as a
     * String ("mypack:vote.ding", or a 1.8 name like random.levelup), played by name; NOTHING
     * for neither.
     */
    private Object sound(String configured) {
        Object sound = soundCache.get(configured);
        if (sound == null) {
            sound = resolveSound(configured);
            soundCache.put(configured, sound);
        }
        return sound;
    }

    private static void hear(Player listener, Location at, Object sound, float volume, float pitch) {
        if (sound instanceof Sound) listener.playSound(at, (Sound) sound, volume, pitch);
        else listener.playSound(at, (String) sound, volume, pitch);
    }

    private Object resolveSound(String configured) {
        List<String> own = EffectNames.bukkitSoundNames(configured, false);
        for (String name : EffectNames.bukkitSoundNames(configured, true)) {
            Object sound = soundField(name);
            if (sound == null) continue;
            if (!own.contains(name)) {
                tell("sound:" + configured, "Vote effects: this server version has no sound '" + configured + "', so "
                    + name + " plays instead.");
            }
            return sound;
        }
        if (EffectNames.looksLikeKey(configured)) return EffectNames.soundKey(configured);
        tell("sound:" + configured, "Vote effects: unknown sound '" + configured + "' - use a key like entity.player.levelup.");
        return NOTHING;
    }

    /** Sound.X by name: an enum constant up to 1.21.2, a static field of the Sound interface after. */
    private static Object soundField(String name) {
        try {
            Object value = Sound.class.getField(name).get(null);
            return value instanceof Sound ? value : null;
        } catch (Throwable missing) {
            return null;
        }
    }


    // ── titles ───────────────────────────────────────────────────────

    private Titles titles() {
        Titles t = titles;
        if (t == null) {
            t = new Titles();
            titles = t;
        }
        return t;
    }

    /** sendTitle with fade times (1.11+), or the plain one; servers without Spigot's title API show none. */
    private static final class Titles {
        private final Method timed = method(Player.class, "sendTitle", String.class, String.class, int.class, int.class, int.class);
        private final Method plain = method(Player.class, "sendTitle", String.class, String.class);

        void show(Player player, String title, String subtitle) {
            // A subtitle only shows under a title, so an empty title is still sent.
            String main = title == null ? "" : title;
            String sub = subtitle == null ? "" : subtitle;
            try {
                if (timed != null) timed.invoke(player, main, sub, 10, 60, 20);
                else if (plain != null) plain.invoke(player, main, sub);
            } catch (Exception ignored) {
                // A title is a nicety; the chat thank-you still went out.
            }
        }
    }

    // ── fireworks ────────────────────────────────────────────────────

    /** A firework we launched, held weakly: one in a world that unloads doesn't keep the world in memory. */
    private static final class Launched {
        final WeakReference<Firework> firework;
        final long at;

        Launched(Firework firework, long at) {
            this.firework = new WeakReference<Firework>(firework);
            this.at = at;
        }
    }

    /** Firework#detonate() (1.12+); older fireworks just fly their (short) course. */
    private static final Method DETONATE = method(Firework.class, "detonate");

    /**
     * Launch a firework in front of the player, a little below eye level, timed to burst
     * where they're looking - unless one went up in this area within the last second.
     * On the player's thread.
     */
    private void launchFirework(Player player, DankVotesConfig.Effect fx, int[] palette) {
        try {
            Location spot = fireworkSpot(player);
            if (!roomForFirework(spot)) return;
            Firework firework = spot.getWorld().spawn(spot, Firework.class);
            long now = System.currentTimeMillis();
            forgetOldFireworks(now);
            fireworks.put(firework.getUniqueId(), new Launched(firework, now));
            FireworkMeta meta = firework.getFireworkMeta();
            List<Color> colours = new ArrayList<Color>();
            for (int rgb : palette) colours.add(Color.fromRGB(rgb));
            FireworkEffect.Type type;
            try {
                type = FireworkEffect.Type.valueOf(fx.fireworkType);
            } catch (IllegalArgumentException e) {
                type = FireworkEffect.Type.BALL_LARGE;
            }
            meta.addEffect(FireworkEffect.builder().with(type).withColor(colours).withFade(Color.WHITE)
                .flicker(true).trail(true).build());
            meta.setPower(0);
            firework.setFireworkMeta(meta);
            if (DETONATE != null) {
                final Firework rocket = firework;
                scheduler.runAtEntity(rocket, new Runnable() {
                    @Override public void run() {
                        try {
                            DETONATE.invoke(rocket);
                        } catch (Exception ignored) {
                            // It bursts on its own a moment later.
                        }
                    }
                }, null, FIREWORK_FUSE);
            }
        } catch (Throwable t) {
            tell("firework", "Vote effects: could not launch a firework on this server: " + t);
        }
    }

    /** 3 blocks ahead of the player at about eye level; above their head when that spot is inside a block. */
    private Location fireworkSpot(Player player) {
        Location eye = player.getEyeLocation();
        Vector ahead = eye.getDirection().setY(0);
        if (ahead.lengthSquared() < 1.0E-4) ahead = new Vector(0, 0, 1);   // looking straight up or down
        Location spot = eye.clone().add(ahead.normalize().multiply(3.0)).add(0, -0.4, 0);
        try {
            if (!scheduler.ownsLocation(spot) || spot.getBlock().getType().isSolid()) {
                return player.getLocation().add(0, 0.5, 0);
            }
        } catch (Throwable t) {
            return player.getLocation().add(0, 0.5, 0);
        }
        return spot;
    }

    /** One firework per 8x8-block area a second: a party for 200 players in one spawn is a few, not 200. */
    private boolean roomForFirework(Location spot) {
        String area = spot.getWorld().getName() + ":" + (spot.getBlockX() >> 3) + ":" + (spot.getBlockZ() >> 3);
        long now = System.currentTimeMillis();
        Long last = fireworkAreas.get(area);
        if (last != null && now - last < FIREWORK_SPACING_MS) return false;
        fireworkAreas.put(area, now);
        if (fireworkAreas.size() > 256) {
            Iterator<Long> it = fireworkAreas.values().iterator();
            while (it.hasNext()) {
                if (now - it.next() >= FIREWORK_SPACING_MS) it.remove();
            }
        }
        return true;
    }

    /** Forget fireworks launched long enough ago to have burst (and any already collected). */
    void forgetOldFireworks(long now) {
        Iterator<Launched> it = fireworks.values().iterator();
        while (it.hasNext()) {
            Launched l = it.next();
            if (now - l.at > FIREWORK_MEMORY_MS || l.firework.get() == null) it.remove();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Log a line once per server start (per subject), so a missing particle doesn't flood the console. */
    private void tell(String subject, String message) {
        if (told.add(subject)) plugin.getLogger().info(message);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) {
        try {
            return type.getMethod(name, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** For tests: how many effects are playing. */
    int playingCount() {
        return playing.size();
    }

    boolean isOurFirework(UUID id) {
        return fireworks.containsKey(id);
    }

    /** For tests: how many launched fireworks are remembered. */
    int rememberedFireworks() {
        return fireworks.size();
    }
}
