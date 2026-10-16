package com.hush.allpurpose;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * RANKS-ONLY listener. Everything PvP (combat, arena, duels, anticheat,
 * combat-tag, jail, bots, deaths feed) is stripped: vanilla behavior.
 * Kept: rank assignment, rank chat/nametag, silent kill counting behind the
 * rank ladder, /tp-by-rank, LP parent-set guard.
 */
public class PlayerListener implements Listener {

    private final AllPurpose plugin;

    public PlayerListener(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onHealthChange(EntityDamageEvent e) {
        try { plugin.getNametag().repaintTag(e.getEntity()); } catch (Exception ignored) {}
    }

    @EventHandler
    public void onHealthRegain(EntityRegainHealthEvent e) {
        try { plugin.getNametag().repaintTag(e.getEntity()); } catch (Exception ignored) {}
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        try { if (plugin.getLpExpiry() != null) plugin.getLpExpiry().handleJoin(p); } catch (Exception ignored) {}
        try { plugin.getNametag().noteJoin(p); } catch (Exception ignored) {}
        try { plugin.getRanks().noteName(p); } catch (Exception ignored) {}
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                try { plugin.getRanks().autoRank(p); } catch (Exception ignored) {}
                try { plugin.getNametag().applyRank(p); } catch (Exception ignored) {}
                try { plugin.getNametag().spawnTagHolo(p); } catch (Exception ignored) {}
                try { plugin.getLeaderboard().refreshLbName(p); } catch (Exception ignored) {}
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override public void run() {
                        if (p.isOnline()) {
                            try { plugin.getNametag().applyRank(p); } catch (Exception ignored) {}
                            try { if (plugin.getNametag().useHoloLine()) plugin.getNametag().spawnTagHolo(p); } catch (Exception ignored) {}
                        }
                    }
                }, 60L);
            }
        }, 40L);
    }

    @EventHandler
    public void onQuitCleanup(PlayerQuitEvent e) {
        try { if (plugin.getLpExpiry() != null) plugin.getLpExpiry().handleQuit(e.getPlayer()); } catch (Exception ignored) {}
        try { plugin.getNametag().noteQuit(e.getPlayer()); } catch (Exception ignored) {}
        try { plugin.getNametag().removeTagHolo(e.getPlayer()); } catch (Exception ignored) {}
        try { plugin.getNametag().invalidateTag(e.getPlayer()); } catch (Exception ignored) {}
    }

    @EventHandler
    public void onDeathTag(EntityDeathEvent e) {
        try {
            if (e.getEntity() instanceof Player) plugin.getNametag().removeTagHolo((Player) e.getEntity());
        } catch (Exception ignored) {}
    }

    @EventHandler
    public void onRespawnTag(PlayerRespawnEvent e) {
        try {
            if (!plugin.getNametag().useHoloLine()) return;
            final Player p = e.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() { if (p.isOnline()) plugin.getNametag().spawnTagHolo(p); }
            }, 2L);
        } catch (Exception ignored) {}
    }

    // Anti-spam: per-player chat cooldown (default 5s).
    private final java.util.Map<UUID, Long> lastChatAt = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatCooldown(AsyncChatEvent e) {
        try {
            int cd;
            try { cd = plugin.getConfig().getInt("chatmod-cooldown-seconds", 5); }
            catch (Exception ex) { cd = 5; }
            if (cd <= 0) return;
            org.bukkit.entity.Player p = e.getPlayer();
            if (p == null) return;
            try { if (p.isOp() || p.hasPermission("rank.admin")) return; } catch (Exception ignored) {}
            UUID id;
            try { id = p.getUniqueId(); } catch (Exception ex) { return; }
            long now = System.currentTimeMillis();
            Long last = lastChatAt.get(id);
            if (last != null && now - last < (long) cd * 1000L) {
                e.setCancelled(true);
                long left = ((long) cd * 1000L - (now - last) + 999L) / 1000L;
                String warn = plugin.getConfig().getString("chatmod-cooldown-message", "&cSlow down! Wait {s}s.");
                if (warn == null || warn.isEmpty()) warn = "&cSlow down! Wait {s}s.";
                try { p.sendMessage(TextUtil.legacy(warn.replace("{s}", String.valueOf(left)))); }
                catch (Exception ignored) {}
                return;
            }
            lastChatAt.put(id, now);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatCooldownQuit(PlayerQuitEvent e) {
        try { lastChatAt.remove(e.getPlayer().getUniqueId()); } catch (Exception ignored) {}
    }

    // Chat keeps the "[RANK] Name: message" renderer. Vanilla when unranked.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        final String raw;
        try {
            raw = plugin.getRanks().fullTagRaw(e.getPlayer());
        } catch (Exception ex) { return; }
        if (raw == null || raw.isEmpty()) return;
        e.renderer(new io.papermc.paper.chat.ChatRenderer() {
            @Override
            public Component render(Player source, Component sourceDisplayName, Component message, net.kyori.adventure.audience.Audience viewer) {
                String r = raw;
                try {
                    String live = plugin.getRanks().fullTagRaw(source);
                    if (live != null && !live.isEmpty()) r = live;
                } catch (Exception ignored) {}
                String nm;
                try { nm = source.getName(); } catch (Exception ignored) { nm = "?"; }
                return TextUtil.prefixComponent(r).append(Component.text(nm)).append(Component.text(": ")).append(message);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { if (p.isOnline()) { try { plugin.getNametag().applyRank(p); } catch (Exception ignored) {} } }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTpRank(PlayerCommandPreprocessEvent e) {
        // /tp <rank> teleports you to an online holder of that rank.
        // Vanilla /tp <player> is untouched: player names always win.
        try {
            String msg = e.getMessage().trim();
            if (msg.startsWith("/")) msg = msg.substring(1);
            String[] parts = msg.split("\\s+");
            if (parts.length != 2) return;
            String base = parts[0].toLowerCase(Locale.ROOT);
            int colon = base.indexOf(':');
            if (colon >= 0) base = base.substring(colon + 1);
            if (!base.equals("tp")) return;
            if (plugin.getRanks().teleportToRank(e.getPlayer(), parts[1])) e.setCancelled(true);
        } catch (Exception ignored) {}
    }

    // LuckPerms `parent set` guard (warn-only, never blocks): `parent set`
    // REPLACES every parent group, silently stripping `default`. Our ranks
    // only ever use additive `parent add` + `parent remove <synced-group`.
    private final java.util.Map<String, Long> lpGuardAt = new java.util.HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLpParentGuardPlayer(PlayerCommandPreprocessEvent e) {
        try {
            Player sender;
            try { sender = e.getPlayer(); } catch (Exception ex) { return; }
            if (sender == null) return;
            lpParentGuard(sender.getName(), e.getMessage(), false);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLpParentGuardConsole(org.bukkit.event.server.ServerCommandEvent e) {
        try {
            String cmd;
            try { cmd = e.getCommand(); } catch (Exception ex) { return; }
            lpParentGuard("console", cmd, true);
        } catch (Exception ignored) {}
    }

    private void lpParentGuard(String senderKey, String raw, boolean fromConsole) {
        try {
            boolean on;
            try { on = plugin.getConfig().getBoolean("lp-parent-guard-enabled", true); }
            catch (Exception ex) { on = true; }
            if (!on) return;
            String[] hit = lpParentDanger(raw);
            if (hit == null) return;
            String kind = hit[0];
            String target = hit[1];
            String extra = hit[2];
            long now = System.currentTimeMillis();
            Long last;
            try { last = lpGuardAt.get(senderKey == null ? "?" : senderKey.toLowerCase(Locale.ROOT)); }
            catch (Exception ex) { last = null; }
            if (last != null && now - last < 20000L) return;
            try { lpGuardAt.put(senderKey == null ? "?" : senderKey.toLowerCase(Locale.ROOT), now); } catch (Exception ignored) {}
            String warn;
            if (kind.equals("set")) {
                warn = "§c[LP Guard] 'parent set' REPLACES all groups - " + target + " loses 'default' (warp/kits). "
                        + "Use §f/lp user " + target + " parent add " + extra + "§c instead. "
                        + "Repair: §f/lp user " + target + " parent add default";
            } else if (kind.equals("clear")) {
                warn = "§c[LP Guard] 'parent clear' removes EVERY group from " + target + ", including 'default' (warp/kits). "
                        + "Repair: §f/lp user " + target + " parent add default";
            } else {
                warn = "§c[LP Guard] removing 'default' from " + target + " takes away warp/kits/msg. "
                        + "To change rank, use §f/lp user " + target + " parent add <group>§c instead.";
            }
            if (!fromConsole) {
                try {
                    Player sender = Bukkit.getPlayerExact(senderKey);
                    if (sender != null && sender.isOnline()) sender.sendMessage(warn);
                } catch (Exception ignored) {}
            }
            try { plugin.getLogger().warning("[LP Guard] " + senderKey + ": " + raw.trim() + " -> warned (" + kind + " " + target + ")"); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private String[] lpParentDanger(String raw) {
        try {
            if (raw == null) return null;
            String msg = raw.trim();
            while (msg.startsWith("/")) msg = msg.substring(1).trim();
            if (msg.isEmpty()) return null;
            String[] t = msg.split("\\s+");
            if (t.length < 5) return null;
            String base = t[0].toLowerCase(Locale.ROOT);
            int colon = base.indexOf(':');
            if (colon >= 0) base = base.substring(colon + 1);
            if (!base.equals("lp") && !base.equals("luckperms")) return null;
            if (!t[1].equalsIgnoreCase("user")) return null;
            if (!t[3].equalsIgnoreCase("parent")) return null;
            String target = t[2];
            if (target == null || target.isEmpty()) return null;
            String action = t[4].toLowerCase(Locale.ROOT);
            if (action.equals("set")) {
                String group = t.length > 5 ? t[5] : "<group>";
                return new String[]{"set", target, group};
            }
            if (action.equals("clear")) return new String[]{"clear", target, ""};
            if (action.equals("remove") && t.length > 5 && t[5].equalsIgnoreCase("default")) {
                return new String[]{"removedefault", target, ""};
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInactiveMove(PlayerMoveEvent e) {
        try { plugin.getNametag().noteMove(e); } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDeath(PlayerDeathEvent e) {
        // Silent kill counting behind the rank ladder only. No death feed,
        // no kill effects (killfx disabled), vanilla death message stays.
        try { plugin.getLeaderboard().handlePvpDeath(e); } catch (Exception ignored) {}
    }
}
