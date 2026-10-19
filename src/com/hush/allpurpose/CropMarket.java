package com.hush.allpurpose;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.scheduler.BukkitTask;

/**
 * Crops market: the crop shop's SELL prices follow real supply.
 *
 * Every review window the market tallies how many units of each crop players
 * actually sold (EconomyShopGUI fires a PostTransactionEvent per shop trade;
 * we register for it reflectively, so EconomyShopGUI stays a soft dependency)
 * and moves that crop's sell price:
 *
 *   pressure = sold / heavy-volume        (clamped 0..1)
 *   target   = 1 - pressure * (1 - min-multiplier)   (heavily farmed -> floor)
 *   multiplier drifts toward target, at most step-percent per review downwards
 *   and recovery-percent per review upwards (crashes heal slowly, by design).
 *
 * Prices are written through EconomyShopGUI's OWN admin command
 * (/editshop edititem &lt;section&gt; &lt;page&gt;.items.&lt;key&gt; set sell &lt;price&gt;) followed by
 * /sreload, so the shop file keeps the plugin's own format - we never rewrite
 * it. A price that no longer matches what we last wrote is treated as a manual
 * edit and adopted as the new base (owner edits always win).
 *
 * Nothing here runs unless enabled AND the crop shop file is readable; with
 * EconomyShopGUI absent the hook never installs and prices simply never move.
 */
public class CropMarket {

    /** EconomyShopGUI's per-transaction event (reflected: never a hard dep). */
    private static final String HOOK_CLASS =
            "me.gypopo.economyshopgui.api.events.PostTransactionEvent";

    /** Task period: state save every 5 min, a full review when one is due. */
    private static final long PERIOD_TICKS = 20L * 60L * 5L;

    private final AllPurpose plugin;

    /** key -> crop row, in shop-file order. */
    private final Map<String, Crop> crops = new LinkedHashMap<>();
    /** key -> units sold since the last review (this window). */
    private final Map<String, Long> sold = new ConcurrentHashMap<>();

    private BukkitTask task = null;
    private boolean hooked = false;
    private volatile boolean busy = false;
    private volatile long lastReviewMs = 0L;
    private volatile long lastSold = 0L;
    private volatile int lastChanged = 0;
    private volatile String lastNote = "no review yet";

    public CropMarket(AllPurpose plugin) {
        this.plugin = plugin;
    }

    /** One shop crop: the file slot, its reference price and the live multiplier. */
    public static final class Crop {
        public final String key;      // file key, e.g. sugar_cane
        public final String page;     // page1 / page2 - needed by /editshop
        public final String material; // SUGAR_CANE
        public final String label;    // Sugar Cane
        public double base = -1.0;    // reference sell price (0 = not set yet)
        public double mult = 1.0;     // current multiplier vs base
        public double applied = -1.0; // last price we wrote (-1 = we never wrote)
        public double price = -1.0;   // last price seen in the shop file
        public long soldSince = 0L;   // units sold in the window just reviewed

        Crop(String key, String page, String material) {
            this.key = key;
            this.page = page;
            this.material = material;
            this.label = label(material);
        }
    }

    // ---------- config ----------

    public boolean enabled() {
        try {
            if (!plugin.getConfig().getBoolean("crops-market.enabled", true)) return false;
            return shopsFile().isFile();
        } catch (Exception e) {
            return false;
        }
    }

    public String shopSection() {
        try {
            String s = plugin.getConfig().getString("crops-market.shop-section", "crops");
            if (s == null || s.trim().isEmpty()) return "crops";
            return s.trim();
        } catch (Exception e) {
            return "crops";
        }
    }

    public File shopsFile() {
        String raw = "plugins/EconomyShopGUI/shops/Farming/crops.yml";
        try {
            String c = plugin.getConfig().getString("crops-market.shops-file", raw);
            if (c != null && !c.trim().isEmpty()) raw = c.trim();
        } catch (Exception ignored) {}
        try {
            File f = new File(raw);
            if (f.isAbsolute()) return f;
            // Relative paths are from the server root (our CWD), with the world
            // container as a fallback for odd launch layouts.
            if (f.isFile()) return f;
            return new File(Bukkit.getWorldContainer(), raw);
        } catch (Exception e) {
            return new File(raw);
        }
    }

    public int reviewMinutes() {
        try {
            int m = plugin.getConfig().getInt("crops-market.review-minutes", 60);
            if (m < 5) m = 5;
            if (m > 1440) m = 1440;
            return m;
        } catch (Exception e) {
            return 60;
        }
    }

