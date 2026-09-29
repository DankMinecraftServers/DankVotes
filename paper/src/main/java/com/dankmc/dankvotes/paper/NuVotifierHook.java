package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.Vote;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

/**
 * Bridges votes received by NuVotifier (if installed) into DankVotes.
 *
 * This lets DankVotes act as the reward engine on servers that already run NuVotifier
 * and have all their vote sites pointed at it: nothing needs to change on any vote site.
 * DankVotes' own Votifier listener should stay disabled in that setup (it can't share the
 * port), and the polling client can still run alongside for DankMinecraftServers votes.
 *
 * Implemented with reflection so DankVotes has NO compile-time dependency on NuVotifier
 * and works with any version that fires {@code com.vexsoftware.votifier.model.VotifierEvent}.
 */
public final class NuVotifierHook implements Listener {

    private static final String EVENT_CLASS = "com.vexsoftware.votifier.model.VotifierEvent";

    private final DankVotesPaper plugin;

    private NuVotifierHook(DankVotesPaper plugin) {
        this.plugin = plugin;
    }

    /** Register the hook if NuVotifier is present. Returns true if hooked. */
    @SuppressWarnings("unchecked")
    public static boolean register(DankVotesPaper plugin) {
        if (Bukkit.getPluginManager().getPlugin("Votifier") == null) return false;
        final Class<? extends Event> eventClass;
        try {
            eventClass = (Class<? extends Event>) Class.forName(EVENT_CLASS);
        } catch (Throwable t) {
            return false;
        }
        final NuVotifierHook hook = new NuVotifierHook(plugin);
        EventExecutor executor = new EventExecutor() {
            @Override public void execute(Listener listener, Event event) {
                if (eventClass.isInstance(event)) hook.handle(event);
            }
        };
        Bukkit.getPluginManager().registerEvent(eventClass, hook, EventPriority.NORMAL, executor, plugin, false);
        return true;
    }

    private void handle(Object event) {
        try {
            Object vote = event.getClass().getMethod("getVote").invoke(event);
            String username = str(vote, "getUsername");
            String service  = str(vote, "getServiceName");
            String address  = str(vote, "getAddress");
            long ts = parseTimestamp(str(vote, "getTimeStamp"));
            if (plugin.getCore() != null) {
                // Votifier has no VPN verification - treat inbound votes as verified.
                plugin.getCore().getEngine().processVote(new Vote(username, service, address, ts, true, 0));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read a NuVotifier vote: " + t.getMessage());
        }
    }

    private static String str(Object target, String getter) throws Exception {
        Object v = target.getClass().getMethod(getter).invoke(target);
        return v == null ? "" : String.valueOf(v);
    }

    private static long parseTimestamp(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v < 100000000000L ? v * 1000L : v;
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
