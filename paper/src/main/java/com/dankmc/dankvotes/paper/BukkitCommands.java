package com.dankmc.dankvotes.paper;

import com.dankmc.dankvotes.core.CommandHandler;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Registers the player commands (/vote, /votes, /votetop, /voteparty) while the plugin starts,
 * and only the ones config.yml switches on.
 *
 * They are deliberately not in plugin.yml: Bukkit registers every command listed there before
 * the plugin even starts, so DankVotes would take /voteparty or /votes from another plugin
 * even with that part switched off. /dankvotes is unique to DankVotes and stays in plugin.yml.
 *
 * Every Bukkit server, from 1.7.10 to the latest (forks, hybrids and Folia included), has its
 * command map behind CraftServer#getCommandMap(), and Paper has it in the API too. It is
 * looked up by reflection, so the jar needs neither.
 */
final class BukkitCommands {

    private final Plugin plugin;
    private final CommandMap map;
    private final String prefix;
    private final List<PlayerCommand> registered = new ArrayList<PlayerCommand>();

    BukkitCommands(Plugin plugin) {
        this.plugin = plugin;
        this.map = commandMap(plugin.getServer());
        this.prefix = plugin.getDescription().getName().toLowerCase(Locale.ROOT);
    }

    /** False when the server's command map can't be reached (no server known to us does that). */
    boolean available() {
        return map != null;
    }

