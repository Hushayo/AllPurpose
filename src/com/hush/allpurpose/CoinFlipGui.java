package com.hush.allpurpose;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Every coinflip screen. Three views:
 *
 *   MAIN   - lobby: one head per open flip, click a head to take it, click your
 *            own head to cancel and get refunded.
 *   CREATE - wager picker: presets, +/- 100 (shift 1000), "type it in chat",
 *            then CREATE (escrows the wager) or Back to the lobby.
 *   SPIN   - the flip itself: the two heads spin in the centre slot, then the
 *            winner's head lands with a glint and the pot is paid.
 *
 * All screens are read-only; MenuListener forwards clicks here.
 */
public final class CoinFlipGui {

    private CoinFlipGui() {}

    public enum Screen { MAIN, CREATE, SPIN }

    public static final class Holder implements InventoryHolder {
        public final Screen screen;
        public final UUID viewer;
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

    /** Interior slots of the 54-slot lobby, in fill order. */
    private static final int[] FLIP_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    // ---------- MAIN ----------

    public static void openMain(Player p, AllPurpose plugin) {
        Holder h = new Holder(Screen.MAIN, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 54, "§8§l» §6§lCOINFLIP §8§l«");
        h.inv = inv;
        renderMain(inv, h, p, plugin);
        p.openInventory(inv);
    }

    private static void renderMain(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        CoinFlipManager m = plugin.getCoinflip();
        Menus.fill(inv, Menus.GRAY);
        inv.setItem(4, Menus.item(Material.SUNFLOWER, "§6§lCoinflip",
                "§7Balance: §a" + Menus.money(m.economy(), m.balance(p)),
                "§7Open flips: §f" + m.open().size(),
                "",
                "§7Wager against another player.",
                "§7Winner takes the pot."));
        inv.setItem(45, Menus.item(Material.EMERALD, "§a§lYour balance",
                "§a" + Menus.money(m.economy(), m.balance(p)),
                "",
                "§7Click to refresh the board."));
        inv.setItem(49, Menus.item(Material.GOLD_INGOT, "§a§lCREATE COINFLIP",
                "§7Pick a wager, then wait for someone",
                "§7to click your head here.",
                "",
                "§eClick to open the wager picker!"));
        inv.setItem(53, Menus.item(Material.BARRIER, "§cClose"));

        List<CoinFlipManager.Flip> open = m.open();
        if (open.isEmpty()) {
            inv.setItem(22, Menus.item(Material.LIGHT_GRAY_DYE, "§7No open coinflips",
                    "§7Be the first - click §fCREATE COINFLIP§7."));
            return;
        }
        int n = Math.min(open.size(), FLIP_SLOTS.length);
        for (int i = 0; i < n; i++) {
            CoinFlipManager.Flip f = open.get(i);
            long leftMs = Math.max(0L, m.expireSeconds() * 1000L - (System.currentTimeMillis() - f.at));
            long mins = leftMs / 60000L;
            boolean mine = f.creator.equals(p.getUniqueId());
            inv.setItem(FLIP_SLOTS[i], Menus.head(Bukkit.getOfflinePlayer(f.creator), "§e§l" + f.creatorName,
                    "§7Wager: §f" + Menus.money(m.economy(), f.wager),
                    "§7Winner gets: §a" + Menus.money(m.economy(), m.payout(f.wager)),
                    "§7Chance: §f50%",
                    "§7Expires in: §f" + mins + "m",
                    "",
                    mine ? "§cThis is yours - click to cancel + refund" : "§aClick to take the flip!"));
        }
        if (open.size() > FLIP_SLOTS.length) {
            inv.setItem(44, Menus.item(Material.HOPPER, "§7More flips: §f" + (open.size() - FLIP_SLOTS.length),
                    "§7The lobby lists the oldest flips."));
        }
    }

    // ---------- CREATE ----------

    public static void openCreate(Player p, AllPurpose plugin, double amount) {
        CoinFlipManager m = plugin.getCoinflip();
        Holder h = new Holder(Screen.CREATE, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 36, "§8§l» §6COINFLIP §8» §eWAGER");
        h.inv = inv;
        h.amount = amount <= 0 ? m.minWager() : amount;
        renderCreate(inv, h, p, plugin);
        p.openInventory(inv);
    }

