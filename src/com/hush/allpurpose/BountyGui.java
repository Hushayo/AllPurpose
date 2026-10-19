package com.hush.allpurpose;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Bounty screens. Two views:
 *
 *   MAIN - every player with a price on their head, biggest first. Click a
 *          head to open the add-money screen for that player.
 *   ADD  - pick how much to add (presets, +/- 100, "type it in chat"), CONFIRM
 *          escrows it through Vault and puts it on the head.
 */
public final class BountyGui {

    private BountyGui() {}

    public enum Screen { MAIN, ADD }

    public static final class Holder implements InventoryHolder {
        public final Screen screen;
        public final UUID viewer;
        public UUID target;
        public String targetName;
        public double amount;
        public Inventory inv;

        Holder(Screen screen, UUID viewer) {
            this.screen = screen;
            this.viewer = viewer;
        }

        @Override public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[] HEAD_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    // ---------- MAIN ----------

    public static void openMain(Player p, AllPurpose plugin) {
        Holder h = new Holder(Screen.MAIN, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 54, "§8§l» §4§lBOUNTIES §8§l«");
        h.inv = inv;
        renderMain(inv, h, p, plugin);
        p.openInventory(inv);
    }

    private static void renderMain(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        BountyManager m = plugin.getBounty();
        Menus.fill(inv, Menus.GRAY);
        double pool = 0;
        for (BountyManager.Bounty b : m.top()) pool += b.total();
        inv.setItem(4, Menus.item(Material.SKELETON_SKULL, "§6§lBounties",
                "§7Pot on offer: §a" + Menus.money(m.economy(), pool),
                "§7Hunted players: §f" + m.top().size(),
                "",
                "§7Kill a hunted player to claim",
                "§7their whole bounty."));
        inv.setItem(45, Menus.item(Material.EMERALD, "§a§lYour balance",
                "§a" + Menus.money(m.economy(), m.balance(p)),
                "",
                "§7Click to refresh the board."));
        inv.setItem(53, Menus.item(Material.BARRIER, "§cClose"));

        List<BountyManager.Bounty> list = m.top();
        if (list.isEmpty()) {
            inv.setItem(22, Menus.item(Material.LIGHT_GRAY_DYE, "§7No active bounties",
                    "§7Kill someone, then click their head here",
                    "§7to put a price on it."));
            return;
        }
        int n = Math.min(list.size(), HEAD_SLOTS.length);
        for (int i = 0; i < n; i++) {
            BountyManager.Bounty b = list.get(i);
            List<String> lore = new java.util.ArrayList<>();
            lore.add("§7Bounty: §a" + Menus.money(m.economy(), b.total()));
            lore.add("§7Hunter takes: §a" + Menus.money(m.economy(), b.total() * (1.0 - m.taxPercent() / 100.0)));
            lore.add("");
            int shown = 0;
            for (Map.Entry<UUID, Double> c : b.contributors.entrySet()) {
                if (shown++ >= 3) break;
                String name = null;
                try { name = Bukkit.getOfflinePlayer(c.getKey()).getName(); } catch (Exception ignored) {}
                lore.add("§8- §7" + (name == null ? "?" : name) + " §f" + Menus.plain(c.getValue()));
            }
            if (b.contributors.size() > 3) lore.add("§8  §7and " + (b.contributors.size() - 3) + " more...");
            lore.add("");
            lore.add(b.target.equals(p.getUniqueId()) ? "§cThis is you." : "§eClick to add to this bounty!");
            inv.setItem(HEAD_SLOTS[i], Menus.head(Bukkit.getOfflinePlayer(b.target), "§c☠ §f§l" + b.targetName, lore.toArray(new String[0])));
        }
        if (list.size() > HEAD_SLOTS.length) {
            inv.setItem(44, Menus.item(Material.HOPPER, "§7More hunted players: §f" + (list.size() - HEAD_SLOTS.length),
                    "§7The board lists the biggest bounties."));
        }
    }

    // ---------- ADD ----------

    public static void openAdd(Player p, AllPurpose plugin, UUID target, String targetName) {
        BountyManager m = plugin.getBounty();
        Holder h = new Holder(Screen.ADD, p.getUniqueId());
        h.target = target;
        h.targetName = targetName;
        h.amount = m.minBounty();
        Inventory inv = Bukkit.createInventory(h, 36, "§8§l» §4BOUNTY §8» §e" + shorten(targetName));
        h.inv = inv;
        renderAdd(inv, h, p, plugin);
        p.openInventory(inv);
    }

