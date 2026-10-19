package com.hush.allpurpose;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;

/**
 * Free-form floating text holograms behind {@code /setholotext}.
 *
 * Unlike the leaderboard boards (which are built from a fixed template), a
 * holotext is whatever the owner types - stored line by line in holotext.yml
 * and fully editable in-game, so no recompile is ever needed to change one.
 *
 * Two placement rules are baked in on purpose (that is the whole point of this
 * command vs. a vanilla /summon):
 *   - billboard FIXED, so the text does NOT swing to follow every player's
 *     camera - it holds one rotation in the world;
 *   - the yaw is captured from whoever places it, so its readable face points
 *     the way the placer was looking (/setholotext rotate fixes it afterwards).
 *
 * Each display is a persistent TextDisplay carrying a scoreboard tag, so a
 * restart/crash can always be reconciled: on boot every tagged display is
 * swept, then every saved entry is respawned at its exact spot.
 */
public class HoloTextManager {

    /** Base scoreboard tag: lets a boot/crash sweep find OUR displays only. */
    private static final String TAG = "allpurpose_holotext";

    private static final String ROOT = "holotexts.";

    public static final List<String> STYLE_KEYS = Arrays.asList(
            "billboard", "align", "background", "linewidth", "opacity",
            "seethrough", "shadow", "glow", "viewrange", "scale");

    private final AllPurpose plugin;
    private final File file;
    private YamlConfiguration cfg;
    private final Map<String, UUID> live = new HashMap<>();   // id -> live display
    private boolean busy = false;

