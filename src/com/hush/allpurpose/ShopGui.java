package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Shop screens.
 *
 *   SETUP   - reached by sneaking + right-clicking a container. Slot 11 is a
 *             real, interactive slot: whatever the player drops there becomes
 *             the traded item (its stack size seeds the quantity). A decorative
 *             glazed-terracotta arrow sits beside it, pointing at it. Nothing in
 *             this GUI is ever handed out except that same stack back to its
 *             owner, so the screen cannot duplicate items.
 *   PRICE   - the price tab, reached by clicking the gold nugget in SETUP. The
 *             preset prices live here (they used to be gold blocks crowding the
 *             setup screen) plus a "type your own" option and a back arrow.
 *   CONFIRM - reached by right-clicking someone else's shop. One click trades.
 *   LIST    - /shop: every shop, newest page; your own entries open SETUP.
 */
public final class ShopGui {

    private ShopGui() {}

    public enum Screen { SETUP, PRICE, CONFIRM, LIST }

    public static final class Holder implements InventoryHolder {
        public final Screen screen;
        public final UUID viewer;
        public String shopKey;
        public int page;
        public boolean mineOnly;
        /** Set while we swap this screen for another one ourselves, so the close
         *  hook knows the close was intentional and must not "go back". */
        public boolean transitioning;
        public Inventory inv;

        Holder(Screen screen, UUID viewer) {
            this.screen = screen;
            this.viewer = viewer;
        }

        @Override public Inventory getInventory() { return inv; }
    }

    // SETUP slots
    static final int SAMPLE = 11;      // the hollow slot: drop the traded item here
    static final int POINTER = 10;     // decorative arrow, points at the hollow slot
    static final int MODE = 13;
    static final int PRICE = 15;
    static final int CANCEL = 18;
    static final int QTY_MINUS = 19;
    static final int QTY_PLUS = 21;
    static final int QTY_INFO = 22;
    static final int CONFIRM_BTN = 26;

