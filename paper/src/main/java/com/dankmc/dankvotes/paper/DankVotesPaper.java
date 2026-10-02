package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.CommandHandler;
import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesCore;
import com.dankmc.dankvotes.core.DefaultConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * DankVotes - entry point for every Bukkit-API server (UNIVERSAL build, Java 8).
 *
 * Loads on CraftBukkit, Spigot, Paper, Purpur, Pufferfish and other forks, hybrids such as
 * Mohist and Arclight, and Folia, from Minecraft 1.7.10 to the latest release. Receives votes
 * via outbound polling (no port forwarding), the inbound Votifier v1/v2 protocol, or an
 * existing NuVotifier install, then runs fully customizable in-game rewards.
 *
 * Commands run through the shared {@link CommandHandler}, so they behave exactly as on the
 * other platforms. Only /dankvotes is in plugin.yml; the player commands are registered at
 * startup when config.yml switches them on (see {@link BukkitCommands}).
 */
public class DankVotesPaper extends JavaPlugin implements Listener {

    private volatile DankVotesCore core;
    private CommandHandler commands;
    private BukkitCommands playerCommands;
    private boolean nuVotifierHooked;
    private boolean placeholdersHooked;
    private Object metrics;

    @Override
    public void onEnable() {
        DefaultConfig.saveIfMissing(getDataFolder(), DankVotesPaper.class, getLogger());
        startCore();
        commands = new CommandHandler(
            new CommandHandler.CoreAccess() {
                @Override public DankVotesCore core() { return core; }
            },
            new Runnable() {
                @Override public void run() { reload(); }
            },
            null);
        registerCommands();

        getServer().getPluginManager().registerEvents(this, this);

        // Optional integrations - each is a no-op when the other plugin is absent.
        DankVotesConfig config = core.getConfig();
        if (config.nuVotifierHookEnabled) {
            try {
                nuVotifierHooked = NuVotifierHook.register(this);
                if (nuVotifierHooked) {
                    getLogger().info("Hooked into NuVotifier: votes it receives will be rewarded by DankVotes.");
                    warnIfForwardingToSelf(config);
                }
            } catch (Throwable t) {
                getLogger().warning("Could not hook NuVotifier: " + t.getMessage());
            }
        }
        // Registered even with statistics and the vote party off: those placeholders then stay
        // unparsed, and turning a part back on with /dankvotes reload needs no restart.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                placeholdersHooked = new PlaceholderHook(this).register();
                if (placeholdersHooked) getLogger().info("Registered PlaceholderAPI expansion: %dankvotes_*%");
            } catch (Throwable t) {
                getLogger().warning("Could not register PlaceholderAPI expansion: " + t.getMessage());
            }
        }
        if (config.metricsEnabled) {
            try {
                metrics = MetricsHook.start(this);
            } catch (Throwable ignored) {
                // Metrics are best-effort only.
            }
        }

        getLogger().info("DankVotes " + getDescription().getVersion() + " enabled on " + core.getPlatform().getPlatformName() + ".");
    }

    @Override
    public void onDisable() {
        if (playerCommands != null) {
            playerCommands.unregister();
            playerCommands = null;
        }
        SchedulerAdapter scheduler = null;
        DankVotesCore c = core;
        core = null;
        if (c != null) {
            if (c.getPlatform() instanceof PaperPlatform) {
                scheduler = ((PaperPlatform) c.getPlatform()).getScheduler();
            }
            c.stop();
        }
        if (metrics != null) {
            try { MetricsHook.stop(metrics); } catch (Throwable ignored) {}
            metrics = null;
        }
        if (scheduler != null) scheduler.cancelAll();
    }

    /** Folia runs commands on several threads at once, so two reloads must not interleave. */
    private synchronized void reload() {
        reloadConfig();
        DankVotesCore old = core;
        core = null;
        if (old != null) old.stop();
        startCore();
    }

    private void startCore() {
        DankVotesConfig config = PaperConfigLoader.load(getConfig());
        DankVotesCore c = new DankVotesCore(new PaperPlatform(this), config);
        c.start();
        core = c;
    }

    /**
     * /dankvotes comes from plugin.yml; the player commands are registered here, and only the
     * ones config.yml switches on, so a command that is off stays free for other plugins.
     */
    private void registerCommands() {
        DankVotesConfig config = core.getConfig();
        List<String[]> wanted = CommandHandler.enabledCommands(config);
        PluginCommand admin = getCommand("dankvotes");
        if (admin != null) {
            // plugin.yml lists every subcommand; /help should show only the ones that are on.
            admin.setUsage("/dankvotes " + CommandHandler.arguments("dankvotes", config));
        }
        playerCommands = new BukkitCommands(this);
        List<String> registered = new ArrayList<String>();
        registered.add("dankvotes");
        if (playerCommands.available()) {
            registered.addAll(playerCommands.register(wanted));
            getLogger().info(CommandHandler.registrationSummary(config));
            // Plugins that start after DankVotes may want the same names; check once all have started.
            if (core.getPlatform() instanceof PaperPlatform) {
                ((PaperPlatform) core.getPlatform()).getScheduler().runGlobal(new Runnable() {
                    @Override public void run() {
                        BukkitCommands pc = playerCommands;
                        if (pc != null) pc.reportClashes(getLogger());
                    }
                });
            }
        } else if (wanted.size() > 1) {
            getLogger().severe("Could not reach the server's command map, so /vote, /votes, /votetop and /voteparty "
                + "are unavailable. Please report this with your server version.");
        }
        commands.setRegistered(registered);
    }

    /**
     * Forwarding to this machine while the NuVotifier hook is on: when the target is this
     * server's own NuVotifier (to feed a plugin that listens to it), each vote would come back
     * into DankVotes through the hook and be forwarded again.
     */
    private void warnIfForwardingToSelf(DankVotesConfig config) {
        if (!config.forwardingEnabled) return;
        for (DankVotesConfig.ForwardTarget t : config.forwardingServers) {
            String host = t.host.trim().toLowerCase(java.util.Locale.ROOT);
            if (host.equals("localhost") || host.startsWith("127.") || host.equals("::1") || host.equals("0.0.0.0")) {
                getLogger().warning("forwarding sends votes to " + t.host + ":" + t.port + " while nuvotifier-hook is on. "
                    + "If that is this server's own NuVotifier, set nuvotifier-hook: false, or each vote comes back into DankVotes.");
                return;
            }
        }
    }

    /** The active core (null while disabled). Hooks call this so /dankvotes reload is safe. */
    public DankVotesCore getCore() { return core; }
    public boolean isNuVotifierHooked() { return nuVotifierHooked; }

    // ── Events ───────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        DankVotesCore c = core;
        if (c != null) c.onPlayerJoin(event.getPlayer().getName());
    }

    // ── Commands ─────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandHandler handler = commands;
        if (handler == null) {
            sender.sendMessage(PaperPlatform.color("&cDankVotes is not running."));
            return true;
        }
        handler.execute(command.getName(), new BukkitSender(sender), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        CommandHandler handler = commands;
        if (handler == null) return new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        for (Player p : BukkitCompat.onlinePlayers()) names.add(p.getName());
        return handler.complete(command.getName(), new BukkitSender(sender), args, names);
    }

    /** A Bukkit command sender as the shared command handler sees it. */
    private static final class BukkitSender implements CommandHandler.Sender {

        private final CommandSender sender;

        BukkitSender(CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public String playerName() {
            return sender instanceof Player ? ((Player) sender).getName() : null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        /** Bukkit applies plugin.yml's "default: true" itself, so a plain check is right. */
        @Override
        public boolean allowedByDefault(String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void sendLegacy(String line) {
            sender.sendMessage(PaperPlatform.color(line));
        }
    }
}
