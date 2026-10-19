package com.hush.allpurpose;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

/**
 * Wealth tax ("prosperity audit"): a random, capped, PROGRESSIVE levy on the
 * richest visible balances, funding a public treasury.
 *
 *   - one check per UTC day; each check has a configurable chance to fire, so
 *     most weeks are quiet and a tax-free week is common;
 *   - progressive/marginal bands - only the slice inside a band is taxed;
 *   - a hard per-player cap per event, so one whale can never be drained;
 *   - exempt accounts; bots and non-human accounts are never taxed;
 *   - a manual run is PREVIEW-first: /tax run shows who would be taxed and how
 *     much without touching balances; only /tax run confirm actually collects;
 *   - every deduction and payout is written to a ledger (tax.yml), and payouts
 *     record the staff member, target, bucket and a REQUIRED reason;
 *   - the balance is earmarked into sub-balances (insurance / starter grants /
 *     prizes) so one fund can never eat another.
 *
 * The treasury is VIRTUAL: the ledger holds the balance, there is no bank
 * account. Because a payout can never exceed its earmarked balance, the money
 * supply can only shrink or stay flat - payouts can never inflate it.
 *
 * Balances are read from Essentials userdata (the same source /baltop uses),
 * honouring baltop-exempt. Withdrawals go through Vault, so offline players
 * are taxed too. Ships DISABLED - nothing happens until tax.enabled is true.
 */
public class WealthTaxManager {

    /** One progressive band. upTo &lt; 0 = open-ended top band. */
    public static final class Bracket {
        public final double upTo;
        public final double percent;
        public Bracket(double upTo, double percent) {
            this.upTo = upTo;
            this.percent = percent;
        }
    }

    /** A taxable account resolved from Essentials userdata. */
    private static final class Candidate {
        final UUID id;
        final String name;
        final double balance;
        final double tax;
        Candidate(UUID id, String name, double balance, double tax) {
            this.id = id;
            this.name = name;
            this.balance = balance;
            this.tax = tax;
        }
    }

    /** Non-human accounts deliberately NOT in bots.yml (see AGENTS 0.1): never taxed. */
    private static final Set<String> NON_HUMAN = new HashSet<>(Arrays.asList(
            "mcpvpgg", "ritgo_73", "ritgo_67"));

    private static final String[] DEFAULT_BUCKETS = {"insurance", "starter-grant", "prize"};
    private static final double[] DEFAULT_PCTS = {70.0, 20.0, 10.0};

    private final AllPurpose plugin;
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private BukkitTask task;
    private volatile boolean busy = false;
    /** Rolled cap of the last preview, reused by the next manual confirm so the two agree; -1 = none. */
    private volatile double pendingCap = -1.0;

    public WealthTaxManager(AllPurpose plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tax.yml");
    }