    private static void renderCreate(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        CoinFlipManager m = plugin.getCoinflip();
        double bal = m.balance(p);
        double max = Math.max(m.minWager(), Math.min(m.maxWager(), bal));
        h.amount = Menus.clamp(Math.floor(h.amount), m.minWager(), max);
        Menus.fill(inv, Menus.GRAY);

        inv.setItem(4, Menus.item(Material.GOLD_INGOT, "§6§lWager: §f" + Menus.money(m.economy(), h.amount),
                "§7Balance: §a" + Menus.money(m.economy(), bal),
                "§7Winner gets: §a" + Menus.money(m.economy(), m.payout(h.amount)),
                "§7Your chance: §f50%"));

        List<Double> pre = m.presets();
        for (int i = 0; i < pre.size() && i < 8; i++) {
            double v = pre.get(i);
            boolean ok = v <= bal && v >= m.minWager() && v <= m.maxWager();
            inv.setItem(9 + i, Menus.item(ok ? Material.GOLD_NUGGET : Material.GRAY_DYE,
                    (ok ? "§e" : "§8") + Menus.plain(v),
                    ok ? "§7Click to wager " + Menus.money(m.economy(), v) : "§cNot affordable (or out of range)"));
        }
        inv.setItem(17, Menus.item(Material.NETHER_STAR, "§d§lALL IN §7(" + Menus.money(m.economy(), bal) + ")",
                "§7Wager every coin you have."));

        inv.setItem(20, Menus.item(Material.RED_DYE, "§c− §f" + Menus.plain(100),
                "§7Hold shift: §c− §f" + Menus.plain(1000)));
        inv.setItem(22, Menus.item(Material.NAME_TAG, "§e§lType a custom amount",
                "§7Click here, then type it in chat.",
                "§7Examples: §f999§7, §f1.5k§7, §f2m§7, §fcancel"));
        inv.setItem(24, Menus.item(Material.LIME_DYE, "§a+ §f" + Menus.plain(100),
                "§7Hold shift: §a+ §f" + Menus.plain(1000)));

        inv.setItem(29, Menus.item(Material.ARROW, "§7Back to the lobby"));
        inv.setItem(31, Menus.item(Material.LIME_CONCRETE, "§a§lCREATE §7- " + Menus.plain(h.amount),
                "§7Your wager is held until someone takes it.",
                "§7Cancel any time from the lobby."));
        inv.setItem(33, Menus.item(Material.BARRIER, "§cCancel"));
    }

    // ---------- SPIN ----------

    /** Participant header: head + wager. */
    public static ItemStack face(OfflinePlayer who, String name, double wager) {
        return Menus.head(who, name,
                "§7Wager: §f" + Menus.plain(wager));
    }

    /** Opens the flip screen for one viewer and returns its inventory. */
    public static Inventory openSpin(AllPurpose plugin, Player viewer, CoinFlipManager.Flip f, Player opponent) {
        CoinFlipManager m = plugin.getCoinflip();
        Holder h = new Holder(Screen.SPIN, viewer.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 27, "§8§l» §e§lFLIPPING §8§l«");
        h.inv = inv;
        Menus.fill(inv, Menus.BLANK);
        inv.setItem(4, Menus.item(Material.GOLD_INGOT, "§6§lPot: §f" + Menus.money(m.economy(), f.wager * 2.0),
                "§7" + f.creatorName + " §7vs §7" + (opponent == null ? "?" : opponent.getName())));
        inv.setItem(11, Menus.head(Bukkit.getOfflinePlayer(f.creator), "§e" + f.creatorName + " §8(creator)",
                "§7Wager: §f" + Menus.money(m.economy(), f.wager)));
        if (opponent != null) {
            inv.setItem(15, Menus.head(opponent, "§e" + opponent.getName() + " §8(challenger)",
                    "§7Wager: §f" + Menus.money(m.economy(), f.wager)));
        }
        inv.setItem(13, Menus.item(Material.GOLD_NUGGET, "§e§lFlipping..."));
        viewer.openInventory(inv);
        return inv;
    }

