package com.hush.allpurpose;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Event glue for the combat tag (phase 1), combat-log detection (phase 2) and
 * the anti-abuse rules (phase 3). All policy lives in {@link CombatManager}.
 */
public class CombatListener implements Listener {

    private final AllPurpose plugin;
    private final CombatManager combat;

    public CombatListener(AllPurpose plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        combat.handleDamage(e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getEntity() != null) combat.untag(e.getEntity().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        combat.handleQuit(e.getPlayer(), e.getReason());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!combat.shameEnabled()) return;
        int offence = combat.consumePending(p.getUniqueId());
        if (offence <= 0) return;
        // Wait one second so login plugins have finished moving the player.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) combat.getCeremony().start(p, offence, false);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (combat.getCeremony().isInCeremony(p.getUniqueId())
                && !p.hasPermission("allpurpose.shame.admin")) {
            e.setCancelled(true);
            p.sendMessage(Component.text("You cannot run commands during the shame ceremony.", NamedTextColor.RED));
            return;
        }
        if (!combat.isTagged(p.getUniqueId())) return;
        if (p.hasPermission(combat.bypassPermission())) return;
        String raw = e.getMessage();
        if (raw == null) return;
        String base = raw.startsWith("/") ? raw.substring(1) : raw;
        int space = base.indexOf(' ');
        if (space >= 0) base = base.substring(0, space);
        base = base.toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(colon + 1);
        if (!combat.isBlockedCommand(base)) return;
        e.setCancelled(true);
        p.sendMessage(Component.text("You cannot use /" + base + " while in combat.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent e) {
        if (e.getEntity() == null || !combat.blockPearls()) return;
        if (!(e.getEntity() instanceof EnderPearl)) return;
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        if (!combat.isTagged(p.getUniqueId())) return;
        if (p.hasPermission(combat.bypassPermission())) return;
        e.setCancelled(true);
        p.sendMessage(Component.text("You cannot throw ender pearls while in combat.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!combat.blockEnderChests()) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        if (e.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (!combat.isTagged(p.getUniqueId())) return;
        if (p.hasPermission(combat.bypassPermission())) return;
        e.setCancelled(true);
        p.sendMessage(Component.text("You cannot open an ender chest while in combat.", NamedTextColor.RED));
    }
}
