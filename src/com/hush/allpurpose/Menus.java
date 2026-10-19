package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Shared helpers for the GUI-driven money systems (coinflip + bounty):
 * item building, money formatting, amount parsing/stepping, and the
 * "type the amount in chat" prompt registry.
 *
 * Stateless except the prompt registry, which is written on the main thread
 * (opening a prompt) and read on the async chat thread (consuming it) - hence
 * the concurrent map.
 */
public final class Menus {

    private Menus() {}

    /** Slot of the "back/close" arrow in the 27/36/54 sized screens. */
    public static final ItemStack BLANK = item(Material.BLACK_STAINED_GLASS_PANE, " ");
    public static final ItemStack GRAY = item(Material.GRAY_STAINED_GLASS_PANE, " ");

    /** Vault economy provider (EssentialsX Economy on this server), or null. */
    public static Economy economy() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            return rsp == null ? null : rsp.getProvider();
        } catch (Exception e) {
            return null;
        }
    }

    public static ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                if (name != null) meta.setDisplayName(name);
                if (lore != null && lore.length > 0) {
                    List<String> l = new ArrayList<>();
                    for (String s : lore) l.add(s == null ? "" : s);
                    meta.setLore(l);
                }
                it.setItemMeta(meta);
            }
        } catch (Exception ignored) {}
        return it;
    }

    /** Player head, safe for offline/unknown players. */
    public static ItemStack head(OfflinePlayer who, String name, String... lore) {
        ItemStack it = item(Material.PLAYER_HEAD, name, lore);
        if (who == null) return it;
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta instanceof SkullMeta) {
                ((SkullMeta) meta).setOwningPlayer(who);
                it.setItemMeta(meta);
            }
        } catch (Exception ignored) {}
        return it;
    }

    /** Adds the enchant glint so a "winner" head stands out. */
    public static ItemStack glowing(ItemStack it) {
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                try {
                    meta.setEnchantmentGlintOverride(Boolean.TRUE);
                } catch (Throwable t) {
                    meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                }
                it.setItemMeta(meta);
            }
        } catch (Exception ignored) {}
        return it;
    }

    public static void fill(Inventory inv, ItemStack filler) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    public static void fillEmpty(Inventory inv, ItemStack filler) {
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
    }

    public static String plain(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1.0e15) return "$" + String.format(Locale.ROOT, "%,.0f", v);
        return "$" + String.format(Locale.ROOT, "%,.2f", v);
    }

    /** Economy-formatted amount (EssentialsX "$1,000.00") with a plain fallback. */
    public static String money(Economy econ, double v) {
        if (econ != null) {
            try {
                String s = econ.format(v);
                if (s != null && !s.trim().isEmpty()) return s;
            } catch (Exception ignored) {}
        }
        return plain(v);
    }

    /**
     * Short money for sidebars / holograms, no "$" and no thousands separator:
     *   812.65 -> "812.65"   98812.65 -> "98.8K"   1000 -> "1K"
     *   1200 -> "1.2K"       1200000 -> "1.2M"     1.2e9 -> "1.2B"
     * One decimal from K up (trailing ".0" dropped), 2 below 1K. A value that
     * rounds up to 1000 of its unit is promoted (999999 -> "1M", not "1000K").
     */
    public static String shortNumber(double v) {
        final String[] u = {"", "K", "M", "B", "T", "Q"};
        boolean neg = v < 0;
        double a = Math.abs(v);
        int i = 0;
        while (a >= 1000.0 && i < u.length - 1) { a /= 1000.0; i++; }
        if (i == 0) {
            // Below 1K: keep both cents (a balance of 812.65 must not read 812.70).
            double r = Math.round(a * 100.0) / 100.0;
            String s = (r == Math.rint(r))
                    ? String.format(Locale.ROOT, "%,.0f", r)
                    : String.format(Locale.ROOT, "%,.2f", r);
            return (neg ? "-" : "") + s;
        }
        double r = Math.round(a * 10.0) / 10.0;
        if (r >= 1000.0 && i < u.length - 1) { a /= 1000.0; i++; r = Math.round(a * 10.0) / 10.0; }
        String s = (r == Math.rint(r))
                ? String.format(Locale.ROOT, "%.0f", r)
                : String.format(Locale.ROOT, "%.1f", r);
        return (neg ? "-" : "") + s + u[i];
    }

    /** "999" / "1.5k" / "2m" / "$10,000" -> amount, or null when unparseable. */
    public static Double parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replace(",", "").replace("$", "").replace("_", "").replace(" ", "");
        if (s.isEmpty()) return null;
        double mult = 1.0;
        if (s.endsWith("k")) { mult = 1_000.0; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1_000_000.0; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1_000_000_000.0; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s.trim()) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0 || v > 1.0e12) return null;
            return Math.floor(v);
        } catch (Exception e) {
            return null;
        }
    }

    public static double clamp(double v, double min, double max) {
        if (max < min) max = min;
        return Math.max(min, Math.min(max, v));
    }

    // ---------- "type the amount in chat" prompt ----------

    /** Runs on the MAIN thread (callers schedule); Player may be offline by then. */
    public interface InputHandler {
        void accept(Player p, double amount);
    }

    private static final class Pending {
        final double min;
        final double max;
        final InputHandler handler;
        final long at = System.currentTimeMillis();
        Pending(double min, double max, InputHandler handler) {
            this.min = min;
            this.max = max;
            this.handler = handler;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    public static void prompt(Player p, double min, double max, InputHandler handler) {
        PENDING.put(p.getUniqueId(), new Pending(min, max, handler));
    }

    public static boolean awaiting(UUID id) {
        Pending pe = PENDING.get(id);
        if (pe == null) return false;
        if (System.currentTimeMillis() - pe.at > 90_000L) { PENDING.remove(id); return false; }
        return true;
    }

    public static void clear(UUID id) {
        PENDING.remove(id);
    }

    /**
     * Consumes a chat line while a prompt is open. Returns true when the line
     * was ours (caller cancels the chat event). Called on the async chat
     * thread; the handler is invoked on the main thread.
     */
    public static boolean consume(final Player p, String raw) {
        final UUID id = p.getUniqueId();
        final Pending pe = PENDING.get(id);
        if (pe == null) return false;
        if (System.currentTimeMillis() - pe.at > 90_000L) { PENDING.remove(id); return false; }
        if (raw == null) return false;
        String t = raw.trim();
        if (t.equalsIgnoreCase("cancel") || t.equalsIgnoreCase("exit") || t.equalsIgnoreCase("q")) {
            PENDING.remove(id);
            p.sendMessage("§7Cancelled.");
            return true;
        }
        PENDING.remove(id);
        Double v = parseAmount(t);
        if (v == null) {
            p.sendMessage("§cThat is not an amount. Nothing changed.");
            return true;
        }
        if (v < pe.min || v > pe.max) {
            p.sendMessage("§cAmount must be between §f" + plain(pe.min) + " §cand §f" + plain(pe.max) + "§c.");
            return true;
        }
        final double amt = v;
        Bukkit.getScheduler().runTask(p.getServer().getPluginManager().getPlugin("AllPurpose"), new Runnable() {
            @Override public void run() {
                try { pe.handler.accept(p, amt); } catch (Exception ignored) {}
            }
        });
        return true;
    }
}
