package com.hush.allpurpose;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Owns lb.yml: kills/deaths, leaderboard board, death-message grouping. */
public class LeaderboardManager {

    private final AllPurpose plugin;
    private File lbFile;
    private YamlConfiguration lb;
    private UUID holoId = null;
    private boolean holoBusy = false;

    // Rapid PvP kills by the same killer collapse into one summary line so a
    // crystal/anchor rampage doesn't flood chat (or the logs).
    private final Map<String, java.util.List<String>> deathBurst = new HashMap<>();
    private final Map<String, org.bukkit.scheduler.BukkitTask> deathBurstTask = new HashMap<>();
    // Same-pair farm guard: RevengeByRain x mustbethewind x7 in a row spams 7
    // instant lines. After death-repeat-max-instant instant lines inside
    // death-repeat-window-seconds, further instant lines for that exact pair
    // are suppressed (rampage summary still counts them).
    private static final class RepeatState {
        long windowStart = 0L;
        int instantShown = 0;
    }
    private final Map<String, RepeatState> deathRepeat = new HashMap<>();

    public LeaderboardManager(AllPurpose plugin) {
        this.plugin = plugin;
        lbFile = new File(plugin.getDataFolder(), "lb.yml");
        if (!lbFile.exists()) {
            lb = new YamlConfiguration();
            saveLb();
        } else {
            lb = YamlConfiguration.loadConfiguration(lbFile);
        }
    }

    public void saveLb() {
        try { lb.save(lbFile); } catch (Exception e) { plugin.getLogger().warning("Could not save lb.yml: " + e.getMessage()); }
    }

    /** Flush every pending death-burst line (used on shutdown). */
    public void shutdown() {
        try {
            for (String k : new ArrayList<>(deathBurst.keySet())) flushDeathBurst(k);
        } catch (Exception ignored) {}
        saveLb();
    }

    public int lbKills(UUID id) {
        return lb.getInt("stats." + id.toString() + ".kills", 0);
    }

    public int lbDeaths(UUID id) {
        return lb.getInt("stats." + id.toString() + ".deaths", 0);
    }

    public void addKill(Player p) {
        String base = "stats." + p.getUniqueId().toString();
        lb.set(base + ".name", p.getName());
        lb.set(base + ".kills", lb.getInt(base + ".kills", 0) + 1);
    }

    public void addDeath(Player p) {
        String base = "stats." + p.getUniqueId().toString();
        lb.set(base + ".name", p.getName());
        lb.set(base + ".deaths", lb.getInt(base + ".deaths", 0) + 1);
    }

    /**
     * Duel flee penalty: subtract lifetime kills, floored at 0. Returns the
     * new total. Saves + refreshes the board so /lb shows it immediately.
     * Draws never call this (time-limit / mutual-death carry no penalty).
     */
    public int takeKills(UUID id, int n) {
        try {
            String base = "stats." + id.toString();
            int cur = lb.getInt(base + ".kills", 0);
            int next = Math.max(0, cur - Math.max(0, n));
            lb.set(base + ".kills", next);
            saveLb();
            updateHolo();
            return next;
        } catch (Exception e) {
            return 0;
        }
    }

    public void refreshLbName(Player p) {
        try {
            String base = "stats." + p.getUniqueId().toString();
            if (lb.contains(base) && !p.getName().equals(lb.getString(base + ".name"))) {
                lb.set(base + ".name", p.getName());
                saveLb();
            }
        } catch (Exception ignored) {}
    }

    public String kdOf(int kills, int deaths) {
        if (deaths <= 0) return String.valueOf(kills);
        return String.format(Locale.ROOT, "%.1f", (double) kills / (double) deaths);
    }

    public static class LbRow {
        public String uuid;
        public String name;
        public int kills;
        public int deaths;
    }

    public static class BalRow {
        public String name;
        public double money;
    }

    // ---------- Baltop (Essentials userdata, same money /baltop ranks) ----------
    // Vault has no "top balances" call, so read Essentials' own files:
    // plugins/Essentials/userdata/<uuid>.yml (money: + last-account-name:,
    // honouring baltop-exempt: true). ~170 files; cached 60s, scanned async
    // so kills/deaths never hitch the main thread.
    private volatile java.util.List<BalRow> balCache = new java.util.ArrayList<BalRow>();
    private volatile long balCacheAt = 0L;
    private volatile boolean balBusy = false;

    private long balCacheMillis() {
        try { return Math.max(15000L, plugin.getConfig().getLong("lb-holo-baltop-cache-seconds", 60) * 1000L); }
        catch (Exception e) { return 60000L; }
    }

    public java.util.List<BalRow> balTop() {
        try {
            if (System.currentTimeMillis() - balCacheAt > balCacheMillis() && !balBusy) refreshBalTop();
        } catch (Exception ignored) {}
        return balCache;
    }

