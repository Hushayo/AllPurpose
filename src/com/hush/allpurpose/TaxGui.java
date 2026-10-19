package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code /tax} screen: every inspect output (fund, earmarks, rates,
 * patrons, the weekly prize draw, the ledger, spends, grants and draws)
 * rendered as items whose hover tooltips carry the detail - so the wall of
 * chat lines {@code /tax} used to print never hits a player's client.
 *
 * One 54-slot screen, no sub-pages: every list the manager keeps is short
 * (top 5 patrons, 10 ledger entries), so it fits in hover lore.
 *
 * Console/RCON keeps the plain-text path in {@link TaxCommand} - a chest GUI
 * can only be opened for a player, never for the console.
 */
public final class TaxGui {

    private TaxGui() {}

    /** Marks the inventory as ours so MenuListener routes (and swallows) its clicks. */
    public static final class Holder implements InventoryHolder {
        public final java.util.UUID viewer;
        public Inventory inv;
        Holder(java.util.UUID viewer) { this.viewer = viewer; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final String TITLE = "§8§l» §6§lCOMMUNITY FUND §8§l«";

    private static final int SLOT_FUND = 4;
    private static final int SLOT_BUCKETS = 10;
    private static final int SLOT_RATES = 12;
    private static final int SLOT_PATRONS = 14;
    private static final int SLOT_PRIZE = 16;
    private static final int SLOT_LEDGER = 19;
    private static final int SLOT_SPENDS = 21;
    private static final int SLOT_GRANTS = 23;
    private static final int SLOT_DRAWS = 25;
    private static final int SLOT_INFO = 31;
    private static final int SLOT_REFRESH = 45;
    private static final int SLOT_CLOSE = 49;

    // ---------- open / render ----------

    public static void open(Player p, AllPurpose plugin) {
        Holder h = new Holder(p.getUniqueId());
        Inventory inv = Bukkit.createInventory(h, 54, TITLE);
        h.inv = inv;
        render(inv, p, plugin);
        p.openInventory(inv);
    }

    private static void render(Inventory inv, Player p, AllPurpose plugin) {
        Menus.fill(inv, Menus.GRAY);
        WealthTaxManager t = plugin.getWealthTax();
        if (t == null) {
            inv.setItem(22, Menus.item(Material.BARRIER, "§cTax manager not available",
                    "§7The wealth tax failed to initialise.",
                    "§7Check the console on startup."));
            inv.setItem(SLOT_CLOSE, Menus.item(Material.BARRIER, "§c§lClose"));
            return;
        }

        boolean admin = p.hasPermission("allpurpose.tax.admin");

        inv.setItem(SLOT_FUND, fund(t));
        inv.setItem(SLOT_BUCKETS, buckets(t));
        inv.setItem(SLOT_PATRONS, patrons(t));
        inv.setItem(SLOT_PRIZE, prizeDraw(plugin));

        // Staff-only sections mirror the permission gates TaxCommand already had.
        if (admin) {
            inv.setItem(SLOT_RATES, rates(t));
            inv.setItem(SLOT_LEDGER, ledger(t));
            inv.setItem(SLOT_SPENDS, spends(t));
            inv.setItem(SLOT_GRANTS, grants(t));
            inv.setItem(SLOT_DRAWS, draws(t));
            inv.setItem(SLOT_INFO, info(t, plugin));
        }

        inv.setItem(SLOT_REFRESH, Menus.item(Material.COMPASS, "§e§lRefresh",
                "§7Rebuild this screen from the live ledger."));
        inv.setItem(SLOT_CLOSE, Menus.item(Material.BARRIER, "§c§lClose"));
    }

    // ---------- the items ----------

    private static ItemStack fund(WealthTaxManager t) {
        List<String> lore = new ArrayList<>();
        lore.add(t.balanceLine());
        if (t.lastEvent() > 0) {
            lore.add("§7Last audit: §f" + WealthTaxManager.fmtDate(t.lastEvent())
                    + " §7collected §a" + Menus.plain(t.lastTotal()));
        } else {
            lore.add("§7No audit has landed yet - the realm is untouched.");
        }
        lore.add("");
        lore.add("§7Every deduction lands here, then splits");
        lore.add("§7across the earmarks on the next item.");
        return Menus.item(Material.GOLD_INGOT, "§6§lCommunity Fund", lore.toArray(new String[0]));
    }

    private static ItemStack buckets(WealthTaxManager t) {
        List<String> lore = new ArrayList<>(t.bucketLines());
        if (lore.isEmpty()) lore.add("§7No earmarks configured.");
        lore.add("");
        lore.add("§7Each purpose has its own sub-balance, so");
        lore.add("§7one can never eat another's money.");
        return Menus.item(Material.CHEST, "§6§lEarmarks §7(sub-funds)", lore.toArray(new String[0]));
    }

    private static ItemStack rates(WealthTaxManager t) {
        List<String> lore = new ArrayList<>();
        double lower = 0.0;
        for (WealthTaxManager.Bracket b : t.brackets()) {
            String band = b.upTo < 0
                    ? ("above " + Menus.plain(lower))
                    : (Menus.plain(lower) + " §7- §f" + Menus.plain(b.upTo));
            lore.add("§7" + band + ": §f" + WealthTaxManager.trim(b.percent) + "%");
            if (b.upTo >= 0) lower = b.upTo;
        }
        lore.add("");
        lore.add("§7Only the slice inside each band is taxed,");
        lore.add("§7never the whole balance at the top rate.");
        lore.add("§7Cap: §f" + t.capSummary() + " §7per player, per event.");
        if (!t.exemptLower().isEmpty()) {
            lore.add("§7Exempt: §f" + String.join(", ", t.exemptLower()));
        }
        return Menus.item(Material.PAPER, "§6§lTax rates §7(progressive)", lore.toArray(new String[0]));
    }

    private static ItemStack patrons(WealthTaxManager t) {
        List<String> top = t.topPatrons(5);
        List<String> lore = new ArrayList<>();
        if (top.isEmpty()) lore.add("§7No patrons yet - nobody has been audited.");
        else {
            lore.addAll(top);
            lore.add("");
            lore.add("§7Every taxed player, biggest first.");
        }
        String name = top.isEmpty() ? null : patronName(top.get(0));
        if (name == null) {
            return Menus.item(Material.PLAYER_HEAD, "§6§lPatrons", lore.toArray(new String[0]));
        }
        try {
            return Menus.head(Bukkit.getOfflinePlayer(name), "§6§lPatrons", lore.toArray(new String[0]));
        } catch (Exception e) {
            return Menus.item(Material.PLAYER_HEAD, "§6§lPatrons", lore.toArray(new String[0]));
        }
    }

    private static ItemStack prizeDraw(AllPurpose plugin) {
        List<String> lore = new ArrayList<>();
        try {
            PrizeDrawManager pd = plugin.getPrizeDraw();
            if (pd == null) {
                lore.add("§7Prize draw: §cnot available");
            } else if (!pd.enabled()) {
                lore.add("§7Prize draw: §cdisabled");
                lore.add("§7Amount: §f" + Menus.plain(pd.amount()) + " §7per week.");
            } else {
                lore.add("§7Amount: §a" + Menus.plain(pd.amount()) + " §7every §f"
                        + pd.weekdayLabel() + " " + pd.hourUtc() + ":00 UTC");
                lore.add("§7Next draw: §f" + pd.nextLabel());
                if (!pd.lastWinner().isEmpty()) {
                    lore.add("§7Last winner: §f" + pd.lastWinner() + " §8"
                            + WealthTaxManager.fmtDate(pd.lastDraw()));
                } else {
                    lore.add("§7No draw has happened yet.");
                }
                lore.add("");
                lore.add("§7One active player a week, paid from");
                lore.add("§7the §fPrizes§7 earmark.");
            }
        } catch (Exception e) {
            lore.add("§7Prize draw: §cnot available");
        }
        return Menus.item(Material.CLOCK, "§6§lWeekly prize draw", lore.toArray(new String[0]));
    }

    private static ItemStack ledger(WealthTaxManager t) {
        List<String> ev = t.ledgerEvents();
        List<String> lore = new ArrayList<>();
        if (ev.isEmpty()) lore.add("§7No tax events recorded yet.");
        else {
            lore.addAll(ev);
            lore.add("");
            lore.add("§8date | collected | payers | cap | mode");
        }
        return Menus.item(Material.BOOK, "§6§lRecent tax events", lore.toArray(new String[0]));
    }

    private static ItemStack spends(WealthTaxManager t) {
        List<String> sp = t.ledgerSpends();
        List<String> lore = new ArrayList<>();
        if (sp.isEmpty()) lore.add("§7No treasury payouts recorded yet.");
        else {
            lore.addAll(sp);
            lore.add("");
            lore.add("§8date | amount | bucket | to | by | reason");
        }
        return Menus.item(Material.WRITTEN_BOOK, "§6§lRecent treasury payouts", lore.toArray(new String[0]));
    }

    private static ItemStack grants(WealthTaxManager t) {
        List<String> g = t.grantList();
        List<String> lore = new ArrayList<>();
        lore.add("§7Paid from the §fStarter grants§7 earmark.");
        lore.add("");
        if (g.isEmpty()) lore.add("§7No starter grants paid yet.");
        else lore.addAll(g);
        return Menus.item(Material.EMERALD,
                "§6§lStarter grants §7(" + t.grantCount() + " paid)", lore.toArray(new String[0]));
    }

    private static ItemStack draws(WealthTaxManager t) {
        List<String> d = t.prizeDraws(10);
        List<String> lore = new ArrayList<>();
        if (d.isEmpty()) lore.add("§7No prize draws recorded yet.");
        else lore.addAll(d);
        return Menus.item(Material.FIREWORK_ROCKET, "§6§lPrize draws", lore.toArray(new String[0]));
    }

    private static ItemStack info(WealthTaxManager t, AllPurpose plugin) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Enabled: §f" + t.enabled()
                + " §8| §7daily chance: §f" + WealthTaxManager.trim(t.chance()) + "%");
        lore.add("§7Cap per event: §f" + t.capSummary());
        lore.add("§7Last roll day: §f" + (t.lastRollDay().isEmpty() ? "never" : t.lastRollDay())
                + " §8| §7events: §f" + t.events());
        lore.add("");
        lore.add("§7Payout caps: §fper payout " + Menus.plain(capD(plugin, "tax.max-payout", 25000.0))
                + " §8| §7bucket/day " + Menus.plain(capD(plugin, "tax.daily-payout-cap", 50000.0)));
        lore.add("§7Repeat window: §f"
                + WealthTaxManager.trim(t.payRepeatWindowMillis() / 60000.0) + "m");
        lore.add("");
        String acct = t.treasuryAccount();
        if (!acct.isEmpty()) {
            lore.add("§7Treasury account §f" + acct + "§7: §a" + Menus.plain(t.treasuryLiveBalance()));
        } else {
            lore.add("§7Treasury: §fvirtual ledger §7(balance = raised - spent).");
        }
        return Menus.item(Material.COMPARATOR, "§6§lTax settings", lore.toArray(new String[0]));
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
        int slot = e.getRawSlot();
        if (slot == SLOT_CLOSE) { p.closeInventory(); return; }
        if (slot == SLOT_REFRESH) { render(top, p, plugin); return; }
    }

    // ---------- helpers ----------

    /** Pulls the plain name out of a {@code topPatrons} line ("§71. §fName §7- §a$X"). */
    private static String patronName(String line) {
        try {
            String s = strip(line);
            int dot = s.indexOf(". ");
            if (dot >= 0) s = s.substring(dot + 2);
            int dash = s.lastIndexOf(" - ");
            if (dash > 0) s = s.substring(0, dash);
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String strip(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    private static double capD(AllPurpose plugin, String key, double fallback) {
        try { return plugin.getConfig().getDouble(key, fallback); } catch (Exception e) { return fallback; }
    }
}