    public static void setCenter(List<Inventory> invs, ItemStack item) {
        if (invs == null || item == null) return;
        for (Inventory inv : invs) {
            try { if (inv != null) inv.setItem(13, item); } catch (Exception ignored) {}
        }
    }

    public static void showResult(List<Inventory> invs, ItemStack winnerHead, String winnerName, String won) {
        if (invs == null) return;
        for (Inventory inv : invs) {
            try {
                if (inv == null) continue;
                if (winnerHead != null) inv.setItem(13, Menus.glowing(winnerHead));
                inv.setItem(4, Menus.item(Material.GOLD_BLOCK, "§6§lPot paid out",
                        "§7" + winnerName + " takes §a" + won));
                inv.setItem(22, Menus.item(Material.EMERALD_BLOCK, "§a§lWINNER: §f§l" + winnerName,
                        "§7Collected §a" + won));
            } catch (Exception ignored) {}
        }
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
        CoinFlipManager m = plugin.getCoinflip();
        int slot = e.getRawSlot();
        switch (h.screen) {
            case MAIN: mainClick(p, h, m, plugin, slot); break;
            case CREATE: createClick(p, h, m, plugin, slot, e.getClick()); break;
            case SPIN: break;
        }
    }

    private static void mainClick(Player p, Holder h, CoinFlipManager m, AllPurpose plugin, int slot) {
        if (slot == 53) { p.closeInventory(); return; }
        if (slot == 49) { openCreate(p, plugin, m.minWager()); return; }
        if (slot == 45) { renderMain(h.inv, h, p, plugin); return; }
        int idx = flipIndex(slot);
        if (idx < 0) return;
        List<CoinFlipManager.Flip> open = m.open();
        if (idx >= open.size()) { renderMain(h.inv, h, p, plugin); return; }
        m.join(p, open.get(idx));
        // A real join replaces this view with the flip screen; anything else
        // (own flip cancelled, not enough money) refreshes the lobby.
        try {
            if (p.getOpenInventory() != null && p.getOpenInventory().getTopInventory().equals(h.inv)) {
                renderMain(h.inv, h, p, plugin);
            }
        } catch (Exception ignored) {}
    }

    private static void createClick(final Player p, final Holder h, final CoinFlipManager m, final AllPurpose plugin,
                                    int slot, ClickType click) {
        if (slot == 33) { p.closeInventory(); return; }
        if (slot == 29) { p.closeInventory(); openMain(p, plugin); return; }
        if (slot == 31) {
            if (m.create(p, h.amount)) p.closeInventory();
            else renderCreate(h.inv, h, p, plugin);
            return;
        }
        if (slot == 17) {
            h.amount = m.balance(p);
            renderCreate(h.inv, h, p, plugin);
            return;
        }
        if (slot >= 9 && slot <= 16) {
            List<Double> pre = m.presets();
            int i = slot - 9;
            if (i < pre.size()) { h.amount = pre.get(i); renderCreate(h.inv, h, p, plugin); }
            return;
        }
        double step = click != null && click.isShiftClick() ? 1000.0 : 100.0;
        if (slot == 20) { h.amount -= step; renderCreate(h.inv, h, p, plugin); return; }
        if (slot == 24) { h.amount += step; renderCreate(h.inv, h, p, plugin); return; }
        if (slot == 22) {
            final double cap = Math.max(m.minWager(), Math.min(m.maxWager(), m.balance(p)));
            p.closeInventory();
            p.sendMessage("§6Type your wager in chat §7(e.g. §f999§7, §f1.5k§7) §6or §fcancel§6.");
            Menus.prompt(p, m.minWager(), cap, new Menus.InputHandler() {
                @Override public void accept(Player pl, double amount) {
                    m.create(pl, amount);
                }
            });
        }
    }

    private static int flipIndex(int slot) {
        for (int i = 0; i < FLIP_SLOTS.length; i++) if (FLIP_SLOTS[i] == slot) return i;
        return -1;
    }
}