    public void start() {
        load();
        try {
            // Cheap one-minute heartbeat; the actual levy rolls once per UTC day.
            task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() { tick(); }
            }, 20L * 60L, 20L * 60L);
        } catch (Exception ignored) {}
        try {
            plugin.getLogger().info("[Tax] prosperity audit " + (enabled() ? "ARMED" : "disabled")
                    + " (daily chance " + trim(chance()) + "%, cap " + capSummary()
                    + "/player, " + brackets().size() + " bands, earmarks " + earmarkSummary() + ")");
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        try { if (task != null) task.cancel(); } catch (Exception ignored) {}
        save();
    }

    // ---------- config ----------

    private double cfgD(String k, double d) {
        try { return plugin.getConfig().getDouble(k, d); } catch (Exception e) { return d; }
    }
    private int cfgI(String k, int d) {
        try { return plugin.getConfig().getInt(k, d); } catch (Exception e) { return d; }
    }
    private boolean cfgB(String k, boolean d) {
        try { return plugin.getConfig().getBoolean(k, d); } catch (Exception e) { return d; }
    }
    private String cfgS(String k, String d) {
        try { String s = plugin.getConfig().getString(k, d); return s == null ? d : s; }
        catch (Exception e) { return d; }
    }

    public boolean enabled() { return cfgB("tax.enabled", false); }
    public double chance() { return Menus.clamp(cfgD("tax.daily-chance-percent", 15.0), 0.0, 100.0); }
    public double maxPerEvent() { return Math.max(0.0, cfgD("tax.max-per-event", 25000.0)); }
    /** Lowest rolled per-event cap, as a percent of max-per-event (100 = fixed, no random). */
    public double capFloorPercent() { return Menus.clamp(cfgD("tax.cap-floor-percent", 60.0), 0.0, 100.0); }
    public double minTax() { return Math.max(0.0, cfgD("tax.min-tax", 1.0)); }
    public boolean announce() { return cfgB("tax.announce", true); }
    public boolean discord() { return cfgB("tax.discord", true); }
    public boolean skipBots() { return cfgB("tax.skip-bots", true); }
    public boolean treasuryBaltopExempt() { return cfgB("tax.treasury-baltop-exempt", true); }
    public String treasuryAccount() { return cfgS("tax.treasury-account", "").trim(); }
    public int ledgerKeep() { return (int) Menus.clamp(cfgI("tax.ledger-keep", 200), 10, 5000); }
    public long payRepeatWindowMillis() {
        return (long) (Menus.clamp(cfgD("tax.pay-repeat-window-minutes", 1440.0), 0.0, 525600.0) * 60000L);
    }

    // ---------- per-event cap roll ----------

    /**
     * One cap for the WHOLE event: uniform in [cap-floor-percent%, 100%] of
     * max-per-event. Shared by every payer so nobody is singled out, and never
     * above max-per-event. With cap-floor-percent=100 the cap is fixed (old
     * behaviour).
     */
    public double rollEventCap() {
        double max = maxPerEvent();
        if (max <= 0.0) return 0.0;
        double pct = capFloorPercent();
        if (pct >= 100.0) return max;
        double lo = max * pct / 100.0;
        return lo + ThreadLocalRandom.current().nextDouble(max - lo);
    }

    /** Human summary of the cap roll, e.g. "15000 - 25000 (random per event)" or "25000". */
    public String capSummary() {
        double max = maxPerEvent();
        double lo = max * capFloorPercent() / 100.0;
        if (capFloorPercent() >= 100.0 || lo >= max) return Menus.plain(max);
        return Menus.plain(lo) + " §7- §f" + Menus.plain(max) + " §7(random per event)";
    }

    /**
     * Cap to use for an event. A preview rolls and LOCKS the cap; the next manual
     * confirm reuses it, so /tax run confirm collects exactly the numbers the
     * preview showed. Auto events always roll fresh.
     */
    private synchronized double resolveEventCap(boolean dryRun, boolean manual) {
        if (dryRun) { pendingCap = rollEventCap(); return pendingCap; }
        if (manual && pendingCap > 0.0) { double c = pendingCap; pendingCap = -1.0; return c; }
        pendingCap = -1.0;
        return rollEventCap();
    }

    public List<String> exemptLower() {
        List<String> out = new ArrayList<>();
        try {
            for (String s : plugin.getConfig().getStringList("tax.exempt-players")) {
                if (s != null && !s.trim().isEmpty()) out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---------- earmarks (sub-balances) ----------

    /** Configured bucket names, in config order; falls back to the 70/20/10 trio. */
    public List<String> earmarkNames() {
        List<String> out = new ArrayList<>();
        try {
            ConfigurationSection s = plugin.getConfig().getConfigurationSection("tax.earmarks");
            if (s != null) for (String k : s.getKeys(false)) {
                if (k != null && !k.trim().isEmpty()) out.add(k.trim());
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) out.addAll(Arrays.asList(DEFAULT_BUCKETS));
        return out;
    }

    private double rawPct(String b) {
        try { return Math.max(0.0, plugin.getConfig().getDouble("tax.earmarks." + b, 0.0)); }
        catch (Exception e) { return 0.0; }
    }

    /** Bucket share of every collection, normalised to 100% across the buckets. */
    public double earmarkPct(String b) {
        double sum = 0.0;
        List<String> names = earmarkNames();
        for (String n : names) sum += rawPct(n);
        if (sum > 0) return rawPct(b) / sum * 100.0;
        for (int i = 0; i < DEFAULT_BUCKETS.length; i++) {
            if (DEFAULT_BUCKETS[i].equals(b)) return DEFAULT_PCTS[i];
        }
        return 0.0;
    }

    public String earmarkSummary() {
        StringBuilder sb = new StringBuilder();
        for (String b : earmarkNames()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(bucketLabel(b)).append(' ').append(trim(earmarkPct(b))).append('%');
        }
        return sb.toString();
    }

    public double earmarkRaised(String b) { return data.getDouble("treasury.earmarks." + b + ".raised", 0.0); }
    public double earmarkSpent(String b) { return data.getDouble("treasury.earmarks." + b + ".spent", 0.0); }

    /** Spendable balance of one bucket (raised minus spent, never negative). */
    public double available(String bucket) {
        try {
            String b = (bucket == null || bucket.isEmpty()) ? "insurance" : bucket;
            if (!data.isConfigurationSection("treasury.earmarks")) return Math.max(0.0, treasuryBalance());
            return Math.max(0.0, earmarkRaised(b) - earmarkSpent(b));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void distributeToEarmarks(double total) {
        try {
            List<String> names = earmarkNames();
            String largest = null;
            double largestPct = -1.0;
            for (String b : names) {
                double p = earmarkPct(b);
                if (p > largestPct) { largestPct = p; largest = b; }
            }
            double remaining = total;
            for (String b : names) {
                double share = Math.floor(total * earmarkPct(b) / 100.0 * 100.0) / 100.0;
                if (share <= 0) continue;
                data.set("treasury.earmarks." + b + ".raised", earmarkRaised(b) + share);
                remaining -= share;
            }
            if (largest != null && remaining > 0.0001) {
                data.set("treasury.earmarks." + largest + ".raised", earmarkRaised(largest) + remaining);
            }
        } catch (Exception ignored) {}
    }

    /** Ordered bands; falls back to the shipped 0/1/2/3 progression. */
    public List<Bracket> brackets() {
        List<Bracket> out = new ArrayList<>();
        try {
            for (Map<?, ?> m : plugin.getConfig().getMapList("tax.brackets")) {
                try {
                    Object up = m.get("up-to");
                    Object pc = m.get("percent");
                    if (up == null || pc == null) continue;
                    double u = Double.parseDouble(String.valueOf(up));
                    double p = Double.parseDouble(String.valueOf(pc));
                    out.add(new Bracket(u, Menus.clamp(p, 0.0, 100.0)));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            out.add(new Bracket(50000.0, 0.0));
            out.add(new Bracket(100000.0, 1.0));
            out.add(new Bracket(250000.0, 2.0));
            out.add(new Bracket(-1.0, 3.0));
        }
        return out;
    }

    /** Progressive tax: only the slice inside each band is charged. */
    public double taxFor(double balance, List<Bracket> bs) {
        double tax = 0.0, lower = 0.0;
        try {
            for (Bracket b : bs) {
                if (balance <= lower) break;
                double upper = b.upTo < 0 ? Double.MAX_VALUE : b.upTo;
                double slice = Math.min(balance, upper) - lower;
                if (slice > 0) tax += slice * (b.percent / 100.0);
                if (upper == Double.MAX_VALUE) break;
                lower = upper;
            }
        } catch (Exception ignored) {}
        return Math.max(0.0, tax);
    }

    public double taxFor(double balance) { return taxFor(balance, brackets()); }

    // ---------- the daily roll ----------

    private void tick() {
        try {
            if (!enabled()) return;
            String today = LocalDate.now(ZoneOffset.UTC).toString();
            // First ever boot after enabling: never levy on day one.
            if (!data.isSet("state.last-roll-day")) {
                data.set("state.last-roll-day", today);
                save();
                return;
            }
            if (today.equals(data.getString("state.last-roll-day", ""))) return;
            data.set("state.last-roll-day", today);
            save();
            double p = chance();
            boolean fire = ThreadLocalRandom.current().nextDouble(100.0) < p;
            try {
                plugin.getLogger().info("[Tax] daily roll " + today + ": "
                        + (fire ? "LEVY" : "no levy") + " (chance " + trim(p) + "%)");
            } catch (Exception ignored) {}
            if (fire) runEvent(null, false);
        } catch (Exception ignored) {}
    }

    // ---------- an event ----------

    /** PREVIEW: report who would be taxed and how much, without touching balances. */
    public void previewEvent(CommandSender invoker) { startScan(invoker, false, true); }

    /**
     * Collect a tax event for real. {@code manual} skips the daily chance
     * (admin /tax run confirm) and is recorded that way in the ledger.
     */
    public void runEvent(CommandSender invoker, boolean manual) { startScan(invoker, manual, false); }

    private void startScan(final CommandSender invoker, final boolean manual, final boolean dryRun) {
        if (busy) { msg(invoker, "§cA tax event is already running."); return; }
        final Economy econ = Menus.economy();
        if (econ == null) { msg(invoker, "§cNo economy hooked (Vault missing)."); return; }
        final File dir;
        try { dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata"); }
        catch (Exception e) { msg(invoker, "§cCould not resolve Essentials userdata."); return; }
        busy = true;
        msg(invoker, dryRun ? "§7Previewing balances (nothing will be deducted)..."
                            : "§7Scanning balances...");
        final List<String> exempt = exemptLower();
        final boolean skipBots = skipBots();
        final List<Bracket> bands = brackets();
        // One cap for the whole event, rolled between cap-floor-percent% and 100%
        // of max-per-event. A preview locks it so /tax run confirm matches it.
        final double cap = resolveEventCap(dryRun, manual);
        final double minTax = minTax();
        final String treasury = treasuryAccount();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final List<Candidate> cands = new ArrayList<>();
                try {
                    File[] files = dir.listFiles();
                    if (files != null) for (File f : files) {
                        try {
                            String fn = f.getName();
                            if (!fn.endsWith(".yml")) continue;
                            UUID id;
                            try { id = UUID.fromString(fn.substring(0, fn.length() - 4)); }
                            catch (Exception ex) { continue; }
                            YamlConfiguration u = YamlConfiguration.loadConfiguration(f);
                            try { if (u.getBoolean("baltop-exempt", false)) continue; } catch (Exception ignored) {}
                            String name = u.getString("last-account-name", null);
                            if (name == null || name.trim().isEmpty()) continue;
                            double money;
                            try { money = Double.parseDouble(String.valueOf(u.get("money")).replace(",", "")); }
                            catch (Exception ex) { continue; }
                            String lower = name.trim().toLowerCase(Locale.ROOT);
                            if (exempt.contains(lower)) continue;
                            if (skipBots && BotNames.isBot(plugin, name)) continue;
                            if (NON_HUMAN.contains(lower)) continue;
                            if (!treasury.isEmpty() && treasury.equalsIgnoreCase(name.trim())) continue;
                            double tax = Math.min(taxFor(money, bands), cap);
                            if (tax < minTax) continue;
                            cands.add(new Candidate(id, name.trim(), money, tax));
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
                Collections.sort(cands, new java.util.Comparator<Candidate>() {
                    @Override public int compare(Candidate a, Candidate b) { return Double.compare(b.tax, a.tax); }
                });
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        try {
                            if (dryRun) reportPreview(cands, invoker, cap);
                            else apply(cands, econ, manual, invoker, cap);
                        } catch (Exception e) {
                            if (dryRun) busy = false;
                            msg(invoker, "§cTax scan failed: " + e.getMessage());
                        }
                    }
                });
            }
        });
    }

    private void reportPreview(List<Candidate> cands, CommandSender invoker, double cap) {
        try {
            if (cands.isEmpty()) {
                msg(invoker, "§7Preview: no balance is over the first taxable band - a real run would collect nothing.");
                try { plugin.getLogger().info("[Tax] preview: nothing taxable."); } catch (Exception ignored) {}
                return;
            }
            double total = 0.0;
            msg(invoker, "§6§lTax preview §7(nothing deducted) - §f" + cands.size() + " §7account(s) would be taxed:");
            msg(invoker, "§7This event's per-player cap: §f" + Menus.plain(cap));
            for (int i = 0; i < cands.size(); i++) {
                Candidate c = cands.get(i);
                total += c.tax;
                if (i < 25) {
                    msg(invoker, "§7- §f" + c.name + " §7balance §f" + Menus.plain(c.balance)
                            + " §7-> tax §c" + Menus.plain(c.tax));
                }
            }
            if (cands.size() > 25) msg(invoker, "§7... and §f" + (cands.size() - 25) + "§7 more.");
            msg(invoker, "§7Would collect: §a" + Menus.plain(total));
            msg(invoker, "§eRun §f/tax run confirm §eto execute this for real.");
            try {
                StringBuilder sb = new StringBuilder();
                for (Candidate c : cands) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(c.name).append('=').append(Menus.plain(c.tax));
                }
                plugin.getLogger().info("[Tax] preview: " + cands.size() + " account(s), total "
                        + Menus.plain(total) + " -> " + sb);
            } catch (Exception ignored) {}
        } finally {
            busy = false;
        }
    }

    private void apply(List<Candidate> cands, Economy econ, boolean manual, CommandSender invoker, double cap) {
        try {
            final String treasury = treasuryAccount();
            double total = 0.0;
            Map<String, Double> applied = new LinkedHashMap<>();
            for (Candidate c : cands) {
                try {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(c.id);
                    EconomyResponse r = econ.withdrawPlayer(op, c.tax);
                    if (r != null && r.transactionSuccess()) {
                        total += c.tax;
                        applied.put(c.name, c.tax);
                    }
                } catch (Exception ignored) {}
            }
            if (applied.isEmpty()) {
                msg(invoker, "§7No balance is over the first taxable band - nothing collected.");
                return;
            }
            if (!treasury.isEmpty() && total > 0) {
                try {
                    econ.depositPlayer(Bukkit.getOfflinePlayer(treasury), total);
                    ensureTreasuryExempt(treasury);
                } catch (Exception ignored) {}
            }
            recordEvent(System.currentTimeMillis(), total, applied, manual ? "manual" : "auto", cap);
            announce(total, applied);
            try {
                plugin.getLogger().info("[Tax] " + (manual ? "MANUAL" : "AUTO") + " event: collected "
                        + Menus.plain(total) + " from " + applied.size() + " accounts (cap "
                        + Menus.plain(cap) + "): " + applied);
            } catch (Exception ignored) {}
            msg(invoker, "§aCollected " + Menus.money(econ, total) + " from " + applied.size()
                    + " account(s). Treasury balance: " + Menus.plain(treasuryBalance()));
        } catch (Exception e) {
            msg(invoker, "§cTax event failed: " + e.getMessage());
        } finally {
            busy = false;
        }
    }

    private void announce(double total, Map<String, Double> applied) {
        String line = "§6§l⚡ PROSPERITY AUDIT §r§7- the realm taxed its largest hoards: §a"
                + Menus.plain(total) + " §7collected from §f" + applied.size() + " §7account"
                + (applied.size() == 1 ? "" : "s") + ". §7Fund: §a" + Menus.plain(treasuryBalance()) + "§7.";
        try { if (announce()) Bukkit.broadcastMessage(line); } catch (Exception ignored) {}
        try {
            if (discord()) say("Prosperity Audit: " + Menus.plain(total) + " collected from "
                    + applied.size() + " accounts. Fund: " + Menus.plain(treasuryBalance()) + ".");
        } catch (Exception ignored) {}
    }

    // ---------- treasury + ledger ----------

    public double raised() { return data.getDouble("treasury.raised", 0.0); }
    public double spent() { return data.getDouble("treasury.spent", 0.0); }
    public int events() { return data.getInt("treasury.events", 0); }
    public long lastEvent() { return data.getLong("treasury.last-event", 0L); }
    public double lastTotal() { return data.getDouble("treasury.last-total", 0.0); }
    public String lastRollDay() { return data.getString("state.last-roll-day", ""); }

    /** Ledger balance (raised minus recorded spends). Never negative after payouts. */
    public double treasuryBalance() { return raised() - spent(); }

    /** Live balance of the treasury ACCOUNT when one is configured, else the ledger balance. */
    public double treasuryLiveBalance() {
        try {
            String n = treasuryAccount();
            if (n.isEmpty()) return treasuryBalance();
            Economy e = Menus.economy();
            if (e == null) return treasuryBalance();
            return e.getBalance(Bukkit.getOfflinePlayer(n));
        } catch (Exception ex) {
            return treasuryBalance();
        }
    }

    /** Public-facing one-line summary for /tax balance. */
    public String balanceLine() {
        return "§7Fund balance: §a" + Menus.plain(treasuryBalance())
                + " §8| §7raised §a" + Menus.plain(raised())
                + " §8| §7spent §c" + Menus.plain(spent())
                + " §8| §7events §f" + events();
    }

    /** Per-bucket balance lines for /tax info and /tax balance. */
    public List<String> bucketLines() {
        List<String> out = new ArrayList<>();
        try {
            for (String b : earmarkNames()) {
                out.add("§7- §f" + bucketLabel(b) + "§7: §a" + Menus.plain(available(b))
                        + " §8(" + trim(earmarkPct(b)) + "%, raised " + Menus.plain(earmarkRaised(b))
                        + ", spent " + Menus.plain(earmarkSpent(b)) + ")");
            }
        } catch (Exception ignored) {}
        return out;
    }

    private synchronized void recordEvent(long millis, double total, Map<String, Double> applied, String mode, double cap) {
        data.set("treasury.raised", raised() + total);
        data.set("treasury.events", events() + 1);
        data.set("treasury.last-event", millis);
        data.set("treasury.last-total", total);
        // A collection - manual (/tax run confirm) or the random roll - marks the
        // UTC day as rolled, so the random roll can never stack a second audit on
        // a day that has already been collected.
        data.set("state.last-roll-day", LocalDate.now(ZoneOffset.UTC).toString());
        distributeToEarmarks(total);
        String base = "ledger.events." + millis;
        data.set(base + ".total", total);
        data.set(base + ".count", applied.size());
        data.set(base + ".mode", mode);
        data.set(base + ".cap", cap);
        for (Map.Entry<String, Double> en : applied.entrySet()) {
            data.set(base + ".players." + en.getKey(), en.getValue());
        }
        trimLedger();
        save();
    }

    /** Records a treasury outflow that moves no money (bookkeeping only). */
    public synchronized void spend(double amount, String note) {
        long millis = System.currentTimeMillis();
        String base = "ledger.spends." + millis;
        data.set(base + ".amount", amount);
        data.set(base + ".note", note == null ? "" : note);
        data.set(base + ".kind", "manual");
        data.set(base + ".by", "console");
        data.set("treasury.spent", spent() + amount);
        trimLedger();
        save();
    }

    // ---------- payouts ----------

    /**
     * Moves money out of ONE earmark bucket to a player and records the spend
     * (staff member, target, bucket, reason). A payout can never exceed its
     * bucket, so the fund can never go negative. Returns true on success.
     */
    public synchronized boolean payFromTreasury(UUID id, String name, double amount, String note,
                                                String kind, String bucket, String by) {
        try {
            if (id == null || amount <= 0) return false;
            Economy e = Menus.economy();
            if (e == null) return false;
            String b = (bucket == null || bucket.isEmpty()) ? "insurance" : bucket;
            if (available(b) + 1.0e-6 < amount) return false;
            String acct = treasuryAccount();
            boolean moved = false;
            if (!acct.isEmpty()) {
                OfflinePlayer t = Bukkit.getOfflinePlayer(acct);
                if (!e.has(t, amount)) return false;
                EconomyResponse w = e.withdrawPlayer(t, amount);
                if (w == null || !w.transactionSuccess()) return false;
                moved = true;
            }
            EconomyResponse d = e.depositPlayer(Bukkit.getOfflinePlayer(id), amount);
            if (d == null || !d.transactionSuccess()) {
                if (moved) {
                    try { e.depositPlayer(Bukkit.getOfflinePlayer(acct), amount); } catch (Exception ignored) {}
                }
                return false;
            }
            long millis = System.currentTimeMillis();
            String base = "ledger.spends." + millis;
            data.set(base + ".amount", amount);
            data.set(base + ".note", (note == null || note.isEmpty() ? "payout" : note));
            data.set(base + ".kind", (kind == null || kind.isEmpty() ? "payout" : kind));
            data.set(base + ".bucket", b);
            data.set(base + ".by", by == null ? "system" : by);
            data.set(base + ".to", id.toString());
            data.set(base + ".to-name", name == null ? "?" : name);
            data.set("treasury.spent", spent() + amount);
            data.set("treasury.earmarks." + b + ".spent", earmarkSpent(b) + amount);
            trimLedger();
            save();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * One-time starter grant, drawn from the starter-grant bucket. The account
     * is flagged before the money moves so a retry can never pay twice; the
     * flag is rolled back if the payout fails.
     */
    public synchronized boolean payStarterGrant(UUID id, String name, double amount) {
        try {
            data.set("grants." + id + ".name", name == null ? "?" : name);
            data.set("grants." + id + ".amount", amount);
            data.set("grants." + id + ".at", System.currentTimeMillis());
        } catch (Exception ignored) {}
        if (!payFromTreasury(id, name, amount, "starter grant: " + (name == null ? "?" : name),
                "grant", "starter-grant", "auto")) {
            try { data.set("grants." + id, null); } catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    /** True once this account has received its one-time starter grant. */
    public boolean grantClaimed(UUID id) {
        try { return id != null && data.isSet("grants." + id); } catch (Exception e) { return false; }
    }

    /** Millis the starter-grant feature was first armed, or 0. */
    public long grantArmedAt() { return data.getLong("state.grant-armed-at", 0L); }

    /** Stamps the arm time once, so accounts that existed before it are grandfathered. */
    public synchronized void stampGrantArmed() {
        try {
            if (data.getLong("state.grant-armed-at", 0L) == 0L) {
                data.set("state.grant-armed-at", System.currentTimeMillis());
                save();
            }
        } catch (Exception ignored) {}
    }

    public int grantCount() {
        try {
            ConfigurationSection s = data.getConfigurationSection("grants");
            return s == null ? 0 : s.getKeys(false).size();
        } catch (Exception e) { return 0; }
    }

    /** Newest-first summary of starter grants for /tax grants. */
    public List<String> grantList() {
        List<String> out = new ArrayList<>();
        try {
            ConfigurationSection s = data.getConfigurationSection("grants");
            if (s == null) return out;
            final java.util.Map<String, Long> at = new java.util.HashMap<>();
            for (String k : s.getKeys(false)) at.put(k, data.getLong("grants." + k + ".at", 0L));
            List<String> keys = new ArrayList<>(s.getKeys(false));
            Collections.sort(keys, new java.util.Comparator<String>() {
                @Override public int compare(String a, String b) {
                    return Long.compare(at.containsKey(b) ? at.get(b) : 0L, at.containsKey(a) ? at.get(a) : 0L);
                }
            });
            for (int i = 0; i < Math.min(keys.size(), 10); i++) {
                String k = keys.get(i);
                out.add("§7" + data.getString("grants." + k + ".name", "?") + " §8| §a"
                        + Menus.plain(data.getDouble("grants." + k + ".amount", 0.0))
                        + " §7" + fmtDate(data.getLong("grants." + k + ".at", 0L)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---------- payout accounting / evidence ----------

    /** Total manual payouts (kind=payout) so far today, UTC; optionally one bucket. */
    public double payoutToday(String bucket) {
        double sum = 0.0;
        try {
            ConfigurationSection s = data.getConfigurationSection("ledger.spends");
            if (s == null) return 0.0;
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            for (String k : s.getKeys(false)) {
                try {
                    long ms = Long.parseLong(k);
                    LocalDate d = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate();
                    if (!d.equals(today)) continue;
                    if (!"payout".equals(data.getString("ledger.spends." + k + ".kind", ""))) continue;
                    if (bucket != null && !bucket.isEmpty()
                            && !bucket.equals(data.getString("ledger.spends." + k + ".bucket", ""))) continue;
                    sum += data.getDouble("ledger.spends." + k + ".amount", 0.0);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return sum;
    }

    /** True if this staff member already paid this target within the window. */
    public boolean recentPayout(String by, UUID target, long windowMillis) {
        try {
            if (by == null || target == null || windowMillis <= 0) return false;
            long cutoff = System.currentTimeMillis() - windowMillis;
            ConfigurationSection s = data.getConfigurationSection("ledger.spends");
            if (s == null) return false;
            for (String k : s.getKeys(false)) {
                try {
                    if (Long.parseLong(k) < cutoff) continue;
                    if (!"payout".equals(data.getString("ledger.spends." + k + ".kind", ""))) continue;
                    if (!by.equalsIgnoreCase(data.getString("ledger.spends." + k + ".by", ""))) continue;
                    if (!target.toString().equals(data.getString("ledger.spends." + k + ".to", ""))) continue;
                    return true;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Ranked tax contributors (from the event ledger) for /tax patrons. */
    public List<String> topPatrons(int n) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, Double> totals = new java.util.HashMap<>();
            ConfigurationSection ev = data.getConfigurationSection("ledger.events");
            if (ev != null) for (String k : ev.getKeys(false)) {
                ConfigurationSection pl = data.getConfigurationSection("ledger.events." + k + ".players");
                if (pl == null) continue;
                for (String nm : pl.getKeys(false)) {
                    double v = data.getDouble("ledger.events." + k + ".players." + nm, 0.0);
                    totals.put(nm, (totals.containsKey(nm) ? totals.get(nm) : 0.0) + v);
                }
            }
            List<Map.Entry<String, Double>> es = new ArrayList<>(totals.entrySet());
            Collections.sort(es, new java.util.Comparator<Map.Entry<String, Double>>() {
                @Override public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                    return Double.compare(b.getValue(), a.getValue());
                }
            });
            for (int i = 0; i < Math.min(n, es.size()); i++) {
                out.add("§7" + (i + 1) + ". §f" + es.get(i).getKey() + " §7- §a"
                        + Menus.plain(es.get(i).getValue()) + " §7taxed");
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Newest-first summary lines for /tax ledger (events). */
    public List<String> ledgerEvents() {
        List<String> out = new ArrayList<>();
        try {
            ConfigurationSection s = data.getConfigurationSection("ledger.events");
            if (s == null) return out;
            List<String> keys = new ArrayList<>(s.getKeys(false));
            Collections.sort(keys, Collections.reverseOrder());
            for (int i = 0; i < Math.min(keys.size(), 10); i++) {
                String k = keys.get(i);
                double cap = data.getDouble("ledger.events." + k + ".cap", 0.0);
                out.add("§7" + fmtDate(Long.parseLong(k)) + " §8| §a"
                        + Menus.plain(data.getDouble("ledger.events." + k + ".total", 0.0))
                        + " §7from §f" + data.getInt("ledger.events." + k + ".count", 0)
                        + (cap > 0 ? " §8| §7cap §f" + Menus.plain(cap) : "")
                        + " §8(" + data.getString("ledger.events." + k + ".mode", "auto") + ")");
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Newest-first summary lines for /tax spends - the payout evidence trail. */
    public List<String> ledgerSpends() {
        List<String> out = new ArrayList<>();
        try {
            ConfigurationSection s = data.getConfigurationSection("ledger.spends");
            if (s == null) return out;
            List<String> keys = new ArrayList<>(s.getKeys(false));
            Collections.sort(keys, Collections.reverseOrder());
            for (int i = 0; i < Math.min(keys.size(), 10); i++) {
                String k = keys.get(i);
                out.add("§7" + fmtDate(Long.parseLong(k)) + " §8| §c"
                        + Menus.plain(data.getDouble("ledger.spends." + k + ".amount", 0.0))
                        + " §7" + bucketLabel(data.getString("ledger.spends." + k + ".bucket", "?"))
                        + " §8| §fto " + data.getString("ledger.spends." + k + ".to-name", "?")
                        + " §8| §7by " + data.getString("ledger.spends." + k + ".by", "?")
                        + " §8| §7" + data.getString("ledger.spends." + k + ".note", ""));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Newest-first lines for /tax draws - only the weekly prize-draw payouts. */
    public List<String> prizeDraws(int n) {
        List<String> out = new ArrayList<>();
        try {
            ConfigurationSection s = data.getConfigurationSection("ledger.spends");
            if (s == null) return out;
            List<String> keys = new ArrayList<>(s.getKeys(false));
            Collections.sort(keys, Collections.reverseOrder());
            for (String k : keys) {
                if (out.size() >= n) break;
                if (!"prize-draw".equals(data.getString("ledger.spends." + k + ".kind", ""))) continue;
                out.add("§7" + fmtDate(Long.parseLong(k)) + " §8| §f"
                        + data.getString("ledger.spends." + k + ".to-name", "?")
                        + " §7won §a" + Menus.plain(data.getDouble("ledger.spends." + k + ".amount", 0.0)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void trimLedger() {
        int keep = ledgerKeep();
        trimSection("ledger.events", keep);
        trimSection("ledger.spends", keep);
    }

    private void trimSection(String path, int keep) {
        try {
            ConfigurationSection s = data.getConfigurationSection(path);
            if (s == null) return;
            List<String> keys = new ArrayList<>(s.getKeys(false));
            if (keys.size() <= keep) return;
            Collections.sort(keys); // fixed-width epoch millis -> lexicographic == numeric
            for (int i = 0; i < keys.size() - keep; i++) data.set(path + "." + keys.get(i), null);
        } catch (Exception ignored) {}
    }

    /** Keeps the treasury account off /baltop by flagging it baltop-exempt. */
    private void ensureTreasuryExempt(String name) {
        try {
            if (!treasuryBaltopExempt()) return;
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            File dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata");
            if (!dir.isDirectory()) return;
            File f = new File(dir, id + ".yml");
            YamlConfiguration y = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            if (y.getBoolean("baltop-exempt", false) && name.equalsIgnoreCase(y.getString("last-account-name", ""))) return;
            y.set("baltop-exempt", true);
            if (!y.isSet("last-account-name")) y.set("last-account-name", name);
            y.save(f);
        } catch (Exception ignored) {}
    }

    // ---------- persistence ----------

    private void load() {
        try {
            if (file == null || !file.exists()) return;
            data.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Tax] load failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    public synchronized void save() {
        try {
            if (file != null) data.save(file);
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Tax] save failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    // ---------- misc ----------

    /** Plain-text line for the Discord chat log (no-op when it is disabled). */
    public void say(String line) {
        try {
            DiscordChatLog log = plugin.getChatLog();
            if (log != null) log.system(line);
        } catch (Exception ignored) {}
    }

    private void msg(CommandSender s, String t) {
        try { if (s != null) s.sendMessage(t); } catch (Exception ignored) {}
    }

    static String bucketLabel(String b) {
        if (b == null) return "?";
        if (b.equals("insurance")) return "Insurance";
        if (b.equals("starter-grant")) return "Starter grants";
        if (b.equals("prize")) return "Prizes";
        return b;
    }

    static String trim(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1.0e15) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    static String fmtDate(long millis) {
        try {
            return java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC";
        } catch (Exception e) {
            return String.valueOf(millis);
        }
    }
}