    public HoloTextManager(AllPurpose plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "holotext.yml");
        load();
    }

    private void load() {
        try {
            if (!file.exists()) {
                cfg = new YamlConfiguration();
                save();
            } else {
                cfg = YamlConfiguration.loadConfiguration(file);
            }
        } catch (Exception e) {
            cfg = new YamlConfiguration();
            plugin.getLogger().warning("Could not read holotext.yml: " + e.getMessage());
        }
    }

    public void save() {
        try { cfg.save(file); } catch (Exception e) {
            plugin.getLogger().warning("Could not save holotext.yml: " + e.getMessage());
        }
    }

    /** Re-read holotext.yml from disk (for hand edits) and put everything back. */
    public void reload() {
        try { live.clear(); } catch (Exception ignored) {}
        load();
        boot();
    }

    public void shutdown() {
        save();
    }

    private boolean enabled() {
        try { return plugin.getConfig().getBoolean("holotext.enabled", true); }
        catch (Exception e) { return true; }
    }

    // ---------- ids ----------

    /** A valid id is lowercase, no spaces/dots (dots would nest in YAML). */
    public static String validId(String raw) {
        try {
            String s = TextUtil.norm(raw == null ? "" : raw.trim());
            return s.matches("[a-z0-9_+\\-]{1,24}") ? s : null;
        } catch (Exception e) { return null; }
    }

    /** Every saved id, sorted. */
    public List<String> ids() {
        List<String> out = new ArrayList<>();
        try {
            ConfigurationSection s = cfg.getConfigurationSection("holotexts");
            if (s != null) out.addAll(s.getKeys(false));
        } catch (Exception ignored) {}
        java.util.Collections.sort(out);
        return out;
    }

    public boolean exists(String id) {
        try { return id != null && cfg.contains(ROOT + id + ".world"); }
        catch (Exception e) { return false; }
    }

    /** First free auto id ("holo1", "holo2", ...). */
    public String autoId() {
        for (int i = 1; i < 10000; i++) if (!exists("holo" + i)) return "holo" + i;
        return "holo" + System.currentTimeMillis();
    }

    // ---------- location ----------

    public Location loc(String id) {
        try {
            if (!exists(id)) return null;
            World w = Bukkit.getWorld(cfg.getString(ROOT + id + ".world"));
            if (w == null) return null;
            return new Location(w,
                    cfg.getDouble(ROOT + id + ".x"),
                    cfg.getDouble(ROOT + id + ".y"),
                    cfg.getDouble(ROOT + id + ".z"),
                    (float) cfg.getDouble(ROOT + id + ".yaw", 0.0),
                    (float) cfg.getDouble(ROOT + id + ".pitch", 0.0));
        } catch (Exception e) { return null; }
    }

    public float yaw(String id) {
        try { return (float) cfg.getDouble(ROOT + id + ".yaw", 0.0); } catch (Exception e) { return 0f; }
    }

    public float pitch(String id) {
        try { return (float) cfg.getDouble(ROOT + id + ".pitch", 0.0); } catch (Exception e) { return 0f; }
    }

    // ---------- text ----------

    public List<String> lines(String id) {
        List<String> out = new ArrayList<>();
        try {
            List<String> l = cfg.getStringList(ROOT + id + ".lines");
            if (l != null) out.addAll(l);
        } catch (Exception ignored) {}
        return out;
    }

    public void setLines(String id, List<String> lines) {
        cfg.set(ROOT + id + ".lines", lines == null ? new ArrayList<String>() : lines);
        save();
    }

    // ---------- style ----------

    /** Stored style value, or null when the hologram has no override. */
    public String style(String id, String key) {
        try { return cfg.getString(ROOT + id + ".style." + key); }
        catch (Exception e) { return null; }
    }

    public void setStyle(String id, String key, String value) {
        cfg.set(ROOT + id + ".style." + key, value);
        save();
    }

    public Map<String, String> styles(String id) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            for (String k : STYLE_KEYS) {
                String v = style(id, k);
                if (v != null) out.put(k, v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---------- create / move / remove ----------

    public void create(String id, Location loc, List<String> lines) {
        if (id == null || loc == null || loc.getWorld() == null) return;
        String base = ROOT + id + ".";
        cfg.set(base + "world", loc.getWorld().getName());
        cfg.set(base + "x", loc.getX());
        cfg.set(base + "y", loc.getY());
        cfg.set(base + "z", loc.getZ());
        cfg.set(base + "yaw", (double) loc.getYaw());
        cfg.set(base + "pitch", (double) loc.getPitch());
        cfg.set(base + "lines", lines == null ? new ArrayList<String>() : lines);
        save();
        spawn(id);
    }

    /** Move an existing hologram to loc (keeping the given yaw/pitch). */
    public void move(String id, Location loc) {
        if (!exists(id) || loc == null || loc.getWorld() == null) return;
        String base = ROOT + id + ".";
        cfg.set(base + "world", loc.getWorld().getName());
        cfg.set(base + "x", loc.getX());
        cfg.set(base + "y", loc.getY());
        cfg.set(base + "z", loc.getZ());
        cfg.set(base + "yaw", (double) loc.getYaw());
        cfg.set(base + "pitch", (double) loc.getPitch());
        save();
    }

    public void setYaw(String id, float yaw) {
        if (!exists(id)) return;
        cfg.set(ROOT + id + ".yaw", (double) yaw);
        save();
    }

    public void setPitch(String id, float pitch) {
        if (!exists(id)) return;
        cfg.set(ROOT + id + ".pitch", (double) pitch);
        save();
    }

    public void remove(String id) {
        try {
            removeEntity(id);
            cfg.set(ROOT + id, null);
            save();
        } catch (Exception ignored) {}
    }

    public void removeAll() {
        try {
            for (String id : ids()) removeEntity(id);
            cfg.set("holotexts", null);
            save();
        } catch (Exception ignored) {}
    }

    // ---------- entities ----------

    public TextDisplay entity(String id) {
        try {
            UUID u = live.get(id);
            if (u == null) return null;
            Entity en = Bukkit.getEntity(u);
            return en instanceof TextDisplay ? (TextDisplay) en : null;
        } catch (Exception e) { return null; }
    }

    private void removeEntity(String id) {
        try {
            TextDisplay td = entity(id);
            if (td != null) td.remove();
        } catch (Exception ignored) {}
        try { live.remove(id); } catch (Exception ignored) {}
    }

    /** Spawn (or re-spawn) one hologram at its saved spot. Chunk-aware. */
    public boolean spawn(String id) {
        if (id == null || busy || !exists(id)) return false;
        Location loc = loc(id);
        if (loc == null || loc.getWorld() == null) return false;
        World w = loc.getWorld();
        try {
            if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return false;
        } catch (Exception ignored) {}
        busy = true;
        try {
            removeEntity(id);
            sweepSpot(loc);
            Entity en = w.spawnEntity(loc, EntityType.TEXT_DISPLAY);
            if (!(en instanceof TextDisplay)) { try { en.remove(); } catch (Exception ignored) {} return false; }
            TextDisplay td = (TextDisplay) en;
            applyStyle(id, td);
            try { td.setPersistent(true); } catch (Exception ignored) {}
            try { td.setInvulnerable(true); } catch (Exception ignored) {}
            try { td.addScoreboardTag(TAG); } catch (Exception ignored) {}
            td.text(TextUtil.prefixComponent(String.join("\n", lines(id))));
            live.put(id, td.getUniqueId());
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("holotext '" + id + "' failed: " + e.getMessage());
            try { live.remove(id); } catch (Exception ignored) {}
            return false;
        } finally {
            busy = false;
        }
    }

    /** Update text + style on the live entity WITHOUT moving it (no flicker). */
    public void refresh(String id) {
        TextDisplay td = entity(id);
        if (td == null || !td.isValid()) { spawn(id); return; }
        try { applyStyle(id, td); } catch (Exception ignored) {}
        try { td.text(TextUtil.prefixComponent(String.join("\n", lines(id)))); } catch (Exception ignored) {}
    }

    public int respawnAll() {
        int n = 0;
        for (String id : ids()) { try { if (spawn(id)) n++; } catch (Exception ignored) {} }
        return n;
    }

    /** Slow upkeep: bring back any hologram whose chunk has since loaded. */
    public void tick() {
        if (!enabled()) return;
        for (String id : ids()) {
            try {
                TextDisplay td = entity(id);
                if (td == null || !td.isValid()) spawn(id);
            } catch (Exception ignored) {}
        }
    }

    /** Boot: drop every tagged display (ours, possibly orphaned), then respawn. */
    public void boot() {
        sweepOrphans();
        if (!enabled()) return;
        for (String id : ids()) { try { spawn(id); } catch (Exception ignored) {} }
    }

    /** Remove every display carrying our tag across all worlds. */
    public void sweepOrphans() {
        try {
            for (World w : Bukkit.getWorlds()) {
                for (Entity en : w.getEntities()) {
                    if (!(en instanceof TextDisplay)) continue;
                    try { if (en.getScoreboardTags().contains(TAG)) en.remove(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        try { live.clear(); } catch (Exception ignored) {}
    }

    /** Clear untracked tagged displays near a spot so duplicates can't pile up. */
    private void sweepSpot(Location loc) {
        try {
            for (Entity en : loc.getWorld().getNearbyEntities(loc, 1.5, 2.0, 1.5)) {
                if (!(en instanceof TextDisplay)) continue;
                if (!en.getScoreboardTags().contains(TAG)) continue;
                if (live.containsValue(en.getUniqueId())) continue;
                try { en.remove(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // ---------- style application ----------

    public void applyStyle(String id, TextDisplay td) {
        try { td.setBillboard(billboardOf(styleOrDefault(id, "billboard"))); } catch (Exception ignored) {}
        try { td.setAlignment(alignOf(styleOrDefault(id, "align"))); } catch (Exception ignored) {}

        String bg = styleOrDefault(id, "background");
        if (bg != null && TextUtil.norm(bg).equals("default")) {
            try { td.setDefaultBackground(true); } catch (Throwable ignored) {}
        } else {
            try { td.setDefaultBackground(false); } catch (Throwable ignored) {}
            try { td.setBackgroundColor(parseColor(bg, Color.fromARGB(128, 0, 0, 0))); } catch (Exception ignored) {}
        }

        try {
            td.setLineWidth(clampInt(intStyle(id, "linewidth",
                    plugin.getConfig().getInt("holotext.default-line-width", 300)), 1, 1000));
        } catch (Exception ignored) {}
        try {
            td.setTextOpacity((byte) clampInt(intStyle(id, "opacity",
                    plugin.getConfig().getInt("holotext.default-text-opacity", -1)), -1, 127));
        } catch (Exception ignored) {}
        try { td.setSeeThrough(boolStyle(id, "seethrough",
                plugin.getConfig().getBoolean("holotext.default-see-through", false))); } catch (Exception ignored) {}
        try { td.setShadowed(boolStyle(id, "shadow",
                plugin.getConfig().getBoolean("holotext.default-shadow", false))); } catch (Exception ignored) {}
        try { td.setGlowing(boolStyle(id, "glow", false)); } catch (Exception ignored) {}
        try {
            td.setViewRange((float) clampDouble(doubleStyle(id, "viewrange",
                    plugin.getConfig().getDouble("holotext.default-view-range", 0.6)), 0.01, 2.0));
        } catch (Exception ignored) {}
        try {
            double s = clampDouble(doubleStyle(id, "scale", 1.0), 0.05, 10.0);
            if (Math.abs(s - 1.0) > 1e-6) {
                org.bukkit.util.Transformation t = td.getTransformation();
                org.joml.Vector3f v = t.getScale();
                v.set((float) s, (float) s, (float) s);
                td.setTransformation(t);
            }
        } catch (Throwable ignored) {}
    }

    private String styleOrDefault(String id, String key) {
        try {
            String v = style(id, key);
            if (v != null) return v;
            if (key.equals("billboard")) return plugin.getConfig().getString("holotext.default-billboard", "fixed");
        } catch (Exception ignored) {}
        return null;
    }

    private static Display.Billboard billboardOf(String v) {
        if (v != null) {
            switch (TextUtil.norm(v)) {
                case "center": case "centre": case "look": case "camera": return Display.Billboard.CENTER;
                case "vertical": case "v": return Display.Billboard.VERTICAL;
                case "horizontal": case "h": return Display.Billboard.HORIZONTAL;
                default: return Display.Billboard.FIXED;
            }
        }
        return Display.Billboard.FIXED;
    }

    private static TextDisplay.TextAlignment alignOf(String v) {
        if (v != null) {
            switch (TextUtil.norm(v)) {
                case "left": return TextDisplay.TextAlignment.LEFT;
                case "right": return TextDisplay.TextAlignment.RIGHT;
                default: return TextDisplay.TextAlignment.CENTER;
            }
        }
        return TextDisplay.TextAlignment.CENTER;
    }

    public static Color parseColor(String v, Color def) {
        try {
            if (v == null) return def;
            String s = v.trim();
            if (s.equalsIgnoreCase("none") || s.equalsIgnoreCase("clear")
                    || s.equalsIgnoreCase("transparent")) return Color.fromARGB(0, 0, 0, 0);
            if (s.startsWith("#")) s = s.substring(1);
            int a = 255, r, g, b;
            if (s.length() == 8) {
                a = Integer.parseInt(s.substring(0, 2), 16);
                r = Integer.parseInt(s.substring(2, 4), 16);
                g = Integer.parseInt(s.substring(4, 6), 16);
                b = Integer.parseInt(s.substring(6, 8), 16);
            } else if (s.length() == 6) {
                r = Integer.parseInt(s.substring(0, 2), 16);
                g = Integer.parseInt(s.substring(2, 4), 16);
                b = Integer.parseInt(s.substring(4, 6), 16);
            } else return def;
            return Color.fromARGB(a, r, g, b);
        } catch (Exception e) { return def; }
    }

    public static boolean isColorValue(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.equalsIgnoreCase("none") || s.equalsIgnoreCase("default")
                || s.equalsIgnoreCase("clear") || s.equalsIgnoreCase("transparent")) return true;
        if (s.startsWith("#")) s = s.substring(1);
        return (s.length() == 6 || s.length() == 8) && s.matches("[0-9A-Fa-f]+");
    }

    private int intStyle(String id, String key, int def) {
        try { String v = style(id, key); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }

    private double doubleStyle(String id, String key, double def) {
        try { String v = style(id, key); return v == null ? def : Double.parseDouble(v.trim()); }
        catch (Exception e) { return def; }
    }

    private boolean boolStyle(String id, String key, boolean def) {
        try {
            String v = style(id, key);
            if (v == null) return def;
            String s = TextUtil.norm(v);
            if (s.equals("true") || s.equals("on") || s.equals("yes") || s.equals("1")) return true;
            if (s.equals("false") || s.equals("off") || s.equals("no") || s.equals("0")) return false;
            return def;
        } catch (Exception e) { return def; }
    }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static double clampDouble(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
