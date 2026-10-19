package com.hush.allpurpose;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Chest shops. A player sneaks + right-clicks a container to register it as
 * their shop, picks the item / quantity / price, and the chest becomes a stall.
 * Two directions:
 *
 *   SELL - the owner stocks the chest; a buyer pays {@code price} and takes
 *          {@code quantity} items out of it.
 *   BUY  - the owner wants items; a seller hands over {@code quantity} items
 *          and is paid {@code price} out of the owner's balance.
 *
 * Every coin moves through Vault (EssentialsX Economy). Items and money are
 * always moved in a verified order and rolled back together, so a full
 * inventory or a failed deposit can never eat an item or a coin:
 *
 *   buy  : check stock -> withdraw buyer -> pull items -> give items
 *          (full? put items back + refund) -> deposit owner
 *   sell : withdraw owner (escrow) -> pull items from seller -> put in chest
 *          (full? give items back + refund owner) -> deposit seller
 *
 * Persistence is shops.yml in the plugin data folder. The class also owns the
 * short-lived per-player "draft" the setup GUI edits before it is committed.
 */
public class ShopManager {

    /** Which way the trade runs for the shop owner. */
    public enum Mode {
        SELL, BUY;

        public boolean isSell() { return this == SELL; }

        public static Mode parse(String raw) {
            try { return BUY.name().equalsIgnoreCase(raw) ? BUY : SELL; } catch (Exception e) { return SELL; }
        }
    }

    /** One registered shop. A double chest registers BOTH halves. */
    public static final class Shop {
        public final String world;
        public final int x, y, z;
        public final List<String> blocks = new ArrayList<>();
        public UUID owner;
        public String ownerName = "?";
        public Mode mode = Mode.SELL;
        public ItemStack item;
        public double price;
        public final long created = System.currentTimeMillis();

        Shop(Location loc) {
            this.world = loc.getWorld().getName();
            this.x = loc.getBlockX();
            this.y = loc.getBlockY();
            this.z = loc.getBlockZ();
            this.blocks.add(ShopManager.key(world, x, y, z));
        }

        public String key() { return ShopManager.key(world, x, y, z); }

        public int quantity() { return item == null ? 1 : Math.max(1, item.getAmount()); }

        public boolean isOwner(UUID id) { return owner != null && owner.equals(id); }

        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        public Block block() {
            Location l = location();
            return l == null ? null : l.getBlock();
        }
    }

    /**
     * In-progress setup. Lives only while a player is in the setup GUI (plus a
     * reopen after the "type the price" chat prompt). sampleStack is the stack
     * the player physically placed - it is NEVER handed out except back to them.
     *
     * INVARIANT: while the setup GUI is open the sample lives in the GUI's
     * "hollow" slot, NOT here. sampleStack is non-null ONLY while the stack is
     * parked OUT of the GUI (between closing for the price prompt and reopening),
     * and the GUI slot is empty in that state. Parking a live reference to a stack
     * that is still in the slot hands it back twice on close -> shop-item dupe.
     */
    public static final class Draft {
        public final Location block;
        public final String editingKey;   // non-null when editing an existing shop
        public ItemStack item;            // template, amount 1
        public int qty = 1;
        public Mode mode = Mode.SELL;
        public double price;
        public ItemStack sampleStack;     // parked stack (slot 11 empty), else null
        public String sampleSig;
        public boolean prompting;

        Draft(Location block, String editingKey) {
            this.block = block;
            this.editingKey = editingKey;
        }
    }

    /** Outcome of a trade: ok + the line to show, or the reason it failed. */
    public static final class Result {
        public final boolean ok;
        public final String msg;
        Result(boolean ok, String msg) { this.ok = ok; this.msg = msg; }
        public static Result ok(String m) { return new Result(true, m); }
        public static Result err(String m) { return new Result(false, m); }
    }

    private final AllPurpose plugin;
    private final List<Shop> shops = new ArrayList<>();
    private final Map<String, Shop> index = new LinkedHashMap<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private Economy econ;
    private File file;

    public ShopManager(AllPurpose plugin) {
        this.plugin = plugin;
    }

