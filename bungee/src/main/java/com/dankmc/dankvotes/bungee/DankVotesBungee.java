package com.dankmc.dankvotes.bungee;

import com.dankmc.dankvotes.core.CommandHandler;
import com.dankmc.dankvotes.core.ConfigMapper;
import com.dankmc.dankvotes.core.DankVotesConfig;
import com.dankmc.dankvotes.core.DankVotesCore;
import com.dankmc.dankvotes.core.DefaultConfig;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * DankVotes - BungeeCord proxy entry point (also Waterfall, FlameCord and other forks).
 *
 * Use this on a BungeeCord network for network-wide vote handling: announcements, streaks,
 * /vote, /votetop and proxy-level reward commands. For in-world rewards (items, crates)
 * run the same jar on the backend servers too.
 *
 * Commands run through the shared {@link CommandHandler}; only the ones config.yml switches
 * on are registered.
 */
public final class DankVotesBungee extends Plugin implements Listener {

    private volatile DankVotesCore core;
    private CommandHandler commands;

    @Override
    public void onEnable() {
        startCore();
        commands = new CommandHandler(
            new CommandHandler.CoreAccess() {
                @Override public DankVotesCore core() { return core; }
            },
            new Runnable() {
                @Override public void run() { restartCore(); }
            },
            platformName());
        registerCommands(core.getConfig());
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("DankVotes " + getDescription().getVersion() + " enabled on " + platformName() + ".");
    }

    @Override
    public void onDisable() {
        DankVotesCore c = core;
        core = null;
        if (c != null) c.stop();
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        DankVotesCore c = core;
        if (c != null) c.onPlayerJoin(event.getPlayer().getName());
    }

    /** Forwarding in "current" mode: deliver held votes once the player reaches a backend. */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        DankVotesCore c = core;
        if (c != null && event.getServer() != null && event.getServer().getInfo() != null) {
            c.getForwarder().onPlayerServer(event.getPlayer().getName(), event.getServer().getInfo().getName());
        }
    }

    /**
     * Register the commands config.yml switches on. Proxy commands are matched before the
     * backend servers' own, so one left on here would hide, say, a backend's /vote.
     */
    private void registerCommands(DankVotesConfig config) {
        List<String> registered = new ArrayList<String>();
        for (String[] names : CommandHandler.enabledCommands(config)) {
            if (!names[0].equals("dankvotes")) warnIfTaken(names);
            getProxy().getPluginManager().registerCommand(this, new BungeeCommand(names));
            registered.add(names[0]);
        }
        commands.setRegistered(registered);
        getLogger().info(CommandHandler.registrationSummary(config));
    }

    /**
     * BungeeCord lets the newest registration of a name win, so say when DankVotes takes one
     * over. PluginManager#getCommands() only exists in builds from 2017 on, hence reflection.
     */
    private void warnIfTaken(String[] names) {
        Collection<?> existing;
        try {
            Object pm = getProxy().getPluginManager();
            Object all = pm.getClass().getMethod("getCommands").invoke(pm);
            if (!(all instanceof Collection)) return;
            existing = (Collection<?>) all;
        } catch (Throwable t) {
            return;
        }
        List<String> wanted = Arrays.asList(names);
        List<String> taken = new ArrayList<String>();
        for (Object o : existing) {
            if (!(o instanceof Map.Entry)) continue;
            Object label = ((Map.Entry<?, ?>) o).getKey();
            if (label != null && wanted.contains(String.valueOf(label).toLowerCase(java.util.Locale.ROOT))) {
                taken.add("/" + label);
            }
        }
        if (!taken.isEmpty()) {
            getLogger().warning(join(taken) + " already belongs to another plugin on this proxy; DankVotes's /" + names[0]
                + " replaces it. To keep the other one, set commands." + names[0] + ": false in config.yml and restart.");
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private synchronized void startCore() {
        File file = DefaultConfig.saveIfMissing(getDataFolder(), DankVotesBungee.class, getLogger());
        DankVotesCore c = new DankVotesCore(new BungeePlatform(this), loadConfig(file));
        c.start();
        core = c;
    }

    private synchronized void restartCore() {
        DankVotesCore old = core;
        core = null;
        if (old != null) old.stop();
        startCore();
    }

    private DankVotesConfig loadConfig(File file) {
        if (!file.exists()) return new DankVotesConfig();
        try {
            InputStream in = new FileInputStream(file);
            try {
                Object root = new Yaml().load(in);
                return ConfigMapper.fromMap(root instanceof Map ? (Map<?, ?>) root : null);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            getLogger().warning("Could not read config.yml, using defaults: " + e.getMessage());
            return new DankVotesConfig();
        }
    }

    private String platformName() {
        String name = getProxy().getName();
        return name == null || name.trim().isEmpty() ? "BungeeCord" : name;
    }

    /** One BungeeCord command per DankVotes command; the shared handler does the work. */
    private final class BungeeCommand extends Command implements TabExecutor {

        private final String canonical;

        BungeeCommand(String[] names) {
            // No permission here: the handler checks nodes itself, so /vote stays open to all.
            super(names[0], null, Arrays.copyOfRange(names, 1, names.length));
            this.canonical = names[0];
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            commands.execute(canonical, new BungeeSender(sender), args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            DankVotesCore c = core;
            return commands.complete(canonical, new BungeeSender(sender), args,
                c == null ? java.util.Collections.<String>emptyList() : c.getPlatform().getOnlinePlayerNames());
        }
    }

    private static final class BungeeSender implements CommandHandler.Sender {

        private final CommandSender sender;

        BungeeSender(CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public String playerName() {
            return sender instanceof ProxiedPlayer ? sender.getName() : null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        /** BungeeCord permissions have no "unset" state, so player commands are open to everyone. */
        @Override
        public boolean allowedByDefault(String permission) {
            return true;
        }

        @Override
        public void sendLegacy(String line) {
            sender.sendMessage(BungeePlatform.components(line));
        }
    }
}
