package com.hush.allpurpose;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Command guard: cancels a small list of commands for anyone without the
 * bypass permission. Exists because LuckPerms CANNOT stop opped players from
 * using vanilla commands: Paper resolves `minecraft.command.*` by op level, so
 * a `false` node is simply ignored for an op. Verified live (2026-10-18):
 *   - op   -> /tick query and /minecraft:say ran while both nodes were false
 *   - non-op -> the same nodes refused the command
 * Plugin commands (Essentials /eco, /gmc, AllPurpose /bounty) ARE LP-gated for
 * ops, so this guard only covers the vanilla holes: /effect /summon /tick rate
 * /enchant.
 *
 * RevengeByRain is exempt without a special case: they hold the global `*`
 * node, which resolves the bypass permission to true.
 *
 * config.yml (all optional, defaults below still apply on an old config):
 *   command-guard:
 *     enabled: true
 *     bypass-permission: allpurpose.commandguard.bypass
 *     message: "&cThat command is disabled."
 *     commands: [effect, summon, "tick rate", enchant]
 * An entry with no space blocks every use of the command; an entry with a
 * space blocks only that subcommand ("tick rate" leaves /tick query alone).
 */
public class CommandGuard implements Listener {

    /** Fallback list, so the guard still works before config.yml is updated. */
    private static final List<String> DEFAULT_COMMANDS = Collections.unmodifiableList(
            java.util.Arrays.asList("effect", "summon", "tick rate", "enchant"));

    private static final String DEFAULT_BYPASS = "allpurpose.commandguard.bypass";
    private static final String DEFAULT_MESSAGE = "&cThat command is disabled.";
    /** One log line per player per window, so a spamming player can't flood the log. */
    private static final long LOG_COOLDOWN_MS = 10000L;

    private final AllPurpose plugin;
    private final Map<UUID, Long> lastLogged = new ConcurrentHashMap<>();

    public CommandGuard(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        try {
            if (!enabled()) return;
            Player p = e.getPlayer();
            if (p == null) return;
            String bypass = bypassPermission();
            if (!bypass.isEmpty() && p.hasPermission(bypass)) return;
            String msg = e.getMessage();
            if (msg == null) return;
            msg = msg.trim();
            while (msg.startsWith("/")) msg = msg.substring(1).trim();
            if (msg.isEmpty()) return;
            String[] parts = msg.split("\\s+");
            String name = base(parts[0]);
            if (name.isEmpty()) return;
            String sub = parts.length > 1 ? base(parts[1]) : "";
            String hit = match(name, sub);
            if (hit == null) return;
            e.setCancelled(true);
            try { p.sendMessage(TextUtil.legacy(message())); } catch (Exception ignored) {}
            note(p, hit);
        } catch (Exception ignored) {}
    }

    /** "minecraft:enchant" / "bukkit:help" -> "enchant" / "help". */
    private String base(String token) {
        if (token == null) return "";
        String t = token.trim();
        int colon = t.indexOf(':');
        if (colon >= 0) t = t.substring(colon + 1);
        return TextUtil.norm(t);
    }

    /** Returns the matching config entry, or null when the command is allowed. */
    private String match(String name, String sub) {
        for (String entry : blocked()) {
            if (entry == null) continue;
            String[] et = entry.trim().split("\\s+");
            if (et.length == 0 || et[0].isEmpty()) continue;
            if (!base(et[0]).equals(name)) continue;
            if (et.length == 1) return entry;                            // whole command
            if (base(et[1]).equals(sub)) return entry;                   // that subcommand only
        }
        return null;
    }

    private boolean enabled() {
        try { return plugin.getConfig().getBoolean("command-guard.enabled", true); }
        catch (Exception e) { return true; }
    }

    private String bypassPermission() {
        try {
            String s = plugin.getConfig().getString("command-guard.bypass-permission", DEFAULT_BYPASS);
            return s == null ? DEFAULT_BYPASS : s.trim();
        } catch (Exception e) { return DEFAULT_BYPASS; }
    }

    private String message() {
        try {
            String s = plugin.getConfig().getString("command-guard.message", DEFAULT_MESSAGE);
            return (s == null || s.isEmpty()) ? DEFAULT_MESSAGE : s;
        } catch (Exception e) { return DEFAULT_MESSAGE; }
    }

    /** Empty list = guard off; unset key = built-in defaults. */
    private List<String> blocked() {
        try {
            FileConfiguration cfg = plugin.getConfig();
            if (cfg.isSet("command-guard.commands")) {
                List<String> l = cfg.getStringList("command-guard.commands");
                if (l != null) return l;
            }
        } catch (Exception ignored) {}
        return DEFAULT_COMMANDS;
    }

    private void note(Player p, String entry) {
        try {
            UUID id = p.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastLogged.get(id);
            if (last != null && now - last < LOG_COOLDOWN_MS) return;
            lastLogged.put(id, now);
            plugin.getLogger().info("[CommandGuard] blocked /" + entry + " for " + p.getName());
        } catch (Exception ignored) {}
    }
}
