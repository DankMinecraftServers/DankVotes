package com.dankmc.dankvotes.sponge;

import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.EffectNames;
import com.dankmc.dankvotes.core.EffectPlan;
import com.dankmc.dankvotes.core.EffectShapes;
import com.dankmc.dankvotes.core.Strings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.effect.particle.ParticleEffect;
import org.spongepowered.api.effect.particle.ParticleOptions;
import org.spongepowered.api.effect.particle.ParticleType;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.registry.RegistryTypes;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Color;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3d;
import org.spongepowered.plugin.PluginContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Plays vote effects on Sponge (API 8+): the particle animation that follows the player, a
 * sound, a title, and a firework drawn with particles (a real rocket can't be stopped from
 * hurting players the same way on every Sponge version, so none is launched). The drawn
 * firework is shown to the same players as the rest of the effect: the player, and with
 * "nearby" the players within 32 blocks who can see them (a vanished player's effect is
 * theirs alone). A player a particle can't be sent to is skipped from then on.
 *
 * Everything runs on the server thread through Sponge's scheduler. Particles are looked up
 * in the game's registry by their vanilla id; one a version lacks is swapped for a similar
 * one, and the console says so once.
 */
final class SpongeEffects {

    /** Players this close see and hear an effect shown to everyone nearby. */
    private static final double NEARBY = 32.0;
    /** Ticks after the effect starts when the drawn firework bursts. */
    private static final int FIREWORK_AT = 6;
    private static final Object NOTHING = new Object();

    private final PluginContainer container;
    private final Logger logger;
    private final Map<UUID, Run> playing = new ConcurrentHashMap<UUID, Run>();
    private final Map<String, Object> particleCache = new ConcurrentHashMap<String, Object>();
    private final Set<String> told = ConcurrentHashMap.<String>newKeySet();

    SpongeEffects(PluginContainer container, Logger logger) {
        this.container = container;
        this.logger = logger;
    }

    /** Start an effect for an online player (any thread). */
    boolean play(String username, EffectPlan plan) {
        Optional<ServerPlayer> player = SpongePlatform.player(username);
        if (!player.isPresent()) return false;
        final Run run = new Run(player.get().uniqueId(), plan);
        return schedule(new Runnable() {
            @Override public void run() { run.start(); }
        }, plan.getDelayTicks());
    }

    private boolean schedule(Runnable task, long delayTicks) {
        try {
            Sponge.server().scheduler().submit(Task.builder().plugin(container).delay(Ticks.of(Math.max(0L, delayTicks)))
                .execute(task).build());
            return true;
        } catch (RuntimeException stopping) {
            return false;
        }
    }

    // ── one effect for one player ────────────────────────────────────

    private final class Run {
        private final UUID id;
        private final EffectPlan plan;
        private final DankVotesConfig.Effect fx;
        private final String animation;
        private final int frames;
        private final int total;
        private int[] palette;
        private ParticleType particle;
        private boolean particleColoured;
        private ParticleType spark;
        private ParticleType dust;
        private boolean dustColoured;
        /** Where the drawn firework bursts (it stays there, like a real one); null for none. */
        private Vector3d burst;
        /** Built effects for points that don't move, by particle and colour. */
        private final Map<String, ParticleEffect> still = new java.util.HashMap<String, ParticleEffect>();
        /** Players around a particle couldn't be sent to: skipped from then on. */
        private final Set<UUID> unreachable = new HashSet<UUID>();

        Run(UUID id, EffectPlan plan) {
            this.id = id;
            this.plan = plan;
            this.fx = plan.getEffect();
            this.animation = EffectShapes.normalise(fx.animation);
            this.frames = EffectShapes.frames(animation);
            this.total = Math.max(frames, fx.firework ? FIREWORK_AT + EffectShapes.FIREWORK_FRAMES : 0);
        }

        void start() {
            try {
                begin();
            } catch (Throwable t) {
                playing.remove(id, this);
                tell("run", "Vote effects: could not play an effect on this server: " + t);
            }
        }

