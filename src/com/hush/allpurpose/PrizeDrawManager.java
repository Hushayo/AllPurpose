package com.hush.allpurpose;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

/**
 * Weekly prize draw: every week, one ACTIVE player is drawn at random and paid
 * a flat prize out of the wealth-tax treasury's "prize" bucket.
 *
 * It exists because the prize earmark otherwise just accumulates with nothing
 * to spend it on. The loop is: a prosperity audit fires (random) -> 10% lands in
 * the prize bucket -> the weekly draw pays one player out of it.
 *
 * "Active" is deliberately cheap and offline-safe - no Bukkit player objects:
 *   - the account appears in Essentials userdata (so it has actually played),
 *   - its `timestamps.login/logout` is within active-days (default 7),
 *   - its lifetime `minecraft:play_time` (world/players/stats) is at least
 *     min-playtime-minutes, so a one-login alt cannot win,
 *   - and it is not a bot (bots.yml), a non-human account (mcpvpgg/ritgo_*),
 *     the treasury account, or on the exclude list.
 *
 * Schedule: once per configured UTC slot (default Saturday 12:00 UTC = 20:00
 * local). It is armed on first enable, so it never fires the instant it is
 * switched on - the first draw waits for the next slot. A slot that passes
 * while the server is down is caught up on the next check. If the prize bucket
 * cannot cover the prize a slot is skipped (logged) rather than draining it.
 *
 * Safety: the payout goes through WealthTaxManager.payFromTreasury, so it is
 * capped per payout, capped per day, can never exceed the prize bucket, and is
 * recorded in /tax spends like any other payout. Manual draws are an extra -
 * they do not touch the weekly schedule.
 *
 * Ships DISABLED (prize-draw.enabled: false).
 */
public class PrizeDrawManager {

    /** One eligible account. */
    private static final class Pool {
        final UUID id;
        final String name;
        final long minutes;
        Pool(UUID id, String name, long minutes) { this.id = id; this.name = name; this.minutes = minutes; }
    }

    /** Non-human accounts deliberately NOT in bots.yml (see AGENTS 0.1). */
    private static final Set<String> NON_HUMAN = new HashSet<>(Arrays.asList(
            "mcpvpgg", "ritgo_73", "ritgo_67"));

    private static final Pattern PLAY_TIME = Pattern.compile("\"minecraft:play_time\"\\s*:\\s*(\\d+)");

    private final AllPurpose plugin;
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private BukkitTask task;
    private volatile boolean busy = false;

