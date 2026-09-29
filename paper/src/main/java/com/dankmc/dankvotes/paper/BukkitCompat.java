package com.dankmc.dankvotes.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small bridges over Bukkit API differences between Minecraft versions.
 *
 * {@code Bukkit.getOnlinePlayers()} returned {@code Player[]} up to 1.7.9 and a
 * {@code Collection} from 1.7.10 on. Calling the Collection version on an older server throws
 * NoSuchMethodError, so this looks the method up once and uses reflection only when the
 * server still has the array form.
 */
final class BukkitCompat {

    /** Non-null only on servers where getOnlinePlayers() still returns an array. */
    private static final Method LEGACY_ONLINE_PLAYERS = findLegacyOnlinePlayers();

    private BukkitCompat() {}

    /** A snapshot of the online players, safe to iterate from any thread. */
    static List<Player> onlinePlayers() {
        if (LEGACY_ONLINE_PLAYERS != null) {
            try {
                Object players = LEGACY_ONLINE_PLAYERS.invoke(null);
                return players == null ? new ArrayList<Player>() : new ArrayList<Player>(Arrays.asList((Player[]) players));
            } catch (Exception e) {
                return new ArrayList<Player>();
            }
        }
        return new ArrayList<Player>(Bukkit.getOnlinePlayers());
    }

    private static Method findLegacyOnlinePlayers() {
        try {
            Method m = Bukkit.class.getMethod("getOnlinePlayers");
            return m.getReturnType().isArray() ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