    public void start() {
        econ = Menus.economy();
        file = new File(plugin.getDataFolder(), "shops.yml");
        load();
        try {
            plugin.getLogger().info("[Shop] economy hook: "
                    + (econ == null ? "NOT HOOKED (Vault/Economy missing)" : econ.getName())
                    + ", registered shops: " + shops.size());
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        try { save(); } catch (Exception ignored) {}
    }

    public Economy economy() {
        if (econ == null) econ = Menus.economy();
        return econ;
    }

    public double balance(OfflinePlayer p) {
        Economy e = economy();
        if (e == null || p == null) return 0.0;
        try { return e.getBalance(p); } catch (Exception ex) { return 0.0; }
    }

    public String money(double v) { return Menus.money(economy(), v); }

    // ---------- config ----------

    public boolean enabled() { return bool("shop.enabled", true); }
    public double minPrice() { return Math.max(1.0, cfg("shop.min-price", 1.0)); }
    public double maxPrice() { return Math.max(minPrice(), cfg("shop.max-price", 1000000000.0)); }
    public double taxPercent() { return Menus.clamp(cfg("shop.tax-percent", 0.0), 0.0, 50.0); }
    public int maxPerPlayer() { return (int) Math.max(0.0, cfg("shop.max-per-player", 5.0)); }
    public boolean allowDoubleChests() { return bool("shop.allow-double-chest", true); }
    public boolean protectShops() { return bool("shop.protect-shops", true); }
    public boolean allowAutomation() { return bool("shop.allow-automation", false); }
    public boolean logTransactions() { return bool("shop.log-transactions", true); }
    public boolean announceSales() { return bool("shop.announce-sales", false); }

    // Label rendering is strictly on-demand (see ShopLabels): entities only exist
    // for shops with a player nearby, so a big shop network costs almost nothing.
    public String labelMode() {
        try { return TextUtil.norm(plugin.getConfig().getString("shop.label-mode", "nearby")); }
        catch (Exception e) { return "nearby"; }
    }
    public boolean labelsEnabled() { return !"off".equals(labelMode()); }
    public double labelRadius() { return Menus.clamp(cfg("shop.label-radius", 12.0), 2.0, 48.0); }
    public double labelHysteresis() { return Menus.clamp(cfg("shop.label-hysteresis", 4.0), 0.0, 32.0); }
    public int labelMax() { return (int) Menus.clamp(cfg("shop.label-max", 24.0), 0.0, 200.0); }
    public long labelTicks() { return (long) Menus.clamp(cfg("shop.label-update-ticks", 20.0), 4.0, 200.0); }
    public List<Double> presetPrices() {
        List<Double> out = new ArrayList<>();
        try {
            List<String> raw = plugin.getConfig().getStringList("shop.preset-prices");
            if (raw != null) for (String s : raw) {
                Double v = Menus.parseAmount(s);
                if (v != null) out.add(v);
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) { double[] def = {10, 100, 1000, 10000}; for (double d : def) out.add(d); }
        return out;
    }

    private boolean bool(String key, boolean def) {
        try { return plugin.getConfig().getBoolean(key, def); } catch (Exception e) { return def; }
    }

    private double cfg(String key, double def) {
        try { return plugin.getConfig().getDouble(key, def); } catch (Exception e) { return def; }
    }

    /** Shop cap: allpurpose.shop.limit.<n> wins, else the config default. */
    public int limitFor(Player p) {
        if (p.hasPermission("allpurpose.shop.admin")) return Integer.MAX_VALUE;
        int best = -1;
        try {
            for (PermissionAttachmentInfo pai : p.getEffectivePermissions()) {
                try {
                    if (!pai.getValue()) continue;
                    String n = TextUtil.norm(pai.getPermission());
                    if (n == null || !n.startsWith("allpurpose.shop.limit.")) continue;
                    int v = Integer.parseInt(n.substring("allpurpose.shop.limit.".length()));
                    if (v > best) best = v;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return best >= 0 ? best : maxPerPlayer();
    }

    // ---------- lookups ----------

    public synchronized List<Shop> all() { return new ArrayList<>(shops); }

    public synchronized Shop at(Block b) {
        if (b == null) return null;
        return index.get(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    public synchronized Shop at(Location l) { return l == null ? null : at(l.getBlock()); }

    public synchronized Shop byKey(String k) { return k == null ? null : index.get(k); }

    public synchronized List<Shop> ownedBy(UUID id) {
        List<Shop> out = new ArrayList<>();
        if (id == null) return out;
        for (Shop s : shops) if (s.isOwner(id)) out.add(s);
        return out;
    }

    public synchronized int countOwned(UUID id) { return ownedBy(id).size(); }

    public synchronized void clear() {
        shops.clear();
        index.clear();
        drafts.clear();
    }

    // ---------- drafts (setup GUI state) ----------

    public synchronized Draft draft(UUID id) { return id == null ? null : drafts.get(id); }

    public synchronized void setDraft(UUID id, Draft d) {
        if (id == null) return;
        if (d == null) drafts.remove(id); else drafts.put(id, d);
    }

    public synchronized void clearDraft(UUID id) { if (id != null) drafts.remove(id); }

    /**
     * Drop everything the player still owes the shop setup and forget the draft.
     * Used on quit, so a disconnect mid-setup (or mid price-prompt, where the
     * sample stack lives in the draft) can never eat the item. Routed through
     * ShopGui so it also recovers a stack still sitting in an open screen.
     */
    public synchronized void abandonDraft(Player p) {
        if (p == null) return;
        ShopGui.releaseSetupOnQuit(p, plugin);
    }

    /** Opens a fresh draft for a container the player is setting up (or editing). */
    public synchronized Draft begin(Player p, Block b) {
        Shop existing = at(b);
        boolean admin = p.hasPermission("allpurpose.shop.admin");
        if (existing != null && !existing.isOwner(p.getUniqueId()) && !admin) return null;
        Draft d = new Draft(b.getLocation(), existing == null ? null : existing.key());
        if (existing != null) {
            d.item = template(existing.item);
            d.qty = existing.quantity();
            d.mode = existing.mode;
            d.price = existing.price;
        }
        Draft old = drafts.put(p.getUniqueId(), d);
        if (old != null && old.sampleStack != null) {
            // The draft being replaced was parked mid price-prompt (its sample was
            // out of the GUI). Hand that stack back instead of letting the
            // overwrite silently eat it.
            ShopGui.giveBack(p, old.sampleStack);
            old.sampleStack = null;
        }
        return d;
    }

    // ---------- create / update / remove ----------

    public synchronized Result create(Player p, Location loc, ItemStack sample, Mode mode, double price) {
        if (!enabled()) return Result.err("Shops are disabled.");
        if (loc == null || loc.getWorld() == null) return Result.err("Unknown location.");
        Block b = loc.getBlock();
        if (!isAllowedContainer(b)) return Result.err("That block cannot hold a shop.");
        Shop existing = at(b);
        if (existing != null) return update(p, existing, sample, mode, price);
        if (sample == null || sample.getType() == Material.AIR) return Result.err("Place the item you want to trade first.");
        if (price < minPrice()) return Result.err("Minimum price is " + money(minPrice()) + ".");
        if (price > maxPrice()) return Result.err("Maximum price is " + money(maxPrice()) + ".");
        int owned = countOwned(p.getUniqueId());
        int limit = limitFor(p);
        if (owned >= limit) return Result.err("You already have " + owned + " shop(s) (limit " + limit + ").");
        Shop s = new Shop(loc);
        s.owner = p.getUniqueId();
        s.ownerName = p.getName();
        s.mode = mode;
        s.item = quantityTemplate(sample);
        s.price = Math.floor(price);
        if (allowDoubleChests()) for (String k : halves(b)) if (!s.blocks.contains(k)) s.blocks.add(k);
        shops.add(s);
        for (String k : s.blocks) index.put(k, s);
        save();
        return Result.ok("Shop created: " + describe(s) + ".");
    }

    public synchronized Result update(Player p, Shop s, ItemStack sample, Mode mode, double price) {
        if (s == null) return Result.err("That shop no longer exists.");
        if (!s.isOwner(p.getUniqueId()) && !p.hasPermission("allpurpose.shop.admin"))
            return Result.err("That is " + s.ownerName + "'s shop.");
        if (sample != null && sample.getType() != Material.AIR) { s.item = quantityTemplate(sample); }
        if (s.item == null || s.item.getType() == Material.AIR) return Result.err("Place the item you want to trade first.");
        if (price < minPrice()) return Result.err("Minimum price is " + money(minPrice()) + ".");
        if (price > maxPrice()) return Result.err("Maximum price is " + money(maxPrice()) + ".");
        s.mode = mode;
        s.price = Math.floor(price);
        save();
        return Result.ok("Shop updated: " + describe(s) + ".");
    }

    public synchronized boolean remove(Shop s) {
        if (s == null) return false;
        shops.remove(s);
        for (String k : s.blocks) index.remove(k);
        save();
        return true;
    }

    public synchronized int removeOwnedBy(UUID id) {
        List<Shop> mine = ownedBy(id);
        for (Shop s : mine) remove(s);
        return mine.size();
    }

    /** "/shop remove" while looking at a block. */
    public synchronized Result remove(Player p, Block b) {
        Shop s = at(b);
        if (s == null) return Result.err("That is not a shop.");
        if (!s.isOwner(p.getUniqueId()) && !p.hasPermission("allpurpose.shop.admin"))
            return Result.err("That is " + s.ownerName + "'s shop.");
        String d = describe(s);
        remove(s);
        return Result.ok("Removed the shop selling " + d + ".");
    }

    public String describe(Shop s) {
        if (s == null) return "?";
        return s.quantity() + "x " + prettyName(s.item) + " for " + money(s.price)
                + (s.mode.isSell() ? " (selling)" : " (buying)");
    }

    // ---------- stock ----------

    public Container container(Shop s) {
        return s == null ? null : containerOf(s.block());
    }

    /** Total similar items sitting in the shop chest. */
    public int stock(Shop s) {
        try {
            Container c = container(s);
            return c == null ? 0 : countSimilar(c.getInventory(), s.item);
        } catch (Exception e) { return 0; }
    }

    /**
     * Stock, but only when the shop's chunk is already loaded - reading a chest
     * in an unloaded chunk would force a synchronous chunk load. Returns -1 for
     * "unknown" so screens can say so instead of stalling the server thread.
     */
    public int stockLoaded(Shop s) {
        try {
            Location l = s == null ? null : s.location();
            if (l == null || l.getWorld() == null) return -1;
            if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return -1;
        } catch (Exception ignored) { return -1; }
        return stock(s);
    }

    /** True when a BUY shop's owner currently has the money to pay a seller. */
    public boolean ownerCanPay(Shop s) {
        try {
            Economy e = economy();
            if (e == null) return false;
            return e.has(Bukkit.getOfflinePlayer(s.owner), ownerCost(s));
        } catch (Exception ex) { return false; }
    }

    // ---------- tax ----------

    /** House cut withheld from the shop owner's side of a trade. */
    public double taxOf(double price) { return Math.max(0.0, price * taxPercent() / 100.0); }

    /** What a SELL shop owner receives for a trade. */
    public double ownerProceeds(Shop s) { return Math.max(0.0, s.price - taxOf(s.price)); }

    /** What a BUY shop owner pays for a trade. */
    public double ownerCost(Shop s) { return s.price + taxOf(s.price); }

    // ---------- trades ----------

    /** Buyer takes items out of a SELL shop. */
    public synchronized Result buy(Player buyer, Shop s) {
        if (!enabled()) return Result.err("Shops are disabled.");
        if (s == null) return Result.err("That shop no longer exists.");
        if (s.isOwner(buyer.getUniqueId())) return Result.err("That is your own shop.");
        if (!s.mode.isSell()) return Result.err("That shop is buying, not selling.");
        Economy e = economy();
        if (e == null) return Result.err("No economy hooked (Vault missing).");
        Container c = container(s);
        if (c == null) return Result.err("That shop's chest is gone.");
        int qty = s.quantity();
        Inventory chest = c.getInventory();
        if (countSimilar(chest, s.item) < qty) return Result.err("Out of stock.");
        double price = s.price;
        if (!e.has(buyer, price)) {
            return Result.err("You need " + money(price) + " (you have " + money(balance(buyer)) + ").");
        }
        EconomyResponse w = e.withdrawPlayer(buyer, price);
        if (w == null || !w.transactionSuccess()) {
            return Result.err("Withdraw failed: " + (w == null ? "no response" : w.errorMessage));
        }
        if (!removeSimilar(chest, s.item, qty)) {
            refund(buyer, price);
            return Result.err("Could not take the items; nothing was traded.");
        }
        ItemStack give = stackOf(s.item, qty);
        Map<Integer, ItemStack> left = buyer.getInventory().addItem(give);
        int leftover = leftoverAmount(left);
        if (leftover > 0) {
            // Undo all of it: pull back what fit, return the stock, refund.
            int added = qty - leftover;
            if (added > 0) removeSimilar(buyer.getInventory(), s.item, added);
            try { chest.addItem(stackOf(s.item, qty)); } catch (Exception ignored) {}
            refund(buyer, price);
            return Result.err("Your inventory is full; nothing was traded.");
        }
        double pay = ownerProceeds(s);
        deposit(s.owner, pay);
        String line = buyer.getName() + " bought " + qty + "x " + prettyName(s.item) + " from " + s.ownerName + " for " + money(price);
        notify(s, buyer.getName() + " bought " + qty + "x " + prettyName(s.item) + " for " + money(price) + ".");
        say(line);
        announce(buyer.getName() + " bought " + qty + "x " + prettyName(s.item) + " from " + s.ownerName + " for " + money(price));
        return Result.ok("Bought " + qty + "x " + prettyName(s.item) + " for " + money(price) + ".");
    }

    /** Seller hands items to a BUY shop and is paid from the owner's balance. */
    public synchronized Result sell(Player seller, Shop s) {
        if (!enabled()) return Result.err("Shops are disabled.");
        if (s == null) return Result.err("That shop no longer exists.");
        if (s.isOwner(seller.getUniqueId())) return Result.err("That is your own shop.");
        if (s.mode.isSell()) return Result.err("That shop is selling, not buying.");
        Economy e = economy();
        if (e == null) return Result.err("No economy hooked (Vault missing).");
        Container c = container(s);
        if (c == null) return Result.err("That shop's chest is gone.");
        int qty = s.quantity();
        ItemStack have = stackOf(s.item, qty);
        if (countSimilar(seller.getInventory(), s.item) < qty) {
            return Result.err("You need " + qty + "x " + prettyName(s.item) + " to sell here.");
        }
        double cost = ownerCost(s);
        OfflinePlayer ownerRef = Bukkit.getOfflinePlayer(s.owner);
        if (!e.has(ownerRef, cost)) {
            return Result.err(s.ownerName + " cannot afford this right now.");
        }
        EconomyResponse w = e.withdrawPlayer(ownerRef, cost);
        if (w == null || !w.transactionSuccess()) {
            return Result.err("Could not take the owner's payment; nothing was traded.");
        }
        if (!removeSimilar(seller.getInventory(), s.item, qty)) {
            refund(s.owner, cost);
            return Result.err("Could not take your items; nothing was traded.");
        }
        Inventory chest = c.getInventory();
        Map<Integer, ItemStack> left;
        try {
            left = chest.addItem(have);
        } catch (Exception ex) {
            seller.getInventory().addItem(have);
            refund(s.owner, cost);
            return Result.err("The shop chest rejected the items; nothing was traded.");
        }
        int leftover = leftoverAmount(left);
        if (leftover > 0) {
            int stored = qty - leftover;
            if (stored > 0) removeSimilar(chest, s.item, stored);
            seller.getInventory().addItem(stackOf(s.item, qty));
            refund(s.owner, cost);
            return Result.err("That shop's chest is full; nothing was traded.");
        }
        deposit(seller.getUniqueId(), s.price);
        String line = seller.getName() + " sold " + qty + "x " + prettyName(s.item) + " to " + s.ownerName + " for " + money(s.price);
        notify(s, seller.getName() + " sold " + qty + "x " + prettyName(s.item) + " for " + money(s.price) + ".");
        say(line);
        announce(seller.getName() + " sold " + qty + "x " + prettyName(s.item) + " to " + s.ownerName + " for " + money(s.price));
        return Result.ok("Sold " + qty + "x " + prettyName(s.item) + " for " + money(s.price) + ".");
    }

    // ---------- money ----------

    private void deposit(UUID id, double amount) {
        if (amount <= 0) return;
        Economy e = economy();
        if (e == null) return;
        try { e.depositPlayer(Bukkit.getOfflinePlayer(id), amount); } catch (Exception ignored) {}
    }

    private void refund(OfflinePlayer p, double amount) {
        if (amount <= 0 || p == null) return;
        Economy e = economy();
        if (e == null) return;
        try { e.depositPlayer(p, amount); } catch (Exception ignored) {}
    }

    private void refund(UUID id, double amount) {
        if (amount <= 0 || id == null) return;
        refund(Bukkit.getOfflinePlayer(id), amount);
    }

    private void notify(Shop s, String msg) {
        try {
            Player o = s.owner == null ? null : Bukkit.getPlayer(s.owner);
            if (o != null && o.isOnline()) o.sendMessage("§6§l[Shop] §f" + msg);
        } catch (Exception ignored) {}
    }

    private void announce(String line) {
        if (!announceSales()) return;
        try { Bukkit.broadcastMessage("§6§l[Shop] §f" + line); } catch (Exception ignored) {}
    }

    /** Plain-text line for the Discord chat log (no-op when it is disabled). */
    public void say(String line) {
        if (!logTransactions()) return;
        try {
            DiscordChatLog log = plugin.getChatLog();
            if (log != null) log.system("[Shop] " + line);
        } catch (Exception ignored) {}
    }

    // ---------- label text ----------

    /** &-coded label for the floating display. One entry per line. */
    public String labelText(Shop s) {
        try {
            int qty = s.quantity();
            String nm = prettyName(s.item);
            StringBuilder b = new StringBuilder();
            b.append("&e&l").append(nm);
            b.append("\n").append(s.mode.isSell() ? "&aBuy &f" + qty + "x &7for &f" + money(s.price)
                                                 : "&aSell &f" + qty + "x &7for &f" + money(s.price));
            if (s.mode.isSell()) {
                int st = stockLoaded(s);
                b.append("\n&7Stock: " + (st < 0 ? "&8?" : "&f" + st) + " &8| &7By &f" + s.ownerName);
            } else {
                b.append("\n&7Pays &f" + money(s.price) + " &8| &7By &f" + s.ownerName);
            }
            return b.toString();
        } catch (Exception e) { return "&eShop"; }
    }

    /** Readable item name: custom display name if present, else the material name. */
    public static String prettyName(ItemStack it) {
        if (it == null) return "item";
        try {
            if (it.hasItemMeta() && it.getItemMeta() != null && it.getItemMeta().hasDisplayName()) {
                String n = it.getItemMeta().getDisplayName();
                if (n != null && !n.trim().isEmpty()) return sanitize(n);
            }
        } catch (Exception ignored) {}
        String n = it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return n.length() > 0 ? Character.toUpperCase(n.charAt(0)) + n.substring(1) : "item";
    }

    /** Kills color codes + newlines so a renamed item cannot break the label/GUI. */
    private static String sanitize(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ');
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '§' && i + 1 < t.length()) { i++; continue; }
            b.append(c);
        }
        return b.length() > 40 ? b.substring(0, 40) : b.toString();
    }

    // ---------- item helpers ----------

    /** Item template for a shop: a single unit of what the player offered. */
    public static ItemStack template(ItemStack it) {
        if (it == null) return null;
        ItemStack c = it.clone();
        c.setAmount(1);
        return c;
    }

    /** Item template carrying the per-trade quantity (clamped to one stack). */
    public static ItemStack quantityTemplate(ItemStack it) {
        if (it == null) return null;
        ItemStack c = it.clone();
        c.setAmount((int) Menus.clamp(c.getAmount(), 1, MAX_QTY));
        return c;
    }

    public static final int MAX_QTY = 64;

    public static ItemStack stackOf(ItemStack template, int amount) {
        ItemStack c = template.clone();
        c.setAmount(Math.max(1, amount));
        return c;
    }

    public static int countSimilar(Inventory inv, ItemStack template) {
        if (inv == null || template == null) return 0;
        int n = 0;
        try {
            for (ItemStack it : inv.getContents()) if (it != null && it.isSimilar(template)) n += it.getAmount();
        } catch (Exception ignored) {}
        return n;
    }

    /** Removes exactly {@code amount} similar items, or nothing at all. */
    public static boolean removeSimilar(Inventory inv, ItemStack template, int amount) {
        if (amount <= 0) return true;
        if (inv == null || template == null) return false;
        if (countSimilar(inv, template) < amount) return false;
        int left = amount;
        try {
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length && left > 0; i++) {
                ItemStack it = contents[i];
                if (it == null || !it.isSimilar(template)) continue;
                int take = Math.min(left, it.getAmount());
                left -= take;
                int remain = it.getAmount() - take;
                if (remain <= 0) inv.setItem(i, null);
                else inv.setItem(i, stackOf(it, remain));
            }
        } catch (Exception ignored) {}
        return left <= 0;
    }

    public static int leftoverAmount(Map<Integer, ItemStack> left) {
        if (left == null || left.isEmpty()) return 0;
        int n = 0;
        for (ItemStack it : left.values()) if (it != null) n += it.getAmount();
        return n;
    }

    /** Container block state at a location, or null when it is not a container. */
    public static Container containerOf(Block b) {
        if (b == null) return null;
        try {
            BlockState st = b.getState();
            if (st instanceof Container) return (Container) st;
        } catch (Exception ignored) {}
        return null;
    }

    /** Containers a shop may live in (config: shop.allowed-containers, or a sane default). */
    public static boolean isAllowedContainer(Block b) {
        Container c = containerOf(b);
        if (c == null) return false;
        try {
            Material m = b.getType();
            if (m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL) return true;
            String n = m.name();
            if (n.endsWith("SHULKER_BOX")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Both halves of a double chest (just the one block otherwise). */
    public static List<String> halves(Block b) {
        List<String> out = new ArrayList<>();
        if (b == null) return out;
        try {
            String k = key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            out.add(k);
            BlockState st = b.getState();
            if (st instanceof Chest) {
                Inventory inv = ((Chest) st).getInventory();
                InventoryHolder h = inv == null ? null : inv.getHolder();
                if (h instanceof DoubleChest) {
                    addHalf(out, ((DoubleChest) h).getLeftSide(), b);
                    addHalf(out, ((DoubleChest) h).getRightSide(), b);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void addHalf(List<String> out, InventoryHolder half, Block origin) {
        try {
            if (!(half instanceof BlockState)) return;
            Block hb = ((BlockState) half).getBlock();
            if (hb == null) return;
            if (hb.getX() == origin.getX() && hb.getY() == origin.getY() && hb.getZ() == origin.getZ()) return;
            String k = key(hb.getWorld().getName(), hb.getX(), hb.getY(), hb.getZ());
            if (!out.contains(k)) out.add(k);
        } catch (Exception ignored) {}
    }

    public static String key(String world, int x, int y, int z) { return world + "|" + x + "," + y + "," + z; }

    // ---------- persistence ----------

    private synchronized void load() {
        shops.clear();
        index.clear();
        try {
            if (file == null || !file.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = y.getConfigurationSection("shops");
            if (root == null) return;
            for (String world : root.getKeys(false)) {
                ConfigurationSection ws = root.getConfigurationSection(world);
                if (ws == null) continue;
                for (String coord : ws.getKeys(false)) {
                    try {
                        ConfigurationSection s = ws.getConfigurationSection(coord);
                        if (s == null) continue;
                        String[] p = coord.split(",");
                        if (p.length != 3) continue;
                        World w = Bukkit.getWorld(world);
                        Location loc = new Location(w, Integer.parseInt(p[0].trim()),
                                Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
                        Shop shop = new Shop(loc);
                        String ou = s.getString("owner");
                        if (ou != null) { try { shop.owner = UUID.fromString(ou); } catch (Exception ignored) {} }
                        shop.ownerName = s.getString("owner-name", "?");
                        shop.mode = Mode.parse(s.getString("mode", "SELL"));
                        shop.item = s.getItemStack("item");
                        if (shop.item == null) continue;      // unreadable entry: skip, never crash
                        shop.item = quantityTemplate(shop.item);
                        shop.price = s.getDouble("price", 0.0);
                        List<String> extra = s.getStringList("blocks");
                        for (String k : extra) if (k != null && !k.trim().isEmpty() && !shop.blocks.contains(k)) shop.blocks.add(k);
                        if (!allowDoubleChests() && shop.blocks.size() > 1) {
                            String only = shop.blocks.get(0);
                            shop.blocks.clear();
                            shop.blocks.add(only);
                        }
                        shops.add(shop);
                        for (String k : shop.blocks) index.put(k, shop);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Shop] load failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    public synchronized void save() {
        try {
            if (file == null) return;
            YamlConfiguration y = new YamlConfiguration();
            for (Shop s : shops) {
                // Coordinate key "x,y,z" has no '.' in it, so Bukkit's dot-path
                // handling writes it literally and getKeys(false) reads it back.
                String base = "shops." + s.world + "." + s.x + "," + s.y + "," + s.z;
                if (s.owner != null) y.set(base + ".owner", s.owner.toString());
                y.set(base + ".owner-name", s.ownerName);
                y.set(base + ".mode", s.mode.name());
                y.set(base + ".item", s.item);
                y.set(base + ".price", s.price);
                if (s.blocks.size() > 1) y.set(base + ".blocks", new ArrayList<String>(s.blocks));
            }
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            y.save(file);
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Shop] save failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }
}