    public PrizeDrawManager(AllPurpose plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "prize.yml");
    }

    public void start() {
        load();
        arm();
        try {
            task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() { tick(); }
            }, 20L * 60L, 20L * 60L);
        } catch (Exception ignored) {}
        try {
            plugin.getLogger().info("[PrizeDraw] " + (enabled() ? "enabled" : "disabled")
                    + " (" + weekdayLabel() + " " + hourUtc() + ":00 UTC, " + Menus.plain(amount())
                    + ", next " + nextLabel() + ")");
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

    public boolean enabled() { return cfgB("prize-draw.enabled", false); }
    public double amount() { return Math.max(0.0, cfgD("prize-draw.amount", 5000.0)); }
    public double activeDays() { return Menus.clamp(cfgD("prize-draw.active-days", 7.0), 1.0, 365.0); }
    public long minPlaytimeMinutes() { return (long) Menus.clamp(cfgD("prize-draw.min-playtime-minutes", 120.0), 0.0, 1000000.0); }
    public boolean announce() { return cfgB("prize-draw.announce", true); }
    public boolean discord() { return cfgB("prize-draw.discord", true); }

    public List<String> excludeLower() {
        List<String> out = new ArrayList<>();
        try {
            for (String s : plugin.getConfig().getStringList("prize-draw.exclude-players")) {
                if (s != null && !s.trim().isEmpty()) out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public DayOfWeek dayOfWeek() {
        String s = cfgS("prize-draw.day", "saturday").trim().toUpperCase(Locale.ROOT);
        switch (s) {
            case "MON": case "MONDAY": case "1": return DayOfWeek.MONDAY;
            case "TUE": case "TUES": case "TUESDAY": case "2": return DayOfWeek.TUESDAY;
            case "WED": case "WEDNESDAY": case "3": return DayOfWeek.WEDNESDAY;
            case "THU": case "THUR": case "THURS": case "THURSDAY": case "4": return DayOfWeek.THURSDAY;
            case "FRI": case "FRIDAY": case "5": return DayOfWeek.FRIDAY;
            case "SAT": case "SATURDAY": case "6": return DayOfWeek.SATURDAY;
            case "SUN": case "SUNDAY": case "7": return DayOfWeek.SUNDAY;
            default: return DayOfWeek.SATURDAY;
        }
    }

    public int hourUtc() { return (int) Menus.clamp(cfgI("prize-draw.hour-utc", 12), 0, 23); }

    public String weekdayLabel() {
        String n = dayOfWeek().name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ---------- state ----------

    public long lastDraw() { return data.getLong("state.last-draw", 0L); }
    public String lastWinner() { return data.getString("state.last-winner", ""); }
    public double lastAmount() { return data.getDouble("state.last-amount", 0.0); }
    public int draws() { return data.getInt("state.draws", 0); }

    /** Arms the schedule on first run so enabling never draws immediately. */
    private void arm() {
        try {
            if (data.getLong("state.last-attempt", 0L) == 0L) {
                data.set("state.last-attempt", System.currentTimeMillis());
                save();
            }
        } catch (Exception ignored) {}
    }

    /** Most recent configured slot (UTC) at or before now, in millis. */
    public long currentSlot() {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime slot = now.with(TemporalAdjusters.previousOrSame(dayOfWeek()))
                    .withHour(hourUtc()).withMinute(0).withSecond(0).withNano(0);
            if (slot.isAfter(now)) slot = slot.minusWeeks(1);
            return slot.toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Next configured slot (UTC) strictly after now, in millis. */
    public long nextSlot() {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime slot = now.with(TemporalAdjusters.nextOrSame(dayOfWeek()))
                    .withHour(hourUtc()).withMinute(0).withSecond(0).withNano(0);
            if (!slot.isAfter(now)) slot = slot.plusWeeks(1);
            return slot.toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    public String nextLabel() {
        long ms = nextSlot();
        if (ms <= 0) return "not scheduled";
        try {
            return java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm")) + " UTC";
        } catch (Exception e) {
            return String.valueOf(ms);
        }
    }

    // ---------- schedule ----------

    private void tick() {
        try {
            if (!enabled()) return;
            long slot = currentSlot();
            if (slot <= 0) return;
            long last = data.getLong("state.last-attempt", 0L);
            if (last == 0L) { arm(); return; }
            if (last >= slot) return; // this week's slot already attempted
            // Mark the slot attempted first, so a crash or an underfunded skip
            // can never turn into a per-minute retry loop.
            data.set("state.last-attempt", System.currentTimeMillis());
            save();
            runNow(null);
        } catch (Exception ignored) {}
    }

    // ---------- public entry points ----------

    /** PREVIEW: report the eligible pool, prize and funding without paying. */
    public void preview(CommandSender invoker) { startScan(invoker, true); }

    /** Draw now (admin). Does not touch the weekly schedule. */
    public void runNow(CommandSender invoker) { startScan(invoker, false); }

    private void startScan(final CommandSender invoker, final boolean dryRun) {
        if (busy) { msg(invoker, "§cA prize draw is already running."); return; }
        busy = true;
        final double amt = amount();
        final long cutoff = System.currentTimeMillis() - (long) (activeDays() * 86400000L);
        final long minMinutes = minPlaytimeMinutes();
        final List<String> excludes = excludeLower();
        msg(invoker, dryRun ? "§7Checking who is eligible (no payout)..."
                            : "§7Drawing a winner...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final List<Pool> pool = buildPool(cutoff, minMinutes, excludes);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        try {
                            if (dryRun) reportPreview(pool, amt, invoker);
                            else pay(pool, amt, invoker);
                        } catch (Exception e) {
                            msg(invoker, "§cPrize draw failed: " + e.getMessage());
                        } finally {
                            busy = false;
                        }
                    }
                });
            }
        });
    }

    // ---------- pool ----------

    private List<Pool> buildPool(long cutoff, long minMinutes, List<String> excludes) {
        List<Pool> out = new ArrayList<>();
        try {
            Set<String> bots = BotNames.names(plugin);
            String treasury = "";
            try {
                WealthTaxManager t = plugin.getWealthTax();
                if (t != null) treasury = t.treasuryAccount();
            } catch (Exception ignored) {}
            File dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata");
            File[] files = dir.listFiles();
            if (files == null) return out;
            File statsDir = statsFolder();
            for (File f : files) {
                try {
                    String fn = f.getName();
                    if (!fn.endsWith(".yml")) continue;
                    UUID id;
                    try { id = UUID.fromString(fn.substring(0, fn.length() - 4)); } catch (Exception ex) { continue; }
                    YamlConfiguration u = YamlConfiguration.loadConfiguration(f);
                    String name = u.getString("last-account-name", null);
                    if (name == null || name.trim().isEmpty()) continue;
                    name = name.trim();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (bots.contains(lower)) continue;
                    if (NON_HUMAN.contains(lower)) continue;
                    if (!treasury.isEmpty() && treasury.equalsIgnoreCase(name)) continue;
                    if (excludes.contains(lower)) continue;
                    // seen within the window? (login or logout timestamp)
                    long activity = Math.max(u.getLong("timestamps.login", 0L),
                                             u.getLong("timestamps.logout", 0L));
                    if (activity < cutoff) continue;
                    long minutes = playtimeMinutes(statsDir, id);
                    if (minutes < minMinutes) continue;
                    out.add(new Pool(id, name, minutes));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** world/players/stats/<uuid>.json, resolved off the real level folder. */
    private File statsFolder() {
        try {
            File level = levelFolder();
            if (level != null) {
                File a = new File(level, "players/stats");
                if (a.isDirectory()) return a;
                File b = new File(level, "stats");
                if (b.isDirectory()) return b;
            }
        } catch (Exception ignored) {}
        try { return new File(plugin.getDataFolder().getParentFile(), "world/players/stats"); }
        catch (Exception e) { return null; }
    }

    /**
     * The LEVEL folder. server.properties' level-name is authoritative - on this
     * Paper build the main world's getWorldFolder() reports its DIMENSION folder
     * (see AGENTS 3.3), so it is deliberately not used here.
     */
    private File levelFolder() {
        try {
            File container = Bukkit.getWorldContainer();
            String level = null;
            File props = new File(container, "server.properties");
            if (props.isFile()) {
                for (String line : Files.readAllLines(props.toPath(), StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.startsWith("level-name=")) { level = s.substring("level-name=".length()).trim(); break; }
                }
            }
            if (level != null && !level.isEmpty()) {
                File f = new File(container, level);
                if (f.isDirectory()) return f;
            }
            File[] kids = container.listFiles();
            if (kids != null) for (File k : kids) {
                if (k.isDirectory() && new File(k, "level.dat").isFile()) return k;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private long playtimeMinutes(File statsDir, UUID id) {
        try {
            if (statsDir == null) return 0L;
            File f = new File(statsDir, id + ".json");
            if (!f.isFile()) return 0L;
            String s = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Matcher m = PLAY_TIME.matcher(s);
            if (m.find()) return Long.parseLong(m.group(1)) / 1200L;
        } catch (Exception ignored) {}
        return 0L;
    }

    // ---------- reporting / paying ----------

    private void reportPreview(List<Pool> pool, double amt, CommandSender invoker) {
        try {
            WealthTaxManager tax = plugin.getWealthTax();
            double avail = tax == null ? 0.0 : tax.available("prize");
            msg(invoker, "§6§lPrize draw preview §7(nothing paid)");
            msg(invoker, "§7Eligible now: §f" + pool.size() + " §7active player(s)"
                    + " §8(seen in last " + WealthTaxManager.trim(activeDays()) + "d, >= "
                    + minPlaytimeMinutes() + "m played)");
            msg(invoker, "§7Prize: §a" + Menus.plain(amt) + " §8| §7prize bucket: §a" + Menus.plain(avail));
            if (pool.isEmpty()) {
                msg(invoker, "§7Nobody is eligible right now - a draw would pay no one.");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pool.size() && i < 15; i++) {
                    if (sb.length() > 0) sb.append("§7, §f");
                    sb.append(pool.get(i).name);
                }
                if (pool.size() > 15) sb.append("§7, +").append(pool.size() - 15).append(" more");
                msg(invoker, "§7In the hat: §f" + sb);
            }
            if (avail + 1.0e-6 < amt) {
                msg(invoker, "§cThe prize bucket can't cover " + Menus.plain(amt)
                        + " yet - a draw would be skipped this week.");
            }
            msg(invoker, "§7Next scheduled: §f" + nextLabel() + "§7. Run §f/tax draw confirm §7to draw now.");
            try {
                StringBuilder sb = new StringBuilder();
                for (Pool p2 : pool) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(p2.name).append('(').append(p2.minutes).append("m)");
                }
                log("[PrizeDraw] preview: " + pool.size() + " eligible, prize " + Menus.plain(amt)
                        + (sb.length() == 0 ? "" : " -> " + sb));
            } catch (Exception ignored) {}
        } finally {
            busy = false;
        }
    }

    private void pay(List<Pool> pool, double amt, CommandSender invoker) {
        WealthTaxManager tax = plugin.getWealthTax();
        if (tax == null) { msg(invoker, "§cTax manager not initialized."); return; }
        if (pool.isEmpty()) {
            msg(invoker, "§7Prize draw skipped - nobody is eligible.");
            return;
        }
        double avail = tax.available("prize");
        if (avail + 1.0e-6 < amt) {
            msg(invoker, "§7Prize draw skipped - the prize bucket holds " + Menus.plain(avail)
                    + ", below the " + Menus.plain(amt) + " prize. It will try again next week.");
            log("[PrizeDraw] skipped (bucket " + Menus.plain(avail) + " < prize " + Menus.plain(amt) + ")");
            return;
        }
        Pool w = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (!tax.payFromTreasury(w.id, w.name, amt, "weekly prize draw", "prize-draw", "prize", "auto")) {
            msg(invoker, "§cPrize payout failed (bucket moved or economy unavailable).");
            return;
        }
        try {
            data.set("state.last-draw", System.currentTimeMillis());
            data.set("state.last-winner", w.name);
            data.set("state.last-amount", amt);
            data.set("state.draws", draws() + 1);
            save();
        } catch (Exception ignored) {}
        String line = "§6§l🎁 PRIZE DRAW §r§7- §f" + w.name + " §7wins §a" + Menus.plain(amt)
                + " §7from the community fund! §8(" + pool.size() + " entered)";
        try { if (announce()) Bukkit.broadcastMessage(line); } catch (Exception ignored) {}
        try {
            if (discord()) say("Prize draw: " + w.name + " won " + Menus.plain(amt)
                    + " (" + pool.size() + " entered). Fund: " + Menus.plain(tax.treasuryBalance()));
        } catch (Exception ignored) {}
        try {
            org.bukkit.entity.Player on = Bukkit.getPlayer(w.id);
            if (on != null) on.sendMessage("§6§l🎁 §r§aYou won the weekly prize draw: §f"
                    + Menus.plain(amt) + "§a from the community fund!");
        } catch (Exception ignored) {}
        log("[PrizeDraw] winner " + w.name + " paid " + Menus.plain(amt)
                + " of " + pool.size() + " eligible");
        msg(invoker, "§aDrew §f" + w.name + "§a (of " + pool.size() + ") - paid "
                + Menus.plain(amt) + ". Fund: §a" + Menus.plain(tax.treasuryBalance()));
    }

    // ---------- persistence / misc ----------

    private void load() {
        try {
            if (file == null || !file.exists()) return;
            data.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            try { plugin.getLogger().warning("[PrizeDraw] load failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    public synchronized void save() {
        try { if (file != null) data.save(file); }
        catch (Exception e) {
            try { plugin.getLogger().warning("[PrizeDraw] save failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private void say(String line) {
        try {
            DiscordChatLog log = plugin.getChatLog();
            if (log != null) log.system(line);
        } catch (Exception ignored) {}
    }

    private void log(String line) {
        try { plugin.getLogger().info(line); } catch (Exception ignored) {}
    }

    private void msg(CommandSender s, String t) {
        try { if (s != null) s.sendMessage(t); } catch (Exception ignored) {}
    }
}