        private void begin() {
            Optional<ServerPlayer> found = Sponge.server().player(id);
            if (!found.isPresent()) return;
            ServerPlayer player = found.get();
            Run current = playing.get(id);
            if (current != null && current != this && !plan.mayReplace(current.plan)) return;   // a bigger one is playing
            playing.put(id, this);
            palette = EffectShapes.palette(fx.colors);
            Vector3d at = player.position();
            if (!Strings.isBlank(fx.sound)) playSound(viewers(player, fx.nearby, unreachable), at, fx.sound, fx.volume, fx.pitch);
            if (plan.hasTitle()) {
                try {
                    player.showTitle(Title.title(SpongePlatform.component(plan.getTitle()), SpongePlatform.component(plan.getSubtitle())));
                } catch (RuntimeException ignored) {
                    // A title is a nicety; the chat thank-you still went out.
                }
            }
            particle = frames > 0 ? particleFor(fx.particle, animation) : null;
            particleColoured = particle != null && EffectNames.isColoured(id(particle));
            if (fx.firework) {
                spark = particleFor("FIREWORK", "burst");
                dust = particleFor("DUST", "fountain");
                dustColoured = dust != null && EffectNames.isColoured(id(dust));
                burst = fireworkSpot(player);
                playSound(viewers(player, fx.nearby, unreachable), at, "entity.firework_rocket.launch", 1.0f, 1.0f);
            }
            if (particle == null && burst == null) {
                playing.remove(id, this);
                return;
            }
            frame(0);
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
            if (playing.get(id) != this) return;
            Optional<ServerPlayer> found = Sponge.server().player(id);
            if (!found.isPresent()) {
                playing.remove(id, this);
                return;
            }
            ServerPlayer player = found.get();
            Vector3d base = player.position();
            List<ServerPlayer> viewers = viewers(player, fx.nearby, unreachable);
            if (particle != null && t < frames) {
                for (EffectShapes.Point pt : EffectShapes.frame(animation, t)) {
                    draw(viewers, particle, particleColoured, base.add(pt.x, pt.y, pt.z), pt, palette[Math.abs(pt.colour) % palette.length]);
                }
            }
            int f = t - FIREWORK_AT;
            if (burst != null && f >= 0 && f < EffectShapes.FIREWORK_FRAMES) {
                if (f == 0) playSound(viewers, burst, "entity.firework_rocket.large_blast", 1.0f, 1.0f);
                if (f == 8) playSound(viewers, burst, "entity.firework_rocket.twinkle", 1.0f, 1.0f);
                // Drawn for the same players as the rest: with nearby off (the vote party, where
                // everyone gets one) each player sees only their own, not dozens of others'.
                for (EffectShapes.Point pt : EffectShapes.firework(f)) {
                    Vector3d at = burst.add(pt.x, pt.y, pt.z);
                    if (pt.colour < 0) draw(viewers, spark, false, at, pt, 0xFFFFFF);
                    else draw(viewers, dust, dustColoured, at, pt, palette[pt.colour % palette.length]);
                }
            }
            if (t + 1 >= total || !schedule(new Runnable() {
                @Override public void run() { frame(t + 1); }
            }, 1)) {
                playing.remove(id, this);
            }
        }

        /**
         * Draw one particle for the viewers: coloured dust, or a particle moving along the
         * point's velocity. The first viewer is the voter, whose particle decides whether it
         * can be drawn at all; another viewer it can't be sent to is skipped from then on.
         */
        private void draw(List<ServerPlayer> viewers, ParticleType type, boolean colour, Vector3d at, EffectShapes.Point pt, int rgb) {
            if (type == null) return;
            ParticleEffect effect;
            try {
                boolean moving = !colour && pt.moving();
                String key = (type == particle ? "p" : type == dust ? "d" : "s") + ":" + (colour ? rgb : -1);
                effect = moving ? null : still.get(key);
                if (effect == null) {
                    ParticleEffect.Builder b = ParticleEffect.builder().type(type).quantity(1);
                    if (colour) b.option(ParticleOptions.COLOR, Color.ofRgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
                    else if (moving) b.velocity(new Vector3d(pt.vx, pt.vy, pt.vz));
                    effect = b.build();
                    if (!moving) still.put(key, effect);
                }
                viewers.get(0).spawnParticles(effect, at);
            } catch (RuntimeException e) {
                tell("draw:" + id(type), "Vote effects: could not draw " + id(type) + " particles: " + e);
                if (type == particle) particle = null;
                else if (type == dust) dust = null;
                else spark = null;
                return;
            }
            for (int i = 1; i < viewers.size(); i++) {
                ServerPlayer viewer = viewers.get(i);
                try {
                    viewer.spawnParticles(effect, at);
                } catch (RuntimeException e) {
                    viewers.remove(i--);
                    unreachable.add(viewer.uniqueId());
                }
            }
        }
    }