    // PRICE screen layout (opened by the gold nugget)
    static final int P_BACK = 18;
    static final int P_CUSTOM = 22;
    static final int[] P_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};

    // CONFIRM slots
    static final int C_INFO = 11;
    static final int C_ITEM = 13;
    static final int C_STATUS = 15;
    static final int C_OK = 22;
    static final int C_CANCEL = 26;

    // LIST layout
    static final int L_HEADER = 4;
    static final int L_PREV = 45;
    static final int L_FILTER = 49;
    static final int L_NEXT = 53;
    static final int L_CLOSE = 8;
    static final int[] L_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    // ---------- SETUP ----------

    /** Opens (or re-opens) the setup screen for the player's current draft. */
    public static void openSetup(Player p, AllPurpose plugin, boolean restoreSample) {
        ShopManager m = plugin.getShop();
        ShopManager.Draft d = m.draft(p.getUniqueId());
        if (d == null) {
            p.sendMessage("§cThat shop setup expired - sneak-right-click the chest again.");
            return;
        }
        Holder h = new Holder(Screen.SETUP, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 27, "§8§l» §6§lSHOP §8» §7" + (d.editingKey == null ? "new" : "edit"));
        h.inv = inv;
        if (restoreSample && d.sampleStack != null) {
            inv.setItem(SAMPLE, d.sampleStack);
            d.sampleStack = null;
        }
        renderSetup(inv, h, p, plugin);
        p.openInventory(inv);
    }

    private static void renderSetup(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        ShopManager m = plugin.getShop();
        ShopManager.Draft d = m.draft(p.getUniqueId());
        if (d == null) return;
        // Fill everything EXCEPT the sample slot: it holds the player's own stack
        // and must survive every re-render of this screen.
        for (int i = 0; i < inv.getSize(); i++) if (i != SAMPLE) inv.setItem(i, Menus.GRAY);

        // Decorative pointer: it sits immediately left of the hollow slot and its
        // baked-in arrow pattern faces right, straight at the drop slot.
        inv.setItem(POINTER, Menus.item(Material.MAGENTA_GLAZED_TERRACOTTA,
                "§d§lPut the item here §7→",
                "§7Drop the item you want to trade into",
                "§7the empty slot to the §fright§7.",
                "",
                "§7The amount you drop becomes the",
                "§7quantity traded per §fprice§7."));

        inv.setItem(4, Menus.item(Material.CHEST, "§6§lSet up your shop",
                "§7Drop the item you want to trade into",
                "§7the empty slot marked by the §darrow§7.",
                "",
                "§7The amount you drop becomes the",
                "§7quantity traded per §fprice§7.",
                "",
                "§7Reopen later: sneak + right-click the chest."));

        boolean sell = d.mode.isSell();
        inv.setItem(MODE, Menus.item(sell ? Material.EMERALD : Material.GOLD_INGOT,
                sell ? "§a§lMode: §fSELLING" : "§6§lMode: §fBUYING",
                sell ? "§7Players pay §f" + m.money(d.price) + "§7 and take"
                     : "§7Players hand you the items and get",
                sell ? "§f" + d.qty + "x " + ShopManager.prettyName(d.item) + " §7from your chest."
                     : "§f" + m.money(d.price) + " §7from your balance.",
                "",
                sell ? "§7Keep the chest stocked." : "§7Keep money in your balance.",
                "",
                "§eClick to switch to " + (sell ? "BUYING" : "SELLING") + "§e."));

        inv.setItem(PRICE, Menus.item(Material.GOLD_NUGGET,
                "§e§lPrice: §f" + (d.price > 0 ? m.money(d.price) : "not set"),
                "§7What one trade costs §7(money).",
                "",
                "§eClick to pick a preset price",
                "§7(you can type your own there too)."));

        inv.setItem(QTY_MINUS, Menus.item(Material.RED_DYE, "§c− §f1",
                "§7Quantity per trade: §f" + d.qty,
                "§7Hold shift for §f−10"));
        inv.setItem(QTY_PLUS, Menus.item(Material.LIME_DYE, "§a+ §f1",
                "§7Quantity per trade: §f" + d.qty,
                "§7Hold shift for §f+10"));
        inv.setItem(QTY_INFO, Menus.item(Material.PAPER, "§fTrade: §e" + d.qty + "x " + ShopManager.prettyName(d.item),
                "§7Item: §f" + ShopManager.prettyName(d.item),
                "§7Quantity: §f" + d.qty,
                "§7Price: §f" + (d.price > 0 ? m.money(d.price) : "not set"),
                "",
                sell ? "§7Buyers pay §f" + (d.price > 0 ? m.money(d.price) : "?")
                     : "§7Sellers earn §f" + (d.price > 0 ? m.money(d.price) : "?")));

        inv.setItem(CANCEL, Menus.item(Material.BARRIER, "§cCancel"));
        boolean ready = d.item != null && d.item.getType() != Material.AIR && d.price >= m.minPrice();
        inv.setItem(CONFIRM_BTN, Menus.item(ready ? Material.LIME_CONCRETE : Material.LIGHT_GRAY_CONCRETE,
                (ready ? "§a§l" : "§8§l") + (d.editingKey == null ? "CREATE SHOP" : "SAVE SHOP"),
                ready ? "§7Click to save this shop." : "§7Place an item and set a price first."));
    }

    /** Reads slot 11 back into the draft (only when it actually changed). */
    private static void syncSample(Player p, Holder h, AllPurpose plugin) {
        ShopManager m = plugin.getShop();
        ShopManager.Draft d = m.draft(p.getUniqueId());
        if (d == null) return;
        ItemStack inSlot = h.inv.getItem(SAMPLE);
        if (inSlot == null || inSlot.getType() == Material.AIR) { renderSetup(h.inv, h, p, plugin); return; }
        String sig = sig(inSlot);
        if (!sig.equals(d.sampleSig)) {
            d.sampleSig = sig;
            d.item = ShopManager.template(inSlot);
            d.qty = (int) Menus.clamp(inSlot.getAmount(), 1, ShopManager.MAX_QTY);
        }
        // Do NOT park the stack in the draft: it stays in the GUI slot and
        // handleClose returns whatever is in the slot. Parking a live reference
        // here made close hand the same stack back twice (shop-item dupe).
        renderSetup(h.inv, h, p, plugin);
    }

    /**
     * Price prompt. Called from the PRICE screen, whose sample stack is already
     * parked in the draft (the SETUP -> PRICE swap parked it), so this only has
     * to close the tab and listen for the typed amount.
     */
    private static void promptPrice(final Player p, final Holder h, final ShopManager m, final AllPurpose plugin) {
        final ShopManager.Draft d = m.draft(p.getUniqueId());
        if (d == null) { p.closeInventory(); return; }
        p.closeInventory();
        p.sendMessage("§6Type the shop price in chat §7(e.g. §f100§7, §f2.5k§7, §f1m§7) §6or §fcancel§6.");
        Menus.prompt(p, m.minPrice(), m.maxPrice(), new Menus.InputHandler() {
            @Override public void accept(Player pl, double amount) {
                ShopManager.Draft dd = m.draft(pl.getUniqueId());
                if (dd == null) return;
                dd.price = amount;
                openSetup(pl, plugin, true);
            }
        });
    }

    private static void confirmSetup(Player p, Holder h, ShopManager m, AllPurpose plugin) {
        ShopManager.Draft d = m.draft(p.getUniqueId());
        if (d == null) { p.closeInventory(); return; }
        ItemStack inSlot = h.inv.getItem(SAMPLE);
        if (inSlot != null && inSlot.getType() != Material.AIR) {
            d.item = ShopManager.template(inSlot);
            // The stack stays in the slot; close returns it. Do not also park it.
        }
        if (d.item == null || d.item.getType() == Material.AIR) {
            p.sendMessage("§cDrop the item you want to trade into the empty slot (marked by the arrow) first.");
            return;
        }
        if (d.price < m.minPrice()) {
            p.sendMessage("§cSet a price first §7(click the §fPrice §7item).");
            return;
        }
        ItemStack trade = ShopManager.quantityTemplate(d.item);
        trade.setAmount((int) Menus.clamp(d.qty, 1, ShopManager.MAX_QTY));
        ShopManager.Result r = m.create(p, d.block, trade, d.mode, d.price);
        p.sendMessage((r.ok ? "§a" : "§c") + r.msg);
        if (r.ok) p.closeInventory(); else renderSetup(h.inv, h, p, plugin);
    }

    // ---------- PRICE ----------

    /**
     * Opens the price tab from the gold nugget. The draft, and the stack sitting
     * in the setup screen's drop slot, must survive the swap: setting
     * {@code prompting} first makes the SETUP close hook park that stack in the
     * draft instead of handing it back, exactly like the chat prompt used to.
     */
    public static void openPrice(Player p, AllPurpose plugin) {
        ShopManager m = plugin.getShop();
        ShopManager.Draft d = m.draft(p.getUniqueId());
        if (d == null) {
            p.sendMessage("§cThat shop setup expired - sneak-right-click the chest again.");
            return;
        }
        d.prompting = true;          // SETUP close hook parks the sample, keeps the draft
        Holder h = new Holder(Screen.PRICE, p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 27, "§8§l» §6§lSHOP §8» §7price");
        h.inv = inv;
        p.openInventory(inv);
        renderPrice(inv, h, p, plugin);
    }

    private static void renderPrice(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        ShopManager m = plugin.getShop();
        ShopManager.Draft d = m.draft(p.getUniqueId());
        Menus.fill(inv, Menus.GRAY);
        double cur = d == null ? 0.0 : d.price;

        inv.setItem(4, Menus.item(Material.GOLD_NUGGET, "§6§lSet the price",
                "§7One trade costs the buyer this much;",
                "§7in a BUYING shop the owner pays it.",
                "",
                "§7Currently: " + (cur > 0 ? "§f" + m.money(cur) : "§7not set")));

        List<Double> pre = m.presetPrices();
        for (int i = 0; i < P_SLOTS.length && i < pre.size(); i++) {
            double v = pre.get(i);
            boolean sel = cur > 0 && Math.abs(v - cur) < 0.0001;
            ItemStack it = Menus.item(Material.GOLD_NUGGET,
                    (sel ? "§a§l" : "§e§l") + Menus.plain(v),
                    "§7Click to set the price to",
                    "§f" + m.money(v) + "§7.");
            inv.setItem(P_SLOTS[i], sel ? Menus.glowing(it) : it);
        }

        inv.setItem(P_CUSTOM, Menus.item(Material.NAME_TAG, "§e§lType a custom price",
                "§7Type any amount in chat.",
                "§7Examples: §f100§7, §f2.5k§7, §f1m§7"));
        inv.setItem(P_BACK, Menus.item(Material.ARROW, "§7Back to setup"));
    }

    /** Close hook: hand the setup sample back and drop the draft. */
    public static void handleClose(InventoryCloseEvent e, AllPurpose plugin) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(holder instanceof Holder)) return;
        Holder h = (Holder) holder;
        if (h.screen == Screen.PRICE) {
            // ESC out of the price tab goes back to setup; the sample is parked in
            // the draft, so nothing is lost. Reopening is deferred a tick so the
            // client is not left holding a half-closed view.
            if (h.transitioning) return;
            if (!(e.getPlayer() instanceof Player)) return;
            final Player pl = (Player) e.getPlayer();
            if (!pl.isOnline()) return;
            h.transitioning = true;
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() {
                    try {
                        if (pl.isOnline() && plugin.getShop().draft(pl.getUniqueId()) != null)
                            openSetup(pl, plugin, true);
                    } catch (Exception ignored) {}
                }
            });
            return;
        }
        if (h.screen != Screen.SETUP) return;
        if (!(e.getPlayer() instanceof Player)) return;
        releaseSetup((Player) e.getPlayer(), h, plugin);
    }

    /**
     * Quit safety net. The close hook normally runs on disconnect too, but if it
     * does not, this recovers whatever the open SETUP screen still holds. Both
     * paths drain the same state, so the stack is handed back exactly once.
     */
    public static void releaseSetupOnQuit(Player p, AllPurpose plugin) {
        if (p == null) return;
        releaseSetup(p, openSetupHolder(p), plugin);
    }

    private static Holder openSetupHolder(Player p) {
        try {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof Holder && ((Holder) h).screen == Screen.SETUP) return (Holder) h;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Returns everything the closing/dead SETUP screen owes the player.
     *
     * There are two disjoint sources - the draft's parked stack (non-null only
     * while the screen is closed, e.g. during the price prompt) and the stack
     * still sitting in the hollow slot. The view is emptied here, so a second
     * call from the other hook finds nothing to hand back.
     */
    private static void releaseSetup(Player p, Holder h, AllPurpose plugin) {
        ShopManager m = plugin.getShop();
        ShopManager.Draft d = m.draft(p.getUniqueId());

        ItemStack inSlot = null;
        if (h != null) {
            inSlot = h.inv.getItem(SAMPLE);
            h.inv.setItem(SAMPLE, null);
        }

        if (d != null && d.prompting) {          // reopening after the price prompt
            d.sampleStack = inSlot;              // park it: the slot is now empty
            if (inSlot != null) d.sampleSig = sig(inSlot);
            d.prompting = false;
            return;                              // keep the draft alive
        }
        if (d != null) {
            // sampleStack is only ever a parked stack, so it is never the object
            // still in the slot. The identity check is a belt-and-braces guard:
            // handing the SAME stack back twice is exactly the shop-item dupe.
            if (d.sampleStack != null && d.sampleStack != inSlot) giveBack(p, d.sampleStack);
            d.sampleStack = null;
        }
        giveBack(p, inSlot);
        m.clearDraft(p.getUniqueId());
    }

    /** Returns a stack the player already owned: inventory first, then the floor. */
    public static void giveBack(Player p, ItemStack it) {
        if (p == null || it == null || it.getType() == Material.AIR) return;
        try {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            int l = ShopManager.leftoverAmount(left);
            if (l > 0) {
                ItemStack drop = it.clone();
                drop.setAmount(l);
                p.getWorld().dropItemNaturally(p.getLocation(), drop);
            }
        } catch (Exception e) {
            try { p.getWorld().dropItemNaturally(p.getLocation(), it); } catch (Exception ignored) {}
        }
    }

    // ---------- CONFIRM ----------

    public static void openConfirm(Player p, AllPurpose plugin, String shopKey) {
        ShopManager m = plugin.getShop();
        ShopManager.Shop s = m.byKey(shopKey);
        if (s == null) { p.sendMessage("§cThat shop no longer exists."); return; }
        Holder h = new Holder(Screen.CONFIRM, p.getUniqueId());
        h.shopKey = shopKey;
        Inventory inv = Bukkit.createInventory(h, 27, "§8§l» §6§lSHOP §8» §7" + trim(s.ownerName));
        h.inv = inv;
        renderConfirm(inv, h, p, plugin);
        p.openInventory(inv);
    }

    private static void renderConfirm(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        ShopManager m = plugin.getShop();
        ShopManager.Shop s = m.byKey(h.shopKey);
        Menus.fill(inv, Menus.GRAY);
        if (s == null) {
            inv.setItem(C_ITEM, Menus.item(Material.BARRIER, "§cThis shop is gone"));
            inv.setItem(C_CANCEL, Menus.item(Material.BARRIER, "§cClose"));
            return;
        }
        int qty = s.quantity();
        boolean sell = s.mode.isSell();
        boolean own = s.isOwner(p.getUniqueId());

        inv.setItem(C_ITEM, withAmount(ShopManager.stackOf(s.item, qty),
                "§e§l" + qty + "x " + ShopManager.prettyName(s.item),
                "§7Price: §a" + m.money(s.price),
                "§7Shop: §f" + s.ownerName,
                "§7Mode: " + (sell ? "§aSELLING" : "§6BUYING")));

        inv.setItem(C_INFO, Menus.item(Material.CHEST,
                "§6§l" + (sell ? "Buy from " : "Sell to ") + s.ownerName,
                sell ? "§7You pay §f" + m.money(s.price) + "§7 and take"
                     : "§7You hand over §f" + qty + "x " + ShopManager.prettyName(s.item),
                sell ? "§f" + qty + "x " + ShopManager.prettyName(s.item) + " §7from the chest."
                     : "§7and are paid §f" + m.money(s.price) + "§7."));

        if (sell) {
            int stock = m.stock(s);
            String state = stock >= qty ? "§a" + stock + " in stock" : "§cOut of stock (§f" + stock + "§c)";
            inv.setItem(C_STATUS, Menus.item(stock >= qty ? Material.CHEST : Material.BARRIER, "§fStock: " + state,
                    "§7Your balance: §a" + m.money(m.balance(p)),
                    stock >= qty ? "§7You pay §f" + m.money(s.price) + "§7." : "§7Nothing to buy right now."));
        } else {
            int have = ShopManager.countSimilar(p.getInventory(), s.item);
            boolean ok = have >= qty;
            inv.setItem(C_STATUS, Menus.item(ok ? Material.EMERALD : Material.BARRIER,
                    "§fYou carry: " + (ok ? "§a" : "§c") + have + "x",
                    "§7You receive: §a" + m.money(s.price),
                    "§7Owner funds: " + (m.ownerCanPay(s) ? "§aok" : "§cnot enough")));
        }

        boolean can = (!own && sell && m.stock(s) >= qty)
                || (!own && !sell && ShopManager.countSimilar(p.getInventory(), s.item) >= qty && m.ownerCanPay(s));
        if (own) {
            inv.setItem(C_OK, Menus.item(Material.LIGHT_GRAY_CONCRETE, "§8§lThat is your shop",
                    "§7Use §fsneak + right-click §7to manage it."));
        } else {
            inv.setItem(C_OK, Menus.item(can ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                    (can ? "§a§l" : "§c§l") + (sell ? "BUY" : "SELL"),
                    can ? "§7Click to trade now." : "§7Not possible right now."));
        }
        inv.setItem(C_CANCEL, Menus.item(Material.BARRIER, "§cClose"));
    }

    // ---------- LIST ----------

    public static void openList(Player p, AllPurpose plugin, boolean mineOnly, int page) {
        Holder h = new Holder(Screen.LIST, p.getUniqueId());
        h.mineOnly = mineOnly;
        h.page = Math.max(0, page);
        Inventory inv = Bukkit.createInventory(h, 54, "§8§l» §6§lSHOPS §8§l«");
        h.inv = inv;
        renderList(inv, h, p, plugin);
        p.openInventory(inv);
    }

    private static void renderList(Inventory inv, Holder h, Player p, AllPurpose plugin) {
        ShopManager m = plugin.getShop();
        Menus.fill(inv, Menus.GRAY);
        List<ShopManager.Shop> list = h.mineOnly ? m.ownedBy(p.getUniqueId()) : m.all();
        int perPage = L_SLOTS.length;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        if (h.page >= pages) h.page = pages - 1;
        if (h.page < 0) h.page = 0;
        int from = h.page * perPage;

        inv.setItem(L_HEADER, Menus.item(Material.CHEST, "§6§lChest shops",
                "§7Showing: " + (h.mineOnly ? "§fyour shops" : "§fall shops"),
                "§7Total: §f" + list.size(),
                "§7Page: §f" + (h.page + 1) + "§7/§f" + pages,
                "",
                "§7Right-click any shop chest to buy,",
                "§7sneak + right-click one to set it up."));

        for (int i = 0; i < perPage; i++) {
            int idx = from + i;
            if (idx >= list.size()) break;
            ShopManager.Shop s = list.get(idx);
            boolean own = s.isOwner(p.getUniqueId());
            boolean sell = s.mode.isSell();
            List<String> lore = new ArrayList<>();
            lore.add("§7Owner: §f" + s.ownerName + (own ? " §8(you)" : ""));
            lore.add("§7Price: §a" + m.money(s.price) + " §7for §f" + s.quantity() + "x");
            int st = s.mode.isSell() ? m.stockLoaded(s) : -1;
            lore.add(s.mode.isSell()
                    ? (st < 0 ? "§7Stock: §8(chunk not loaded)" : "§7Stock: §f" + st)
                    : "§7Mode: §6BUYING");
            lore.add("§7At: §f" + s.world + " §7" + s.x + ", " + s.y + ", " + s.z);
            lore.add("");
            lore.add(own ? "§eClick to edit this shop." : (sell ? "§eClick to buy." : "§eClick to sell."));
            inv.setItem(L_SLOTS[i], withAmount(ShopManager.stackOf(s.item, s.quantity()),
                    (sell ? "§a" : "§6") + s.quantity() + "x " + ShopManager.prettyName(s.item),
                    lore.toArray(new String[0])));
        }
        if (list.isEmpty()) {
            inv.setItem(22, Menus.item(Material.LIGHT_GRAY_DYE, "§7No shops here yet",
                    "§7Sneak + right-click a chest to",
                    "§7turn it into a shop."));
        }
        inv.setItem(L_PREV, Menus.item(h.page > 0 ? Material.ARROW : Material.GRAY_DYE, "§7Previous page"));
        inv.setItem(L_NEXT, Menus.item(h.page + 1 < pages ? Material.ARROW : Material.GRAY_DYE, "§7Next page"));
        inv.setItem(L_FILTER, Menus.item(h.mineOnly ? Material.PLAYER_HEAD : Material.CHEST,
                "§e§lFilter: §f" + (h.mineOnly ? "your shops" : "all shops"),
                "§7Click to switch."));
        inv.setItem(L_CLOSE, Menus.item(Material.BARRIER, "§cClose"));
    }

    // ---------- clicks ----------

    public static void handleClick(InventoryClickEvent e, AllPurpose plugin) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof Holder)) return;
        Holder h = (Holder) holder;
        if (!(e.getWhoClicked() instanceof Player)) { e.setCancelled(true); return; }
        final Player p = (Player) e.getWhoClicked();
        ShopManager m = plugin.getShop();
        boolean topClick = e.getClickedInventory() != null && e.getClickedInventory().equals(top);

        if (h.screen == Screen.SETUP) {
            if (!topClick) {
                // The player's own inventory stays usable; shift-moves are blocked so
                // nothing can be funnelled into the GUI's decorative slots.
                if (e.isShiftClick()) e.setCancelled(true);
                return;
            }
            if (e.getRawSlot() == SAMPLE) {
                if (e.isShiftClick()) { e.setCancelled(true); return; }
                // Let vanilla move the stack, then read it back next tick.
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() { syncSample(p, h, plugin); }
                });
                return;
            }
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot == CANCEL) { p.closeInventory(); return; }
            if (slot == MODE) {
                ShopManager.Draft d = m.draft(p.getUniqueId());
                if (d != null) { d.mode = d.mode.isSell() ? ShopManager.Mode.BUY : ShopManager.Mode.SELL; }
                renderSetup(h.inv, h, p, plugin);
                return;
            }
            if (slot == PRICE) { openPrice(p, plugin); return; }
            if (slot == QTY_MINUS || slot == QTY_PLUS) {
                ShopManager.Draft d = m.draft(p.getUniqueId());
                if (d != null) {
                    int step = e.getClick() != null && e.getClick().isShiftClick() ? 10 : 1;
                    if (slot == QTY_MINUS) step = -step;
                    d.qty = (int) Menus.clamp(d.qty + step, 1, ShopManager.MAX_QTY);
                }
                renderSetup(h.inv, h, p, plugin);
                return;
            }
            if (slot == CONFIRM_BTN) { confirmSetup(p, h, m, plugin); return; }
            return;
        }

        e.setCancelled(true);
        if (!topClick) return;

        if (h.screen == Screen.PRICE) {
            int slot = e.getRawSlot();
            if (slot == P_BACK) { h.transitioning = true; openSetup(p, plugin, true); return; }
            if (slot == P_CUSTOM) { h.transitioning = true; promptPrice(p, h, m, plugin); return; }
            List<Double> pre = m.presetPrices();
            for (int i = 0; i < P_SLOTS.length && i < pre.size(); i++) {
                if (slot != P_SLOTS[i]) continue;
                ShopManager.Draft d = m.draft(p.getUniqueId());
                if (d != null) d.price = pre.get(i);
                h.transitioning = true;
                openSetup(p, plugin, true);
                return;
            }
            return;
        }

        if (h.screen == Screen.CONFIRM) {
            int slot = e.getRawSlot();
            if (slot == C_CANCEL) { p.closeInventory(); return; }
            if (slot != C_OK) return;
            final String key = h.shopKey;
            final ShopManager.Shop s = m.byKey(key);
            if (s == null) { p.closeInventory(); return; }
            if (s.isOwner(p.getUniqueId())) { p.closeInventory(); return; }
            p.closeInventory();
            final boolean sell = s.mode.isSell();
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() {
                    ShopManager.Result r = sell ? m.buy(p, s) : m.sell(p, s);
                    p.sendMessage((r.ok ? "§a" : "§c") + r.msg);
                }
            });
            return;
        }

        if (h.screen == Screen.LIST) {
            int slot = e.getRawSlot();
            if (slot == L_CLOSE) { p.closeInventory(); return; }
            if (slot == L_PREV) { h.page--; renderList(h.inv, h, p, plugin); return; }
            if (slot == L_NEXT) { h.page++; renderList(h.inv, h, p, plugin); return; }
            if (slot == L_FILTER) { h.mineOnly = !h.mineOnly; h.page = 0; renderList(h.inv, h, p, plugin); return; }
            int idx = listIndex(slot);
            if (idx < 0) return;
            List<ShopManager.Shop> list = h.mineOnly ? m.ownedBy(p.getUniqueId()) : m.all();
            int real = h.page * L_SLOTS.length + idx;
            if (real >= list.size()) return;
            ShopManager.Shop s = list.get(real);
            if (s.isOwner(p.getUniqueId()) || p.hasPermission("allpurpose.shop.admin")) {
                ShopManager.Draft d = null;
                try { org.bukkit.block.Block b = s.block(); if (b != null) d = m.begin(p, b); } catch (Exception ignored) {}
                if (d != null) openSetup(p, plugin, false);
                else p.sendMessage("§cThat shop's world is not loaded right now.");
            } else {
                if (!p.hasPermission("allpurpose.shop")) { p.sendMessage("§cNo permission (allpurpose.shop)."); return; }
                openConfirm(p, plugin, s.key());
            }
            return;
        }
    }

    private static int listIndex(int slot) {
        for (int i = 0; i < L_SLOTS.length; i++) if (L_SLOTS[i] == slot) return i;
        return -1;
    }

    // ---------- small helpers ----------

    private static String sig(ItemStack it) {
        if (it == null) return "";
        String n = "";
        try { if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) n = it.getItemMeta().getDisplayName(); }
        catch (Exception ignored) {}
        return it.getType().name() + "|" + n + "|" + it.getAmount();
    }

    private static ItemStack withAmount(ItemStack it, String name, String... lore) {
        try {
            ItemStack c = it.clone();
            org.bukkit.inventory.meta.ItemMeta meta = c.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(name);
                if (lore != null && lore.length > 0) {
                    List<String> l = new ArrayList<>();
                    for (String s : lore) l.add(s == null ? "" : s);
                    meta.setLore(l);
                }
                c.setItemMeta(meta);
            }
            return c;
        } catch (Exception e) { return it; }
    }

    private static String trim(String s) {
        if (s == null) return "shop";
        String t = s.replaceAll("[^A-Za-z0-9_]", "");
        return t.length() <= 14 ? t : t.substring(0, 14);
    }
}