    public void refreshBalTop() {
        if (balBusy) return;
        balBusy = true;
        final File dir;
        try { dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/userdata"); }
        catch (Exception e) { balBusy = false; return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final java.util.List<BalRow> out = new java.util.ArrayList<BalRow>();
                try {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            try {
                                String fn = f.getName();
                                if (!fn.endsWith(".yml")) continue;
                                YamlConfiguration u = YamlConfiguration.loadConfiguration(f);
                                try { if (u.getBoolean("baltop-exempt", false)) continue; } catch (Exception ignored) {}
                                String name = u.getString("last-account-name", null);
                                if (name == null || name.isEmpty()) continue;
                                double money = 0.0;
                                try { money = Double.parseDouble(String.valueOf(u.get("money")).replace(",", "")); }
                                catch (Exception ignored) { continue; }
                                BalRow r = new BalRow();
                                r.name = name;
                                r.money = money;
                                out.add(r);
                            } catch (Exception ignored) {}
                        }
                        java.util.Collections.sort(out, new java.util.Comparator<BalRow>() {
                            @Override public int compare(BalRow a, BalRow b) {
                                return Double.compare(b.money, a.money);
                            }
                        });
                    }
                } catch (Exception ignored) {}
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        try {
                            balCache = out;
                            balCacheAt = System.currentTimeMillis();
                        } catch (Exception ignored) {}
                        balBusy = false;
                        try { updateHolo(); } catch (Exception ignored) {}
                    }
                });
            }
        });
    }

    private static String moneyOf(double money) {
        try { return String.format(Locale.ROOT, "%,.2f", money); }
        catch (Exception e) { return String.valueOf(money); }
    }

    public long playtimeMinutes(String uuid) {
        try {
            UUID id = UUID.fromString(uuid);
            int ticks = Bukkit.getOfflinePlayer(id).getStatistic(Statistic.PLAY_ONE_MINUTE);
            return Math.max(0, ticks / 1200L);
        } catch (Exception e) {
            return 0;
        }
    }

    public String formatPlaytime(long mins) {
        if (mins < 60) return mins + "m";
        long h = mins / 60;
        if (h < 48) return h + "h " + (mins % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    public List<LbRow> lbTop() {
        List<LbRow> rows = new ArrayList<>();
        try {
            if (lb.getConfigurationSection("stats") == null) return rows;
            for (String uuid : lb.getConfigurationSection("stats").getKeys(false)) {
                try {
                    // AGENT bots farm kills all day: hide them from the board
                    // (history stays recorded, just never displayed).
                    String r = plugin.getRanks().playersConfig().getString("players." + uuid);
                    String k = r == null ? null : plugin.getRanks().findKey(r);
                    if (k != null && TextUtil.norm(k).equals("agent")) continue;
                    // Registry bots (e.g. Spar1-6) hide even if rank lookup misses.
                    try {
                        String bn = lb.getString("stats." + uuid + ".name", "");
                        if (bn != null && !bn.isEmpty() && BotNames.isBot(plugin, bn)) continue;
                    } catch (Exception ignored) {}
                } catch (Exception ignored) {}
                LbRow r = new LbRow();
                r.uuid = uuid;
                r.name = lb.getString("stats." + uuid + ".name", "?");
                r.kills = lb.getInt("stats." + uuid + ".kills", 0);
                r.deaths = lb.getInt("stats." + uuid + ".deaths", 0);
                if (r.kills > 0 || r.deaths > 0) rows.add(r);
            }
        } catch (Exception ignored) {}
        java.util.Collections.sort(rows, new java.util.Comparator<LbRow>() {
            @Override public int compare(LbRow a, LbRow b) {
                if (b.kills != a.kills) return b.kills - a.kills;
                return a.deaths - b.deaths;
            }
        });
        return rows;
    }

    public YamlConfiguration lbConfig() {
        return lb;
    }

    public Location holoLoc() {
        try {
            if (!lb.contains("holo.world")) return null;
            World w = Bukkit.getWorld(lb.getString("holo.world"));
            if (w == null) return null;
            return new Location(w, lb.getDouble("holo.x"), lb.getDouble("holo.y"), lb.getDouble("holo.z"));
        } catch (Exception e) {
            return null;
        }
    }

    public TextDisplay getHoloEntity() {
        try {
            if (holoId == null) return null;
            Entity en = Bukkit.getEntity(holoId);
            return en instanceof TextDisplay ? (TextDisplay) en : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void removeHoloEntity() {
        try {
            TextDisplay td = getHoloEntity();
            if (td != null) td.remove();
        } catch (Exception ignored) {}
        holoId = null;
    }

    // Orphan sweep: kills TextDisplays loitering around a board spot (crashed
    // sessions, chunk-unload removals and restarts all leave ghosts that the
    // id-tracked removal can't see). respawnHolo already sweeps before placing.
    public void sweepHoloArea(Location loc) {
        try {
            if (loc == null || loc.getWorld() == null) return;
            for (Entity en : loc.getWorld().getNearbyEntities(loc, 2, 3, 2)) {
                if (en instanceof TextDisplay) {
                    try { en.remove(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    // Full removal for /lb removeholo: capture the spot FIRST (the config null
    // below would erase it), kill tracked + orphan boards, then clear + save.
    public void removeHolo() {
        Location loc = null;
        try { loc = holoLoc(); } catch (Exception ignored) {}
        try { removeHoloEntity(); } catch (Exception ignored) {}
        holoId = null;
        sweepHoloArea(loc);
        try { lbConfig().set("holo", null); } catch (Exception ignored) {}
        try { saveLb(); } catch (Exception ignored) {}
    }

    public String buildHoloString() {
        int balN;
        int killN;
        boolean showBal;
        try { showBal = plugin.getConfig().getBoolean("lb-holo-baltop-enabled", true); }
        catch (Exception e) { showBal = true; }
        try { balN = Math.max(0, Math.min(25, plugin.getConfig().getInt("lb-holo-baltop-lines", 10))); }
        catch (Exception e) { balN = 10; }
        try { killN = Math.max(0, Math.min(25, plugin.getConfig().getInt("lb-holo-kill-lines", 10))); }
        catch (Exception e) { killN = 10; }
        StringBuilder sb = new StringBuilder("&6&l⚔ LEADERBOARD");
        if (showBal && balN > 0) {
            sb.append("\n&e&l$ BALTOP");
            java.util.List<BalRow> bals = balTop();
            if (bals.isEmpty()) {
                sb.append("\n&7&o... loading");
            } else {
                int n = Math.min(balN, bals.size());
                for (int i = 0; i < n; i++) {
                    BalRow r = bals.get(i);
                    sb.append("\n&6&l#").append(i + 1).append(" &f").append(r.name)
                            .append(" &a$").append(moneyOf(r.money));
                }
            }
        }
        sb.append("\n&c&l⚔ KILLTOP");
        List<LbRow> rows = lbTop();
        int n = Math.min(killN, rows.size());
        if (n == 0) {
            sb.append("\n&7&oNo kills yet");
        } else {
            for (int i = 0; i < n; i++) {
                LbRow r = rows.get(i);
                sb.append("\n&6&l#").append(i + 1).append(" &f").append(r.name)
                        .append(" &aK:").append(r.kills)
                        .append(" &cD:").append(r.deaths);
            }
        }
        try {
            return sb.toString();
        } catch (Exception e) {
            return sb.toString();
        }
    }

    public Component buildHoloText() {
        try {
            return TextUtil.prefixComponent(buildHoloString());
        } catch (Exception e) {
            return Component.text(buildHoloString());
        }
    }

    public void respawnHolo() {
        // Never recurse: this sets the text itself and never calls updateHolo().
        if (holoBusy) return;
        holoBusy = true;
        try {
            removeHoloEntity();
            Location loc = holoLoc();
            if (loc == null || loc.getWorld() == null) return;
            // Nobody around (e.g. right after a restart): don't spawn into an
            // unloaded chunk, the periodic updater will try again later.
            try {
                if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
            } catch (Exception ignored) {}
            // Clear stale boards left by older versions/crashes so they can't pile up.
            try {
                for (Entity en : loc.getWorld().getNearbyEntities(loc, 2, 3, 2)) {
                    if (en instanceof TextDisplay) {
                        try { en.remove(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            TextDisplay td = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
            td.setBillboard(Display.Billboard.CENTER);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setBackgroundColor(Color.fromARGB(128, 0, 0, 0));
            td.setLineWidth(300);
            td.setSeeThrough(false);
            td.setPersistent(true);
            td.setInvulnerable(true);
            holoId = td.getUniqueId();
            try {
                td.text(buildHoloText());
            } catch (Exception ignored) {}
        } catch (Exception e) {
            plugin.getLogger().warning("Leaderboard hologram failed: " + e.getMessage());
            holoId = null;
        } finally {
            holoBusy = false;
        }
    }

    public void updateHolo() {
        try {
            if (holoLoc() == null) return;
            TextDisplay td = getHoloEntity();
            if (td == null || !td.isValid()) {
                respawnHolo(); // single attempt, no recursion: respawn never calls back here
                return;
            }
            // never teleport here: position is fixed at placement so the
            // board stays put and only its text refreshes (no flicker/jitter).
            // Re-assert CENTER billboard so boards spawned by older builds
            // face every viewer without needing /lb holo re-placed.
            try { td.setBillboard(Display.Billboard.CENTER); } catch (Exception ignored) {}
            td.text(buildHoloText());
        } catch (Exception ignored) {}
    }

    /** MONITOR PvP death hook: stats + rankup + board refresh.
     * Bot fights never count — except staff/admin kills: if the killer holds
     * a staff overlay (or OP / rank.admin) and the victim is a registered bot,
     * the kill credits to the staffer (rankup + fx included). Regular players
     * farming bots still get nothing. Feed in handleDeathGroup is untouched
     * (still broadcasts). */
    public void handlePvpDeath(PlayerDeathEvent e) {
        try {
            Player victim = e.getEntity();
            Player killer = victim.getKiller();
            if (isBot(victim) && !isBot(killer)
                    && killer != null && !killer.getUniqueId().equals(victim.getUniqueId())
                    && isStaff(killer)) {
                addKill(killer);
                plugin.getRanks().checkRankup(killer);
                try { killFx(killer, victim); } catch (Exception ignored) {}
                saveLb();
                updateHolo();
                return;
            }
            if (isBot(victim) || isBot(killer)) return;
            addDeath(victim);
            if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
                addKill(killer);
                plugin.getRanks().checkRankup(killer);
                // Per-rank kill celebration (once per kill: this hook fires once
                // per death event; the group-summary path below never records
                // kills). Duel kills flow through this same hook, so no
                // special-casing.
                try { killFx(killer, victim); } catch (Exception ignored) {}
            }
            saveLb();
            updateHolo();
        } catch (Exception ignored) {}
    }

    /** True if player is a registered fighter bot (or AGENT rank fallback). */
    private boolean isBot(Player p) {
        try {
            if (p == null) return false;
            try {
                if (BotNames.isBot(plugin, p.getName())) return true;
            } catch (Exception ignored) {}
            try {
                String r = plugin.getRanks().playersConfig().getString("players." + p.getUniqueId().toString());
                String k = r == null ? null : plugin.getRanks().findKey(r);
                if (k != null && TextUtil.norm(k).equals("agent")) return true;
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return false;
    }

    /** True if player holds a staff overlay (or OP / rank.admin). */
    private boolean isStaff(Player p) {
        try {
            if (p == null) return false;
            try { if (p.isOp()) return true; } catch (Exception ignored) {}
            try { if (p.hasPermission("rank.admin")) return true; } catch (Exception ignored) {}
            try {
                String sk = plugin.getRanks().staffKey(p.getUniqueId());
                if (sk != null && !sk.isEmpty()) return true;
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return false;
    }

    // Per-rank kill celebration dispatcher (all cosmetic, zero damage).
    // Spec source: config killfx.<RANK> ({type,count} map, "type:count" string,
    // or "off"), else the legacy killfx-lt5-* keys for LT5, else tier defaults
    // (LT* rockets, HT* storm, MEMBER puff, top/custom ranks grand storm).
    // Rank = base ladder rank (staff overlays ignored - playerRankKey).
    // Main-thread only (called from the death event), null-safe.
    private void killFx(Player killer, Player victim) {
        try {
            if (killer == null || !killer.isOnline() || killer.isDead()) return;
            boolean master;
            try { master = plugin.getConfig().getBoolean("killfx-enabled", true); }
            catch (Exception ex) { master = true; }
            if (!master) return;
            String cur = null;
            try { cur = plugin.getRanks().playerRankKey(killer); } catch (Exception ignored) {}
            if (cur == null) return;
            String norm = TextUtil.norm(cur);
            String type = null;
            int count = 0;
            try {
                org.bukkit.configuration.ConfigurationSection sec =
                        plugin.getConfig().getConfigurationSection("killfx");
                if (sec != null) {
                    for (String k : sec.getKeys(false)) {
                        if (!TextUtil.norm(k).equals(norm)) continue;
                        try {
                            Object v = sec.get(k);
                            if (v instanceof org.bukkit.configuration.ConfigurationSection) {
                                org.bukkit.configuration.ConfigurationSection s2 =
                                        (org.bukkit.configuration.ConfigurationSection) v;
                                if (!s2.getBoolean("enabled", true)) type = "off";
                                else {
                                    type = s2.getString("type", null);
                                    count = s2.getInt("count", 0);
                                }
                            } else if (v != null) {
                                String s = String.valueOf(v).trim();
                                if (s.equalsIgnoreCase("off") || s.equalsIgnoreCase("none")
                                        || s.equalsIgnoreCase("false")) type = "off";
                                else {
                                    int ci = s.indexOf(':');
                                    if (ci >= 0) {
                                        type = s.substring(0, ci).trim();
                                        try { count = Integer.parseInt(s.substring(ci + 1).trim()); }
                                        catch (Exception ignored) {}
                                    } else type = s;
                                }
                            }
                        } catch (Exception ignored) {}
                        break;
                    }
                }
            } catch (Exception ignored) {}
            // Legacy LT5 keys double as the LT5 fallback while killfx.LT5 is unset.
            if ((type == null || type.isEmpty()) && norm.equals("lt5")) {
                boolean on;
                try { on = plugin.getConfig().getBoolean("killfx-lt5-rockets-enabled", true); }
                catch (Exception ex) { on = true; }
                if (!on) return;
                type = "rockets";
                try { count = plugin.getConfig().getInt("killfx-lt5-rocket-count", 8); }
                catch (Exception ex) { count = 8; }
            }
            if (type == null || type.isEmpty()) {
                if (norm.equals("member")) { type = "puff"; count = 12; }
                else if (norm.length() == 3 && norm.startsWith("lt") && Character.isDigit(norm.charAt(2))) {
                    type = "rockets"; count = 8 + 2 * (5 - (norm.charAt(2) - '0'));
                } else if (norm.length() == 3 && norm.startsWith("ht") && Character.isDigit(norm.charAt(2))) {
                    type = "storm"; count = 10 + 2 * (5 - (norm.charAt(2) - '0'));
                } else { type = "storm"; count = 16; }
            }
            type = TextUtil.norm(type);
            if (type.equals("off") || type.equals("none") || type.equals("false")) return;
            // GOD/IMMORTAL always go grand even if someone sets a small count.
            if ((norm.equals("god") || norm.equals("immortal")) && count < 20) count = 24;
            if (type.equals("puff")) {
                puffFx(killer, clampFx(count, 4, 60, 12));
                playFxSound(killer, "ENTITY_FIREWORK_ROCKET_BLAST", 0.4f, 1.4f);
            } else if (type.equals("lightning")) {
                boltFx(victim, clampFx(count, 1, 32, 8));
                playFxSound(killer, "ENTITY_LIGHTNING_BOLT_THUNDER", 0.8f, 1.0f);
            } else if (type.equals("storm")) {
                int c = clampFx(count, 1, 32, 12);
                rocketRing(killer, c, norm.startsWith("lt") ? 0 : (norm.startsWith("ht") ? 1 : 2));
                boltFx(victim, c);
                playFxSound(killer, "ENTITY_LIGHTNING_BOLT_THUNDER", 0.6f, 1.0f);
                playFxSound(killer, "ENTITY_FIREWORK_ROCKET_BLAST", 0.6f, 1.2f);
                if (c >= 20) playFxSound(killer, "ENTITY_PLAYER_LEVELUP", 0.7f, 1.4f);
            } else { // "rockets" + anything unknown falls back to the ring
                rocketRing(killer, clampFx(count, 1, 24, 8), norm.startsWith("ht") ? 1 : 0);
                playFxSound(killer, "ENTITY_FIREWORK_ROCKET_BLAST", 0.6f, 1.0f);
            }
        } catch (Exception ignored) {}
    }

    private int clampFx(int v, int lo, int hi, int def) {
        try { if (v <= 0) return def; return Math.max(lo, Math.min(hi, v)); }
        catch (Exception e) { return def; }
    }

    private void playFxSound(Player p, String soundName, float vol, float pitch) {
        // Reflection-only: avoids a compile-time org.bukkit.Sound reference
        // (paper-api in lib/ ships without the jetbrains annotations jar that
        // javac wants when resolving Sound's signatures).
        try {
            Class<?> soundCls = Class.forName("org.bukkit.Sound");
            Object target = null;
            try {
                for (Object c : soundCls.getEnumConstants()) {
                    if (c != null && c.toString().equals(soundName)) { target = c; break; }
                }
            } catch (Exception ignored) {}
            if (target == null) return;
            java.lang.reflect.Method m = p.getClass().getMethod("playSound",
                    org.bukkit.Location.class, soundCls, float.class, float.class);
            m.invoke(p, p.getLocation(), target, vol, pitch);
        } catch (Exception ignored) {}
    }

    // Particle puff around the killer (MEMBER default). Cosmetic only.
    private void puffFx(Player killer, int count) {
        try {
            if (killer == null || killer.getWorld() == null) return;
            org.bukkit.Particle part;
            try { part = org.bukkit.Particle.valueOf("FIREWORK"); }
            catch (Exception e) { part = org.bukkit.Particle.FLAME; }
            try {
                killer.getWorld().spawnParticle(part,
                        killer.getLocation().clone().add(0, 1, 0),
                        count, 0.5, 0.5, 0.5, 0.05);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // Cosmetic lightning only: strikeLightningEffect deals no damage and starts
    // no fire (unlike strikeLightning). Strikes scale from count, capped at 4.
    private void boltFx(Player victim, int count) {
        try {
            if (victim == null) return;
            org.bukkit.Location base;
            try { base = victim.getLocation().clone(); } catch (Exception ex) { return; }
            if (base.getWorld() == null) return;
            int strikes = Math.max(1, Math.min(4, (count + 7) / 8));
            java.util.Random rnd = new java.util.Random();
            for (int i = 0; i < strikes; i++) {
                try {
                    org.bukkit.Location at = base.clone().add(
                            (rnd.nextDouble() - 0.5) * 4.0, 0, (rnd.nextDouble() - 0.5) * 4.0);
                    base.getWorld().strikeLightningEffect(at);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // Rocket ring around the killer (the old LT5 effect, now shared).
    // Palette 0 = bright mix (LT), 1 = gold/red (HT), 2 = grand white/gold.
    // Ring at radius ~3, power 1 (rockets climb before popping), small star
    // count. Main-thread only (called from the death event), null-safe.
    private void rocketRing(Player killer, int count, int palette) {
        try {
            if (killer == null || !killer.isOnline() || killer.isDead()) return;
            count = Math.max(1, Math.min(24, count));
            org.bukkit.Location center;
            try { center = killer.getLocation().clone(); } catch (Exception ex) { return; }
            if (center.getWorld() == null) return;
            java.util.Random rnd = new java.util.Random();
            org.bukkit.Color[] brights;
            if (palette == 1) {
                brights = new org.bukkit.Color[]{
                        org.bukkit.Color.YELLOW, org.bukkit.Color.ORANGE, org.bukkit.Color.RED,
                        org.bukkit.Color.WHITE, org.bukkit.Color.fromRGB(255, 170, 0)};
            } else if (palette == 2) {
                brights = new org.bukkit.Color[]{
                        org.bukkit.Color.WHITE, org.bukkit.Color.YELLOW,
                        org.bukkit.Color.AQUA, org.bukkit.Color.fromRGB(255, 215, 0)};
            } else {
                brights = new org.bukkit.Color[]{
                        org.bukkit.Color.RED, org.bukkit.Color.YELLOW, org.bukkit.Color.AQUA,
                        org.bukkit.Color.LIME, org.bukkit.Color.FUCHSIA, org.bukkit.Color.ORANGE,
                        org.bukkit.Color.WHITE, org.bukkit.Color.fromRGB(85, 255, 255)};
            }
            org.bukkit.FireworkEffect.Type[] shapes = new org.bukkit.FireworkEffect.Type[]{
                    org.bukkit.FireworkEffect.Type.BALL,
                    org.bukkit.FireworkEffect.Type.BALL_LARGE,
                    org.bukkit.FireworkEffect.Type.BURST};
            for (int i = 0; i < count; i++) {
                try {
                    double a = 2.0 * Math.PI * i / count;
                    org.bukkit.Location at = center.clone().add(3.0 * Math.cos(a), 0.5, 3.0 * Math.sin(a));
                    org.bukkit.entity.Firework fw = (org.bukkit.entity.Firework)
                            center.getWorld().spawnEntity(at, org.bukkit.entity.EntityType.FIREWORK_ROCKET);
                    org.bukkit.inventory.meta.FireworkMeta meta = fw.getFireworkMeta();
                    org.bukkit.Color c1 = brights[rnd.nextInt(brights.length)];
                    org.bukkit.Color c2 = brights[rnd.nextInt(brights.length)];
                    meta.addEffect(org.bukkit.FireworkEffect.builder()
                            .with(shapes[rnd.nextInt(shapes.length)])
                            .withColor(c1).withFade(c2)
                            .trail(false).flicker(false).build());
                    meta.setPower(1);
                    fw.setFireworkMeta(meta);
                    try { fw.setVelocity(new org.bukkit.util.Vector(0, 1.2, 0)); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public long deathGroupMillis() {
        try {
            return Math.max(2000L, plugin.getConfig().getLong("death-group-seconds", 4) * 1000L);
        } catch (Exception e) {
            return 4000L;
        }
    }

    private long deathRepeatMillis() {
        try {
            return Math.max(5000L, plugin.getConfig().getLong("death-repeat-window-seconds", 30) * 1000L);
        } catch (Exception e) {
            return 30000L;
        }
    }

    private int deathRepeatMaxInstant() {
        try {
            return Math.max(0, Math.min(5, plugin.getConfig().getInt("death-repeat-max-instant", 2)));
        } catch (Exception e) {
            return 2;
        }
    }

    /** Compress repeat victim names: [a,a,a,b] -> ["a x3","b"]. Order kept. */
    private static java.util.List<String> compressNames(java.util.List<String> victims) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String v : victims) {
            if (v == null) continue;
            counts.put(v, counts.getOrDefault(v, 0) + 1);
        }
        java.util.List<String> out = new java.util.ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> en : counts.entrySet()) {
            if (en.getValue() > 1) out.add(en.getKey() + " x" + en.getValue());
            else out.add(en.getKey());
        }
        return out;
    }

    // ---------- Funny instant death feed ----------
    // Instant per-kill line so chat feels alive, plus a rampage summary when one
    // killer chains 3+ kills inside the group window.
    private static final String[] FUNNY_KILL = new String[]{
        "§c{killer} §7absolutely §lclapped §f{victim} §7lmao",
        "§c{killer} §7packed up §f{victim} §7lol",
        "§f{victim} §7got smoked by §c{killer} §7LMAO",
        "§c{killer} §7sent §f{victim} §7back to spawn 💀",
        "§f{victim} §7got folded by §c{killer}",
        "§c{killer} §7dropped §f{victim} §7like a bot",
        "§f{victim} §7fumbled vs §c{killer} §7lmao",
        "§c{killer} §7farmed §f{victim} §7lmao",
        "§f{victim} §7got deleted by §c{killer}",
        "§c{killer} §7made §f{victim} §7touch grass",
    };

    private static final String[] FUNNY_SUICIDE = new String[]{
        "§f{victim} §7fumbled it lmao 💀",
        "§f{victim} §7killed themselves, LMAO",
        "§f{victim} §7just died to nothing, embarrassing",
        "§f{victim} §7got humbled by gravity",
    };

    private static final String[] FUNNY_RAMPAGE = new String[]{
        "§c{killer} §7is on a §4§lRAMPAGE §7({n} kills) lmao: §f{names}",
        "§c{killer} §7just clapped {n} people back to spawn: §f{names}",
        "§c{killer} §7is farming the lobby ({n}): §f{names} §7LMAO",
    };

    private static String pick(String[] arr) {
        try {
            return arr[java.util.concurrent.ThreadLocalRandom.current().nextInt(arr.length)];
        } catch (Exception e) {
            return arr[0];
        }
    }

    private static String pick(java.util.List<String> lst) {
        try {
            return lst.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(lst.size()));
        } catch (Exception e) {
            return lst.get(0);
        }
    }

    /**
     * Config-driven death message lines: death-messages.kill / .suicide / .rampage.
     * Read per-event so edits apply on /allpurpose reload (no restart).
     * Empty or missing list = built-in fallback. Lines may use &c and &#RRGGBB.
     */
    private java.util.List<String> cfgList(String key, String[] fallback) {
        try {
            java.util.List<String> in = plugin.getConfig().getStringList(key);
            if (in != null && !in.isEmpty()) {
                java.util.ArrayList<String> out = new java.util.ArrayList<>(in.size());
                for (String s : in) if (s != null && !s.isEmpty()) out.add(TextUtil.legacy(s));
                if (!out.isEmpty()) return out;
            }
        } catch (Exception ignored) {}
        return java.util.Arrays.asList(fallback);
    }

    private void broadcastInstantKill(String killer, String victim) {
        try {
            String t = pick(cfgList("death-messages.kill", FUNNY_KILL));
            t = t.replace("{killer}", killer).replace("{victim}", victim);
            Bukkit.broadcastMessage(t);
            try {
                if (plugin.getWebhook().wants("deaths")) plugin.getWebhook().send("☠ " + t);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void broadcastSuicide(String victim) {
        try {
            String t = pick(cfgList("death-messages.suicide", FUNNY_SUICIDE));
            t = t.replace("{victim}", victim);
            Bukkit.broadcastMessage(t);
            try {
                if (plugin.getWebhook().wants("deaths")) plugin.getWebhook().send("\uD83E\uDEA6 " + t);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // Plain (non-funny) feed lines: used when death-funny is off but the feed
    // itself (death-grouping) is on, so disabling jokes never silences kills.
    private void broadcastPlainKill(String killer, String victim) {
        try {
            String t = TextUtil.legacy("&c" + killer + " &7killed &f" + victim);
            Bukkit.broadcastMessage(t);
            try {
                if (plugin.getWebhook().wants("deaths")) plugin.getWebhook().send("☠ " + t);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void broadcastPlainSuicide(String victim) {
        try {
            String t = TextUtil.legacy("&f" + victim + " &7died");
            Bukkit.broadcastMessage(t);
            try {
                if (plugin.getWebhook().wants("deaths")) plugin.getWebhook().send("\uD83E\uDEA6 " + t);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public void flushDeathBurst(final String killer) {
        java.util.List<String> victims;
        synchronized (deathBurst) {
            victims = deathBurst.remove(killer);
            org.bukkit.scheduler.BukkitTask t = deathBurstTask.remove(killer);
            if (t != null) { try { t.cancel(); } catch (Exception ignored) {} }
        }
        if (victims == null || victims.isEmpty()) return;
        // Singles/doubles already got their instant line - only
        // celebrate 3+ chains so chat doesn't double-post every kill.
        if (victims.size() < 3) return;
        try {
            java.util.List<String> compressed = compressNames(victims);
            java.util.List<String> head = compressed.subList(0, Math.min(4, compressed.size()));
            String names = String.join(", ", head);
            if (compressed.size() > 4) names += " (+" + (victims.size() - 4) + " more)";
            String t = pick(cfgList("death-messages.rampage", FUNNY_RAMPAGE));
            t = t.replace("{killer}", killer)
                    .replace("{n}", String.valueOf(victims.size()))
                    .replace("{names}", names);
            Bukkit.broadcastMessage(t);
            try {
                if (plugin.getWebhook().wants("deaths")) plugin.getWebhook().send("\uD83D\uDC80 " + t);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /** HIGHEST death hook: instant kill line + short-window rampage tracker.
     * Feed stays up whenever either toggle is on: death-funny picks the joke
     * style, death-grouping keeps a plain "killed" line + the 3+ rampage
     * summary. Both off = silent. */
    public void handleDeathGroup(PlayerDeathEvent e) {
        boolean funny;
        boolean grouping;
        try {
            funny = plugin.getConfig().getBoolean("death-funny", true);
            grouping = plugin.getConfig().getBoolean("death-grouping", true);
        } catch (Exception ex) {
            return;
        }
        if (!funny && !grouping) return;
        final String k;
        final String v;
        final boolean hasKiller;
        try {
            Player victim = e.getEntity();
            Player killer = victim.getKiller();
            v = victim.getName();
            if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
                hasKiller = false;
                k = null;
            } else {
                hasKiller = true;
                k = killer.getName();
            }
        } catch (Exception ex) {
            return;
        }
        try {
            e.deathMessage(null);
        } catch (Exception ignored) {}
        // No killer (fall, lava, self): feed line in either style.
        if (!hasKiller) {
            if (funny) broadcastSuicide(v);
            else broadcastPlainSuicide(v);
            return;
        }
        // Same-pair farm guard: after N instant lines for killer+victim inside
        // the repeat window, suppress further instant lines (rampage still
        // counts every kill, so a 7-farm shows 2 lines + 1 summary, not 7+1).
        boolean suppressInstant = false;
        try {
            String rk = k.toLowerCase(java.util.Locale.ROOT) + "\u0000" + v.toLowerCase(java.util.Locale.ROOT);
            long nowMs = System.currentTimeMillis();
            long winMs = deathRepeatMillis();
            int maxInst = deathRepeatMaxInstant();
            synchronized (deathRepeat) {
                // prune stale pairs so the map can't grow forever
                if (deathRepeat.size() > 2000) {
                    java.util.Iterator<java.util.Map.Entry<String, RepeatState>> it = deathRepeat.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<String, RepeatState> en = it.next();
                        if (nowMs - en.getValue().windowStart > winMs) it.remove();
                    }
                }
                RepeatState rs = deathRepeat.get(rk);
                if (rs == null) {
                    rs = new RepeatState();
                    rs.windowStart = nowMs;
                    rs.instantShown = 0;
                    deathRepeat.put(rk, rs);
                } else if (nowMs - rs.windowStart > winMs) {
                    rs.windowStart = nowMs;
                    rs.instantShown = 0;
                }
                if (rs.instantShown >= maxInst) suppressInstant = true;
                else rs.instantShown++;
            }
        } catch (Exception ignored) {}
        // Responsive part: instant line, zero delay (funny or plain style).
        if (!suppressInstant) {
            if (funny) broadcastInstantKill(k, v);
            else broadcastPlainKill(k, v);
        }
        if (!grouping) return;
        boolean schedule = false;
        boolean flushNow = false;
        synchronized (deathBurst) {
            java.util.List<String> list = deathBurst.get(k);
            if (list == null) {
                list = new ArrayList<>();
                deathBurst.put(k, list);
            }
            list.add(v);
            if (list.size() >= 8) {
                flushNow = true;
            } else if (!deathBurstTask.containsKey(k)) {
                schedule = true;
            }
        }
        if (flushNow) {
            flushDeathBurst(k);
        } else if (schedule) {
            try {
                final long delay = Math.max(40L, deathGroupMillis() / 50L);
                org.bukkit.scheduler.BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override public void run() { flushDeathBurst(k); }
                }, delay);
                synchronized (deathBurst) {
                    if (deathBurst.containsKey(k)) deathBurstTask.put(k, t);
                    else { try { t.cancel(); } catch (Exception ignored) {} }
                }
            } catch (Exception ignored) {}
        }
    }
}
