package com.hush.allpurpose;

import java.util.UUID;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Routes menu clicks to the coinflip / bounty screens, swallows the chat line
 * while a "type the amount" prompt is open, and drops stale prompt state when
 * a player disconnects.
 */
public class MenuListener implements Listener {

    private final AllPurpose plugin;

    public MenuListener(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        try { CoinFlipGui.handleClick(e, plugin); } catch (Exception ignored) {}
        try { BountyGui.handleClick(e, plugin); } catch (Exception ignored) {}
        try { ShopGui.handleClick(e, plugin); } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        try {
            InventoryHolder h = e.getView().getTopInventory().getHolder();
            if (h instanceof CoinFlipGui.Holder || h instanceof BountyGui.Holder || h instanceof ShopGui.Holder) e.setCancelled(true);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent e) {
        try {
            Player p = e.getPlayer();
            if (p == null) return;
            UUID id = p.getUniqueId();
            if (!Menus.awaiting(id)) return;
            String text = PlainTextComponentSerializer.plainText().serialize(e.message());
            e.setCancelled(true);
            Menus.consume(p, text);
        } catch (Exception ignored) {}
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        try { Menus.clear(e.getPlayer().getUniqueId()); } catch (Exception ignored) {}
    }
}