    private static void renderAdd(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        BountyManager m = plugin.getBounty();
        double bal = m.balance(p);
        double max = Math.max(m.minBounty(), Math.min(m.maxBounty(), bal));
        h.amount = Menus.clamp(Math.floor(h.amount), m.minBounty(), max);
        Menus.fill(inv, Menus.GRAY);

        double existing = m.amountOf(h.target);
        inv.setItem(4, Menus.head(Bukkit.getOfflinePlayer(h.target), "§c☠ §f§l" + h.targetName,
                "§7Current bounty: §a" + Menus.money(m.economy(), existing),
                "§7Adding now: §f" + Menus.money(m.economy(), h.amount),
                "§7New total: §a" + Menus.money(m.economy(), existing + h.amount),
                "",
                "§7Your balance: §a" + Menus.money(m.economy(), bal)));

        List<Double> pre = m.presets();
        for (int i = 0; i < pre.size() && i < 8; i++) {
            double v = pre.get(i);
            boolean ok = v <= bal && v >= m.minBounty() && v <= m.maxBounty();
            inv.setItem(9 + i, Menus.item(ok ? Material.GOLD_NUGGET : Material.GRAY_DYE,
                    (ok ? "§e" : "§8") + Menus.plain(v),
                    ok ? "§7Click to add " + Menus.money(m.economy(), v) : "§cNot affordable (or out of range)"));
        }
        inv.setItem(17, Menus.item(Material.NETHER_STAR, "§d§lALL IN §7(" + Menus.money(m.economy(), bal) + ")",
                "§7Put every coin you have on their head."));

        inv.setItem(20, Menus.item(Material.RED_DYE, "§c− §f" + Menus.plain(100),
                "§7Hold shift: §c− §f" + Menus.plain(1000)));
        inv.setItem(22, Menus.item(Material.NAME_TAG, "§e§lType a custom amount",
                "§7Click here, then type it in chat.",
                "§7Examples: §f999§7, §f1.5k§7, §f2m§7, §fcancel"));
        inv.setItem(24, Menus.item(Material.LIME_DYE, "§a+ §f" + Menus.plain(100),
                "§7Hold shift: §a+ §f" + Menus.plain(1000)));

        inv.setItem(29, Menus.item(Material.ARROW, "§7Back to the board"));
        inv.setItem(31, Menus.item(Material.LIME_CONCRETE, "§a§lPLACE §7- " + Menus.plain(h.amount),
                "§7Kill §f" + h.targetName + "§7 (or anyone who does)",
                "§7and they collect the whole pot."));
        inv.setItem(33, Menus.item(Material.BARRIER, "§cCancel"));
    }

    private static String shorten(String s) {
        if (s == null) return "?";
        String t = s.replaceAll("[^A-Za-z0-9_]", "");
        return t.length() <= 14 ? t : t.substring(0, 14);
    }

    // ---------- clicks ----------

    public static void handleClick(InventoryClickEvent e, AllPurpose plugin) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        Holder h = (Holder) holder;
        BountyManager m = plugin.getBounty();
        int slot = e.getRawSlot();
        switch (h.screen) {
            case MAIN: mainClick(p, h, m, plugin, slot); break;
            case ADD: addClick(p, h, m, plugin, slot, e.getClick()); break;
        }
    }

    private static void mainClick(Player p, Holder h, BountyManager m, AllPurpose plugin, int slot) {
        if (slot == 53) { p.closeInventory(); return; }
        if (slot == 45) { renderMain(h.inv, h, p, plugin); return; }
        int idx = headIndex(slot);
        if (idx < 0) return;
        List<BountyManager.Bounty> list = m.top();
        if (idx >= list.size()) { renderMain(h.inv, h, p, plugin); return; }
        BountyManager.Bounty b = list.get(idx);
        if (b.target.equals(p.getUniqueId())) return;
        openAdd(p, plugin, b.target, b.targetName);
    }

    private static void addClick(final Player p, final Holder h, final BountyManager m, final AllPurpose plugin,
                                 int slot, ClickType click) {
        if (slot == 33) { p.closeInventory(); return; }
        if (slot == 29) { p.closeInventory(); openMain(p, plugin); return; }
        if (slot == 31) {
            if (m.place(p, h.target, h.targetName, h.amount)) p.closeInventory();
            else renderAdd(h.inv, h, p, plugin);
            return;
        }
        if (slot == 17) {
            h.amount = m.balance(p);
            renderAdd(h.inv, h, p, plugin);
            return;
        }
        if (slot >= 9 && slot <= 16) {
            List<Double> pre = m.presets();
            int i = slot - 9;
            if (i < pre.size()) { h.amount = pre.get(i); renderAdd(h.inv, h, p, plugin); }
            return;
        }
        double step = click != null && click.isShiftClick() ? 1000.0 : 100.0;
        if (slot == 20) { h.amount -= step; renderAdd(h.inv, h, p, plugin); return; }
        if (slot == 24) { h.amount += step; renderAdd(h.inv, h, p, plugin); return; }
        if (slot == 22) {
            final UUID target = h.target;
            final String targetName = h.targetName;
            final double cap = Math.max(m.minBounty(), Math.min(m.maxBounty(), m.balance(p)));
            p.closeInventory();
            p.sendMessage("§6Type the bounty amount for §f" + targetName + " §6in chat §7(e.g. §f999§7, §f1.5k§7) §6or §fcancel§6.");
            Menus.prompt(p, m.minBounty(), cap, new Menus.InputHandler() {
                @Override public void accept(Player pl, double amount) {
                    m.place(pl, target, targetName, amount);
                }
            });
        }
    }

    private static int headIndex(int slot) {
        for (int i = 0; i < HEAD_SLOTS.length; i++) if (HEAD_SLOTS[i] == slot) return i;
        return -1;
    }
}