    /** Units sold in one window that push a crop's price to the floor. */
    public double heavyVolume() {
        try {
            double v = plugin.getConfig().getDouble("crops-market.heavy-volume", 500.0);
            if (v < 1.0) v = 1.0;
            return v;
        } catch (Exception e) {
            return 500.0;
        }
    }

    private double range(String path, double def, double lo, double hi) {
        try {
            double v = plugin.getConfig().getDouble(path, def);
            if (v < lo) v = lo;
            if (v > hi) v = hi;
            return v;
        } catch (Exception e) {
            return def;
        }
    }

    public double minMultiplier() { return range("crops-market.min-multiplier", 0.4, 0.05, 1.0); }
    public double maxMultiplier() { return range("crops-market.max-multiplier", 1.2, 1.0, 5.0); }
    /** Max fall per review, in % of the current multiplier. */
    public double stepPercent() { return range("crops-market.step-percent", 15.0, 0.5, 100.0); }
    /** Max rise per review, in % of the current multiplier (slow recovery). */
    public double recoveryPercent() { return range("crops-market.recovery-percent", 5.0, 0.5, 100.0); }
    public double minPrice() { return range("crops-market.min-price", 0.01, 0.0, 1000000.0); }

    // ---------- lifecycle ----------

    public synchronized void start() {
        stopTask();
        // Rows first (state and sold volumes are keyed by shop-file item key).
        try { reloadCrops(); } catch (Exception ignored) {}
        try { loadState(); } catch (Exception e) { plugin.getLogger().warning("Crops market: state load failed: " + e.getMessage()); }
        if (!enabled()) return;
        installHook();
        long period = (long) reviewMinutes() * 60L * 1000L;
        long now = System.currentTimeMillis();
        if (lastReviewMs <= 0L) {
            // Fresh state: start the FIRST full window at boot. Reviewing a few
            // seconds after boot would price a whole window off a minute of
            // sales (and slam the busiest crop to the floor for no reason).
            lastReviewMs = now;
            saveState();
        }
        long due = (lastReviewMs + period) - now;
        long delay = Math.max(1200L, due / 50L); // at least ~60s after boot
        try {
            task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() { tick(); }
            }, delay, PERIOD_TICKS);
        } catch (Exception e) {
            plugin.getLogger().warning("Crops market: scheduling failed: " + e.getMessage());
        }
    }

    public synchronized void reload() {
        try { start(); } catch (Exception ignored) {}
    }

    public synchronized void shutdown() {
        stopTask();
        try { saveState(); } catch (Exception ignored) {}
    }

    private synchronized void stopTask() {
        try { if (task != null) task.cancel(); } catch (Exception ignored) {}
        task = null;
    }

    private void tick() {
        try {
            if (!enabled()) return;
            long period = (long) reviewMinutes() * 60L * 1000L;
            long now = System.currentTimeMillis();
            if (now - lastReviewMs >= period) review();
            else saveState();
        } catch (Exception ignored) {}
    }

    // ---------- EconomyShopGUI hook (reflection, no hard dependency) ----------

    private void installHook() {
        if (hooked) return;
        try {
            Class<?> cls = Class.forName(HOOK_CLASS);
            if (!Event.class.isAssignableFrom(cls)) return;
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) cls;
            Listener listener = new Listener() { };
            EventExecutor executor = new EventExecutor() {
                @Override public void execute(Listener l, Event e) { onTransaction(e); }
            };
            Bukkit.getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR, executor, plugin);
            hooked = true;
            plugin.getLogger().info("Crops market: listening to EconomyShopGUI transactions.");
        } catch (Throwable t) {
            hooked = false;
            plugin.getLogger().info("Crops market: EconomyShopGUI not present - supply tracking off (prices stay put).");
        }
    }

    public boolean isHooked() {
        return hooked;
    }

    /** One shop trade: keep the successful SELL volume for the crops we price. */
    private void onTransaction(Event e) {
        try {
            Object result = call(e, "getTransactionResult");
            String res = result == null ? "" : String.valueOf(result);
            if (!res.isEmpty() && !res.toUpperCase(Locale.ROOT).startsWith("SUCCESS")) return;
            Object type = call(e, "getTransactionType");
            Object mode = type == null ? null : call(type, "getTransactionMode");
            String m = mode != null ? String.valueOf(mode) : (type == null ? "" : String.valueOf(type));
            if (!m.toUpperCase(Locale.ROOT).contains("SELL")) return;
            Object items = call(e, "getItems");
            boolean counted = false;
            if (items instanceof Map) {
                try {
                    for (Map.Entry<?, ?> en : ((Map<?, ?>) items).entrySet()) {
                        counted |= count(en.getKey(), en.getValue());
                    }
                } catch (Exception ignored) {}
            }
            if (!counted) {
                counted = count(call(e, "getShopItem"), call(e, "getAmount"));
            }
        } catch (Exception ignored) {}
    }

    /** Tally one sold stack against its crop row. */
    private boolean count(Object shopItem, Object amountObj) {
        try {
            if (shopItem == null) return false;
            String path = null;
            Object p = call(shopItem, "getItemPath");
            if (p != null) path = String.valueOf(p);
            if (path == null || path.isEmpty()) {
                Object sec = call(shopItem, "section");
                Object loc = call(shopItem, "itemLoc");
                if (sec != null && loc != null) path = sec + "." + loc;
            }
            if (path == null) return false;
            String[] parts = path.split("\\.");
            if (parts.length < 4) return false;
            if (!parts[0].equalsIgnoreCase(shopSection())) return false;
            if (!"items".equalsIgnoreCase(parts[parts.length - 2])) return false;
            String key = parts[parts.length - 1];
            if (!crops.containsKey(key)) return false;
            long amount = 1L;
            if (amountObj instanceof Number) amount = Math.max(1L, ((Number) amountObj).longValue());
            sold.merge(key, Long.valueOf(amount), new java.util.function.BiFunction<Long, Long, Long>() {
                @Override public Long apply(Long a, Long b) { return Long.valueOf(a.longValue() + b.longValue()); }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object call(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- shop file ----------

    private File stateFile() {
        return new File(plugin.getDataFolder(), "cropmarket.yml");
    }

    /** (Re)read the crop shop: every item with a sell price we can move. */
    private void reloadCrops() {
        crops.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(shopsFile());
        ConfigurationSection pages = y.getConfigurationSection("pages");
        if (pages == null) return;
        for (String page : pages.getKeys(false)) {
            ConfigurationSection items = pages.getConfigurationSection(page + ".items");
            if (items == null) continue;
            for (String key : items.getKeys(false)) {
                try {
                    ConfigurationSection it = items.getConfigurationSection(key);
                    if (it == null) continue;
                    if (!it.isSet("sell")) continue;
                    double sell = it.getDouble("sell", -1.0);
                    if (sell <= 0.0) continue;
                    String material = it.getString("material", key.toUpperCase(Locale.ROOT));
                    if (material == null || material.trim().isEmpty()) material = key.toUpperCase(Locale.ROOT);
                    Crop c = new Crop(key, page, material.trim().toUpperCase(Locale.ROOT));
                    c.price = sell;
                    crops.put(key, c);
                } catch (Exception ignored) {}
            }
        }
    }

    /** Read the shop file's current sell prices into the rows. */
    private void refreshPrices() {
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(shopsFile());
            ConfigurationSection pages = y.getConfigurationSection("pages");
            if (pages == null) return;
            for (Crop c : crops.values()) {
                try {
                    double sell = y.getDouble("pages." + c.page + ".items." + c.key + ".sell", c.price);
                    if (sell > 0.0) c.price = sell;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // ---------- state ----------

    private void loadState() {
        File f = stateFile();
        if (!f.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        try { lastReviewMs = y.getLong("last-review", 0L); } catch (Exception ignored) {}
        try {
            ConfigurationSection cs = y.getConfigurationSection("crops");
            if (cs == null) return;
            for (String key : cs.getKeys(false)) {
                Crop c = crops.get(key);
                if (c == null) continue; // crop removed from the shop
                c.base = cs.getDouble(key + ".base", -1.0);
                c.mult = cs.getDouble(key + ".mult", 1.0);
                c.applied = cs.getDouble(key + ".applied", -1.0);
            }
        } catch (Exception ignored) {}
        try {
            ConfigurationSection ss = y.getConfigurationSection("sold");
            if (ss != null) {
                for (String key : ss.getKeys(false)) {
                    if (!crops.containsKey(key)) continue;
                    long v = ss.getLong(key, 0L);
                    if (v > 0L) sold.put(key, v);
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveState() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("last-review", lastReviewMs);
            for (Crop c : crops.values()) {
                y.set("crops." + c.key + ".base", c.base);
                y.set("crops." + c.key + ".mult", round(c.mult, 4));
                y.set("crops." + c.key + ".applied", c.applied);
                long v = sold.getOrDefault(c.key, 0L);
                if (v > 0L) y.set("sold." + c.key, v);
            }
            plugin.getDataFolder().mkdirs();
            y.save(stateFile());
        } catch (Exception e) {
            plugin.getLogger().warning("Crops market: state save failed: " + e.getMessage());
        }
    }

    // ---------- review ----------

    /** Force the next window to be reviewed now (command / board refresh). */
    public void reviewNow() {
        Runnable r = new Runnable() {
            @Override public void run() { review(); }
        };
        try {
            if (Bukkit.isPrimaryThread()) r.run();
            else Bukkit.getScheduler().runTask(plugin, r);
        } catch (Exception ignored) {}
    }

    /**
     * One pricing pass: read the shop, adopt manual edits, move each crop's
     * multiplier toward its supply-driven target, then apply the new sell
     * prices through EconomyShopGUI itself.
     */
    private synchronized void review() {
        if (busy) return;
        if (!enabled()) return;
        busy = true;
        try {
            if (crops.isEmpty()) reloadCrops();
            refreshPrices();
            List<String> cmds = new ArrayList<>();
            List<String> moved = new ArrayList<>();
            long windowSold = 0L;
            int changed = 0;
            for (Crop c : crops.values()) {
                // 1) adopt an owner edit: a price we did not write becomes the base.
                if (c.applied >= 0.0 && Math.abs(c.price - c.applied) >= 0.005) {
                    c.base = c.price;
                    c.mult = 1.0;
                    c.applied = -1.0;
                    plugin.getLogger().info("Crops market: " + c.key + " price was edited to "
                            + fmt(c.price) + " - adopted as the new base.");
                }
                if (c.base <= 0.0) { c.base = c.price; c.mult = 1.0; }
                if (c.base <= 0.0) continue;

                // 2) supply pressure -> target multiplier.
                Long removed = sold.remove(c.key);
                long soldNow = removed == null ? 0L : removed.longValue();
                c.soldSince = soldNow;
                windowSold += soldNow;
                double pressure = Math.min(1.0, soldNow / heavyVolume());
                double target = 1.0 - pressure * (1.0 - minMultiplier());
                if (target > maxMultiplier()) target = maxMultiplier();
                if (target < minMultiplier()) target = minMultiplier();

                // 3) move toward it: fast down, slow up.
                double delta = target - c.mult;
                double cap = c.mult * (delta < 0.0 ? stepPercent() : recoveryPercent()) / 100.0;
                if (cap <= 0.0) cap = Math.abs(delta);
                if (delta > cap) delta = cap;
                if (delta < -cap) delta = -cap;
                c.mult += delta;
                if (c.mult < minMultiplier()) c.mult = minMultiplier();
                if (c.mult > maxMultiplier()) c.mult = maxMultiplier();

                // 4) price it, and only write when the shop actually differs.
                double price = round(c.base * c.mult, 2);
                if (price < minPrice()) price = minPrice();
                if (Math.abs(price - c.price) < 0.005) continue;
                cmds.add("editshop edititem " + shopSection() + " " + c.page + ".items." + c.key
                        + " set sell " + fmt(price));
                c.applied = price;
                changed++;
                moved.add(c.label + " " + fmt(c.price) + " -> " + fmt(price));
            }
            lastChanged = changed;
            lastSold = windowSold;
            lastReviewMs = System.currentTimeMillis();
            saveState();
            if (cmds.isEmpty()) {
                lastNote = "no price change (window " + windowSold + " units)";
                return;
            }
            lastNote = changed + " price(s) changed (window " + windowSold + " units)";
            plugin.getLogger().info("Crops market: " + String.join(", ", moved));
            applyChanges(cmds);
        } catch (Exception e) {
            lastNote = "review failed: " + e.getMessage();
            plugin.getLogger().warning("Crops market: review failed: " + e.getMessage());
        } finally {
            busy = false;
        }
    }

    /** Write prices through EconomyShopGUI's own command, then reload its shops. */
    private void applyChanges(final List<String> cmds) {
        Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    for (String c : cmds) {
                        try {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Crops market: '" + c + "' failed: " + e.getMessage());
                        }
                    }
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sreload");
                    } catch (Exception e) {
                        plugin.getLogger().warning("Crops market: /sreload failed: " + e.getMessage());
                    }
                } catch (Exception ignored) {}
            }
        };
        try {
            if (Bukkit.isPrimaryThread()) r.run();
            else Bukkit.getScheduler().runTask(plugin, r);
        } catch (Exception e) {
            plugin.getLogger().warning("Crops market: could not apply prices: " + e.getMessage());
        }
    }

    /**
     * Undo the automation: every multiplier back to 1x and the shop's base
     * prices written back through /editshop + /sreload (a crop we never moved
     * keeps whatever price it has - we only touch what we changed).
     */
    public void reset() {
        List<String> cmds = new ArrayList<>();
        try {
            if (crops.isEmpty()) reloadCrops();
            refreshPrices();
            for (Crop c : crops.values()) {
                if (c.base <= 0.0) {
                    if (c.price > 0.0) c.base = c.price;
                    c.mult = 1.0;
                    c.applied = -1.0;
                    c.soldSince = 0L;
                    continue;
                }
                c.mult = 1.0;
                c.soldSince = 0L;
                double price = round(c.base, 2);
                if (price < minPrice()) price = minPrice();
                if (Math.abs(price - c.price) < 0.005) {
                    c.applied = -1.0;
                    continue;
                }
                cmds.add("editshop edititem " + shopSection() + " " + c.page + ".items." + c.key
                        + " set sell " + fmt(price));
                c.applied = price;
            }
            sold.clear();
            lastChanged = cmds.size();
            lastSold = 0L;
            lastNote = cmds.isEmpty()
                    ? "reset - no price to restore"
                    : "reset - restored " + cmds.size() + " base price(s)";
            saveState();
            if (!cmds.isEmpty()) applyChanges(cmds);
        } catch (Exception e) {
            lastNote = "reset failed: " + e.getMessage();
            plugin.getLogger().warning("Crops market: reset failed: " + e.getMessage());
        }
    }

    // ---------- read-out ----------

    public List<Crop> crops() {
        return new ArrayList<>(crops.values());
    }

    /** Board order: biggest movers first, then alphabetical. */
    public List<Crop> boardOrder() {
        List<Crop> list = crops();
        list.sort(new Comparator<Crop>() {
            @Override public int compare(Crop a, Crop b) {
                double ma = Math.abs(1.0 - a.mult);
                double mb = Math.abs(1.0 - b.mult);
                if (Math.abs(ma - mb) > 0.001) return Double.compare(mb, ma);
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    public long lastReviewMs() {
        return lastReviewMs;
    }

    public long lastSold() {
        return lastSold;
    }

    public int lastChanged() {
        return lastChanged;
    }

    public String lastNote() {
        return lastNote;
    }

    /** Human-readable state for /cropsmarket info. */
    public List<String> infoLines() {
        List<String> out = new ArrayList<>();
        out.add("Crops market: " + (enabled() ? "enabled" : "disabled")
                + " | hook: " + (hooked ? "EconomyShopGUI" : "not installed")
                + " | shop: " + shopSection());
        long mins = -1;
        if (lastReviewMs > 0L) mins = Math.max(0L, (System.currentTimeMillis() - lastReviewMs) / 60000L);
        out.add("window: " + reviewMinutes() + " min, heavy-volume " + fmt(heavyVolume())
                + " | multiplier " + fmt(minMultiplier()) + ".." + fmt(maxMultiplier())
                + " | down " + fmt(stepPercent()) + "% / up " + fmt(recoveryPercent()) + "% per review");
        out.add("last review: " + (mins < 0 ? "never" : mins + " min ago") + " | " + lastNote);
        for (Crop c : boardOrder()) {
            String arrow = c.mult > 1.001 ? "^" : (c.mult < 0.999 ? "v" : "=");
            out.add(String.format(Locale.ROOT, "  %-18s %s %7s (base %s, x%.3f) sold %d",
                    c.label, arrow, fmt(c.price), fmt(c.base), c.mult, c.soldSince));
        }
        return out;
    }

    // ---------- helpers ----------

    public static double round(double v, int places) {
        double f = Math.pow(10.0, places);
        return Math.round(v * f) / f;
    }

    public static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** SUGAR_CANE -> "Sugar Cane" */
    public static String label(String material) {
        try {
            String[] parts = material.toLowerCase(Locale.ROOT).split("_");
            StringBuilder b = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty()) continue;
                if (b.length() > 0) b.append(' ');
                b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
            return b.length() == 0 ? material : b.toString();
        } catch (Exception e) {
            return material;
        }
    }
}