    // ── drawing ──────────────────────────────────────────────────────

    /**
     * The player first, and with {@code nearby} the players in the same world within 32 blocks
     * of them who can see them (none for a vanished player), except those in {@code skip}.
     */
    private static List<ServerPlayer> viewers(ServerPlayer player, boolean nearby, Set<UUID> skip) {
        List<ServerPlayer> out = new ArrayList<ServerPlayer>();
        out.add(player);
        if (!nearby) return out;
        ServerWorld world = player.world();
        Vector3d at = player.position();
        for (Object o : Sponge.server().onlinePlayers().toArray()) {
            if (!(o instanceof ServerPlayer) || o == player) continue;
            ServerPlayer other = (ServerPlayer) o;
            try {
                UUID uuid = other.uniqueId();
                if (uuid.equals(player.uniqueId()) || skip.contains(uuid)) continue;
                if (other.world().equals(world) && other.position().distanceSquared(at) <= NEARBY * NEARBY && other.canSee(player)) {
                    out.add(other);
                }
            } catch (RuntimeException ignored) {
                // left this very moment
            }
        }
        return out;
    }

    /** The particle an effect names (or a similar one this version has, or the animation's own); null if none. */
    private ParticleType particleFor(String configured, String animation) {
        String own = EffectShapes.defaultParticle(animation);
        String wanted = Strings.isBlank(configured) ? own : configured.trim();
        String key = wanted + "|" + animation;
        Object cached = particleCache.get(key);
        if (cached != null) return cached == NOTHING ? null : (ParticleType) cached;
        String canonical = EffectNames.canonicalParticle(wanted);
        ParticleType found = null;
        String used = null;
        for (String name : EffectNames.particleCandidates(wanted, own)) {
            found = lookup(name);
            if (found != null) {
                used = name;
                break;
            }
        }
        if (found == null) {
            tell("particle:" + key, "Vote effects: no particle like '" + wanted + "' exists on this server, so the "
                + animation + " animation is skipped.");
        } else if (!used.equals(canonical)) {
            tell("particle:" + key, "Vote effects: the " + canonical + " particle can't be drawn on this server, so " + used + " is drawn instead.");
        }
        particleCache.put(key, found == null ? NOTHING : found);
        return found;
    }

    private static ParticleType lookup(String canonical) {
        try {
            Optional<ParticleType> type = RegistryTypes.PARTICLE_TYPE.get()
                .findValue(ResourceKey.of("minecraft", EffectNames.particleId(canonical)));
            return type.isPresent() ? type.get() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The registry id of a particle type, in capitals ("DUST"), as EffectNames uses. */
    private static String id(ParticleType type) {
        try {
            return RegistryTypes.PARTICLE_TYPE.get().valueKey(type).value().toUpperCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 3 blocks ahead of the player, a little above eye level, where a firework would burst in view. */
    private static Vector3d fireworkSpot(ServerPlayer player) {
        Vector3d at = player.position();
        try {
            Vector3d look = player.headDirection();
            double length = Math.sqrt(look.x() * look.x() + look.z() * look.z());
            if (length > 1.0E-3) return at.add(look.x() / length * 3.0, 2.2, look.z() / length * 3.0);
        } catch (RuntimeException ignored) {
            // No head direction: burst above them.
        }
        return at.add(0, 3.0, 0);
    }

    // ── sounds ───────────────────────────────────────────────────────

    /** Play a sound at {@code at} for the listeners. */
    private void playSound(List<ServerPlayer> listeners, Vector3d at, String configured, float volume, float pitch) {
        String key = EffectNames.soundKey(configured);
        if (key.isEmpty()) return;
        Sound sound;
        try {
            int colon = key.indexOf(':');
            Key name = colon < 0 ? Key.key("minecraft", key) : Key.key(key.substring(0, colon), key.substring(colon + 1));
            sound = Sound.sound(name, Sound.Source.MASTER, volume, pitch);
        } catch (RuntimeException e) {
            tell("sound:" + configured, "Vote effects: '" + configured + "' isn't a sound key - use one like entity.player.levelup.");
            return;
        }
        for (ServerPlayer listener : listeners) {
            try {
                listener.playSound(sound, at.x(), at.y(), at.z());
            } catch (RuntimeException ignored) {
                // That player just left.
            }
        }
    }

    private void tell(String subject, String message) {
        if (told.add(subject)) logger.info(message);
    }
}
