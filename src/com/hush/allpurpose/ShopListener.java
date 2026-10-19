package com.hush.allpurpose;

import java.util.Iterator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * The gestures and the guards.
 *
 * Gestures on a container:
 *   sneak + right-click -> set up / edit a shop there (opens the setup screen)
 *   plain right-click   -> trade at someone else's shop (opens the confirm GUI)
 *   plain right-click on your own -> the chest opens, exactly as vanilla
 *
 * Guards: nobody but the owner (or staff) can open, break, blow up, push or
 * hopper-drain a shop's container.
 */
public class ShopListener implements Listener {

    private final AllPurpose plugin;

    public ShopListener(AllPurpose plugin) {
        this.plugin = plugin;
    }

    // ---------- gestures ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        try {
            Action a = e.getAction();
            if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
            if (e.getHand() != EquipmentSlot.HAND) return;
            Block b = e.getClickedBlock();
            if (b == null) return;
            Player p = e.getPlayer();
            ShopManager m = plugin.getShop();
            ShopManager.Shop s = m.at(b);
            boolean otherPluginDenied = e.isCancelled();   // e.g. a WorldGuard region denial

            if (a == Action.LEFT_CLICK_BLOCK) {
                if (s != null && !mayManage(p, s)) {
                    e.setCancelled(true);
                    p.sendMessage("§cThat is " + s.ownerName + "'s shop.");
                }
                return;
            }

            boolean container = ShopManager.isAllowedContainer(b);
            if (s == null && !container) return;

            if (s != null && mayManage(p, s)) {
                if (!p.isSneaking()) return;               // owner opens their own chest normally
                deny(e);
                if (!m.enabled()) { p.sendMessage("§cShops are disabled."); return; }
                if (m.begin(p, b) == null) { p.sendMessage("§cYou cannot manage that shop."); return; }
                openSetupLater(p);
                return;
            }

            if (s != null) {                               // someone else's shop
                deny(e);
                if (!m.enabled()) { p.sendMessage("§cShops are disabled."); return; }
                if (!p.hasPermission("allpurpose.shop")) { p.sendMessage("§cNo permission (allpurpose.shop)."); return; }
                openConfirmLater(p, s.key());
                return;
            }

            // A plain container: only the sneak gesture is ours.
            if (!p.isSneaking()) return;
            deny(e);
            if (otherPluginDenied) { p.sendMessage("§cYou cannot build a shop here."); return; }
            if (!m.enabled()) { p.sendMessage("§cShops are disabled."); return; }
            if (!p.hasPermission("allpurpose.shop")) { p.sendMessage("§cNo permission (allpurpose.shop)."); return; }
            if (m.begin(p, b) == null) { p.sendMessage("§cYou cannot set up a shop here."); return; }
            openSetupLater(p);
        } catch (Exception ignored) {}
    }

    /** Opens the setup screen once the interaction has settled. */
    private void openSetupLater(final Player p) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { try { ShopGui.openSetup(p, plugin, false); } catch (Exception ignored) {} }
        });
    }

    /** Opens the trade screen for a specific shop once the interaction has settled. */
    private void openConfirmLater(final Player p, final String shopKey) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { try { ShopGui.openConfirm(p, plugin, shopKey); } catch (Exception ignored) {} }
        });
    }

    private static void deny(PlayerInteractEvent e) {
        try {
            e.setCancelled(true);
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        } catch (Exception ignored) {}
    }

    private static boolean mayManage(Player p, ShopManager.Shop s) {
        return s.isOwner(p.getUniqueId()) || p.hasPermission("allpurpose.shop.admin");
    }

    // ---------- protection ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        try {
            ShopManager m = plugin.getShop();
            ShopManager.Shop s = m.at(e.getBlock());
            if (s == null) return;
            if (!mayManage(e.getPlayer(), s)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage("§cThat is " + s.ownerName + "'s shop.");
                return;
            }
            m.remove(s);                              // owner dismantling their own stall
            e.getPlayer().sendMessage("§7Shop removed.");
        } catch (Exception ignored) {}
    }

    /** Nobody opens a shop chest except its owner (or staff). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        try {
            InventoryHolder h = e.getInventory().getHolder();
            if (!(h instanceof BlockInventoryHolder)) return;
            Block b = ((BlockInventoryHolder) h).getBlock();
            ShopManager.Shop s = plugin.getShop().at(b);
            if (s == null) return;
            if (!(e.getPlayer() instanceof Player)) return;
            Player p = (Player) e.getPlayer();
            if (mayManage(p, s)) return;
            e.setCancelled(true);
            p.sendMessage("§cThat is " + s.ownerName + "'s shop - right-click it to trade.");
        } catch (Exception ignored) {}
    }

    /** Hoppers and the like cannot drain or fill a shop chest by default. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        try {
            if (plugin.getShop().allowAutomation()) return;
            if (isShopInv(e.getSource()) || isShopInv(e.getDestination())) e.setCancelled(true);
        } catch (Exception ignored) {}
    }

    private boolean isShopInv(Inventory inv) {
        try {
            InventoryHolder h = inv == null ? null : inv.getHolder();
            if (h instanceof BlockInventoryHolder) {
                return plugin.getShop().at(((BlockInventoryHolder) h).getBlock()) != null;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.getShop().protectShops()) return;
        strip(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!plugin.getShop().protectShops()) return;
        strip(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (plugin.getShop().protectShops()) strip(e.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (plugin.getShop().protectShops()) strip(e.getBlocks());
    }

    /** Drops shop containers out of an explosion / piston block list. */
    private void strip(List<Block> blocks) {
        try {
            if (blocks == null) return;
            Iterator<Block> it = blocks.iterator();
            while (it.hasNext()) {
                Block b = it.next();
                if (b == null) continue;
                if (plugin.getShop().at(b) != null) it.remove();
            }
        } catch (Exception ignored) {}
    }

    // ---------- cleanup ----------

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        try { ShopGui.handleClose(e, plugin); } catch (Exception ignored) {}
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        try { plugin.getShop().abandonDraft(e.getPlayer()); } catch (Exception ignored) {}
    }
}