    /** Register these commands ({name, aliases...} each); /dankvotes is skipped, plugin.yml has it. */
    List<String> register(List<String[]> commands) {
        List<String> names = new ArrayList<String>();
        if (map == null) return names;
        for (String[] command : commands) {
            if (command[0].equals("dankvotes")) continue;
            PlayerCommand cmd = new PlayerCommand(plugin, command);
            try {
                map.register(prefix, cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not register /" + command[0] + ": " + t);
                continue;
            }
            registered.add(cmd);
            names.add(command[0]);
        }
        return names;
    }

    /**
     * Take the commands out of the command map again. Matters when a plugin manager disables
     * or reloads DankVotes on a running server; on shutdown it is harmless. Best effort.
     */
    void unregister() {
        if (map == null || registered.isEmpty()) return;
        try {
            Map<String, Command> known = knownCommands(map);
            if (known != null) {
                List<String> keys = new ArrayList<String>();
                for (Map.Entry<String, Command> e : known.entrySet()) {
                    if (registered.contains(e.getValue())) keys.add(e.getKey());   // plugin.yml's /dankvotes is Bukkit's
                }
                for (String key : keys) known.remove(key);
            }
            for (PlayerCommand cmd : registered) cmd.unregister(map);
        } catch (Throwable t) {
            plugin.getLogger().fine("Could not unregister commands: " + t);
        }
        registered.clear();
    }

    /**
     * Point out commands another plugin also has. Bukkit gives a name to whichever plugin
     * registers it first, so run this once every plugin has started (a tick after enabling).
     */
    void reportClashes(Logger log) {
        if (map == null) return;
        Map<String, Command> known = null;
        try {
            known = knownCommands(map);
        } catch (Throwable ignored) {
            // Only the shared-name check below needs the full list.
        }
        for (PlayerCommand cmd : registered) {
            String name = cmd.getName();
            Command holder = holderOf(name);
            if (holder != null && !isOurs(holder)) {
                // A commands.yml alias is the owner's own choice; anything else is another plugin.
                if (!(holder instanceof FormattedCommandAlias)) {
                    log.warning("/" + name + " belongs to " + pluginName(holder, namespaceOf(holder, name, known)) + ", so DankVotes's /" + name
                        + " only works as /" + prefix + ":" + name + ". Set commands." + name
                        + ": false in config.yml to stop registering it.");
                }
            } else {
                Set<String> others = othersWith(name, known);
                if (!others.isEmpty()) {
                    log.warning("/" + name + " is also a command of " + join(others) + ". Players get DankVotes's /" + name
                        + "; to hand it to " + (others.size() == 1 ? "that plugin" : "one of them")
                        + " instead, set commands." + name + ": false in config.yml and restart.");
                }
            }
            // Bukkit quietly drops an alias another plugin already has (/vp, /myvotes...).
            Set<String> lost = new TreeSet<String>();
            for (String alias : cmd.aliases) {
                Command aliasHolder = holderOf(alias);
                if (aliasHolder != null && !isOurs(aliasHolder) && !(aliasHolder instanceof FormattedCommandAlias)) {
                    lost.add("/" + alias + " (" + pluginName(aliasHolder, namespaceOf(aliasHolder, alias, known)) + ")");
                }
            }
            if (!lost.isEmpty()) {
                log.info("DankVotes's /" + name + " has no " + join(lost) + " alias: another plugin uses "
                    + (lost.size() == 1 ? "it." : "them."));
            }
        }
    }

    private Command holderOf(String label) {
        try {
            return map.getCommand(label);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private boolean isOurs(Command command) {
        if (command == null) return false;
        if (registered.contains(command)) return true;
        return command instanceof PluginIdentifiableCommand && ((PluginIdentifiableCommand) command).getPlugin() == plugin;
    }

    /** Plugins with their own command of this name ("votingplugin:vote" and so on), besides us. */
    private Set<String> othersWith(String name, Map<String, Command> known) {
        Set<String> out = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (known == null) return out;
        for (Map.Entry<String, Command> e : known.entrySet()) {
            String key = e.getKey();
            int colon = key == null ? -1 : key.indexOf(':');
            if (colon <= 0 || !key.substring(colon + 1).equals(name)) continue;
            String namespace = key.substring(0, colon);
            if (namespace.equals(prefix) || isOurs(e.getValue())) continue;
            out.add(pluginName(e.getValue(), namespace));
        }
        return out;
    }

    /** The plugin behind a command, else the namespace it was registered under. */
    private static String pluginName(Command command, String namespace) {
        if (command instanceof PluginIdentifiableCommand) {
            Plugin owner = ((PluginIdentifiableCommand) command).getPlugin();
            if (owner != null) return owner.getName();
        }
        return namespace;
    }

    /** The namespace of the "namespace:label" entry that points at this command, or "another plugin". */
    private static String namespaceOf(Command command, String label, Map<String, Command> known) {
        if (known != null) {
            for (Map.Entry<String, Command> e : known.entrySet()) {
                String key = e.getKey();
                if (e.getValue() == command && key != null && key.length() > label.length() + 1
                        && key.endsWith(":" + label)) {
                    return key.substring(0, key.length() - label.length() - 1);
                }
            }
        }
        return "another plugin";
    }

    /** CraftServer#getCommandMap() (Server#getCommandMap() in Paper's API), or its field as a fallback. */
    static CommandMap commandMap(Server server) {
        if (server == null) return null;
        try {
            Method m = ((Object) server).getClass().getMethod("getCommandMap");
            Object map = m.invoke(server);
            if (map instanceof CommandMap) return (CommandMap) map;
        } catch (Throwable ignored) {
            // Try the field below.
        }
        Object map = field(server, "commandMap");
        return map instanceof CommandMap ? (CommandMap) map : null;
    }

    /** Every label the command map knows: Paper's CommandMap#getKnownCommands(), else the field. */
    @SuppressWarnings("unchecked")
    static Map<String, Command> knownCommands(CommandMap map) {
        try {
            Method m = ((Object) map).getClass().getMethod("getKnownCommands");
            Object known = m.invoke(map);
            if (known instanceof Map) return (Map<String, Command>) known;
        } catch (Throwable ignored) {
            // Spigot and older servers: the field.
        }
        Object known = field(map, "knownCommands");
        return known instanceof Map ? (Map<String, Command>) known : null;
    }

    private static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException next) {
                // Look in the superclass.
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String join(Set<String> parts) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String p : parts) {
            if (i > 0) sb.append(i == parts.size() - 1 ? " and " : ", ");
            sb.append(p);
            i++;
        }
        return sb.toString();
    }

    /** One player command; runs through the plugin's onCommand / onTabComplete like a plugin.yml command. */
    static final class PlayerCommand extends Command implements PluginIdentifiableCommand {

        private final Plugin owner;
        /** Every alias asked for; Bukkit drops the ones that are taken from getAliases(). */
        final List<String> aliases;

        PlayerCommand(Plugin owner, String[] names) {
            super(names[0], CommandHandler.description(names[0]),
                ("/" + names[0] + " " + CommandHandler.arguments(names[0])).trim(),
                new ArrayList<String>(Arrays.asList(names).subList(1, names.length)));
            this.owner = owner;
            this.aliases = Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(names).subList(1, names.length)));
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!owner.isEnabled()) {
                sender.sendMessage(PaperPlatform.color("&cDankVotes is not running."));
                return true;
            }
            return owner.onCommand(sender, this, label, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!owner.isEnabled()) return Collections.emptyList();
            List<String> out = owner.onTabComplete(sender, this, alias, args);
            return out == null ? Collections.<String>emptyList() : out;
        }

        @Override
        public Plugin getPlugin() {
            return owner;
        }
    }
}
