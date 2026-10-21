package com.hush.allpurpose;

import java.util.Iterator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.inventory.ItemStack;

/**
 * The gestures and the guards.
 *
 * Gestures on a container:
 *   sneak + right-click -> set up / edit a shop there (opens the setup screen)
 *   plain right-click   -> trade at someone else's shop (opens the confirm GUI)
 *   plain right-click on your own -> the chest opens, exactly as vanilla
 *
 * A sneak-click that would place a block (or something placed onto a block face
 * - an item frame, a painting, an armour stand) always stays vanilla. Sneaking is
 * how you build on top of / against a container, so the shop screens must not
 * take that click - otherwise a shop chest can never be built around, and
 * nothing can be hung on a chest.
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

            // Sneak-clicking to build is vanilla block placement - the only way to
            // put a block on top of / against a container, and the only way to hang
            // an item frame, glow frame, painting or armour stand on one. Never
            // swallow that click, or a chest in a tight spot (where the chest is the
            // only surface to aim at) cannot be built on or decorated.
            if (a == Action.RIGHT_CLICK_BLOCK && isVanillaPlacement(p, e, b)) return;

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

    /**
     * True when a sneak-click is really the player placing something, not a shop
     * gesture: sneaking, a placeable item in hand, and somewhere legal to put it.
     *
     * Vanilla only needs the sneak on a container (otherwise it opens), and that
     * is exactly the click the shop screens otherwise take over - so both blocks
     * and the items vanilla turns into an entity on the clicked face (item frames,
     * paintings, armour stands) count as placement. The "somewhere legal" test
     * keeps the shop reachable whenever the placement could not have happened
     * anyway - e.g. the block above a chest is already taken in a cramped space,
     * or there is no free cell in front of the face, the click is ours again.
     */
    private static boolean isVanillaPlacement(Player p, PlayerInteractEvent e, Block clicked) {
        try {
            if (!p.isSneaking()) return false;
            ItemStack it = e.getItem();
            if (it == null) return false;
            Material type = it.getType();
            if (type == null || type == Material.AIR) return false;
            if (!type.isBlock() && !isFacePlacementItem(type)) return false;
            BlockFace face = e.getBlockFace();
            if (face == null) return false;
            return clicked.getRelative(face).isReplaceable();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Items that are not blocks but are still placed onto the clicked block face,
     * so vanilla needs the sneak on a container for them too. Without this an item
     * frame could never be hung on a chest - the shop gesture would eat the click.
     */
    private static boolean isFacePlacementItem(Material type) {
        switch (type) {
            case ITEM_FRAME:
            case GLOW_ITEM_FRAME:
            case PAINTING:
            case ARMOR_STAND:
                return true;
            default:
                return false;
        }
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
