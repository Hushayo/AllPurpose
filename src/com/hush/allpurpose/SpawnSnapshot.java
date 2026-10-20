package com.hush.allpurpose;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Spawn snapshot: top-down render of spawn, edited into ONE Discord message
 * every few minutes (no channel spam, no new infra, no player slot).
 *
 * Two things this must do to be worth looking at:
 *  - read like a map (real block colours, local height shading, grid, compass),
 *  - show WHO is there (pins + a legend with distance/direction), because the
 *    spawn build itself never changes.
 *
 * Positions are deliberately coarse. A pin is the centre of a 16-block cell,
 * distances round to 16 blocks, Y is a 16-block bucket and facing is not shown
 * at all: the view reaches past the spawn region (pvp: deny), so an exact pin
 * plus a facing tail would be a locator for hunting players in the band
 * outside it. Keep this quantised -- see CELL below.
 *
 * Capture runs on the main thread (chunk snapshots are cheap copies, unloaded
 * chunks are skipped, never generated); render + upload run async. Uploads
 * via the same bot token as the status board (discord-status.bot-token).
 */
public class SpawnSnapshot {

    private static final String API = "https://discord.com/api/v10";

    /** Pin colours, one per player (cycled). Matches PIN_DOTS below. */
    private static final int[] PIN_COLORS = {
        0xDD2E44, 0x3B88C3, 0xFFCC4D, 0x78B159, 0xAA8ED6, 0xF4900C, 0xC1694F, 0x55595F
    };
    /** Discord square emoji for the same order: red, blue, yellow, green, purple, orange, brown, black. */
    private static final String[] PIN_DOTS = {
        "\uD83D\uDFE5", "\uD83D\uDFE6", "\uD83D\uDFE8", "\uD83D\uDFE9",
        "\uD83D\uDFEA", "\uD83D\uDFE7", "\uD83D\uDFEB", "\u2B1B"
    };
    /** Flag bit on a captured colour: do not apply height shading (liquids). */
    private static final int NO_SHADE = 0x01000000;
    /**
     * Player position resolution, in blocks. Pins sit on cell centres, distances
     * round to a multiple of this and Y is floored to it, so nothing in the
     * rendered image or the legend is block-exact.
     */
    private static final int CELL = 16;

    private final AllPurpose plugin;
    private HttpClient http = null;
    private BukkitTask task = null;
    private volatile String messageId = null;
    private long lastWarnMs = 0;

    public SpawnSnapshot(AllPurpose plugin) {
        this.plugin = plugin;
    }

    // ---------- config ----------

    private boolean enabled() {
        try {
            if (!plugin.getConfig().getBoolean("discord-snapshot.enabled", true)) return false;
            return !token().isEmpty() && !channelId().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private String token() {
        try {
            String t = plugin.getConfig().getString("discord-status.bot-token", "");
            if (t == null) return "";
            t = t.trim();
            if (t.startsWith("Bot ")) t = t.substring(4).trim();
            return t;
        } catch (Exception e) {
            return "";
        }
    }

    private String channelId() {
        try {
            String c = plugin.getConfig().getString("discord-snapshot.channel-id", "");
            if (c == null || c.trim().isEmpty() || !c.trim().matches("\\d{10,25}")) {
                c = plugin.getConfig().getString("discord-status.channel-id", "1559211061553401986");
            }
            if (c == null) return "";
            c = c.trim();
            if (!c.matches("\\d{10,25}")) return "";
            return c;
        } catch (Exception e) {
            return "1559211061553401986";
        }
    }

    private int intervalMinutes() {
        try {
            int m = plugin.getConfig().getInt("discord-snapshot.interval-minutes", 5);
            if (m < 1) m = 1;
            if (m > 60) m = 60;
            return m;
        } catch (Exception e) {
            return 5;
        }
    }

    private int radius() {
        try {
            int r = plugin.getConfig().getInt("discord-snapshot.radius-blocks", 128);
            if (r < 32) r = 32;
            if (r > 256) r = 256;
            return r;
        } catch (Exception e) {
            return 128;
        }
    }

    // ---------- lifecycle ----------

    public synchronized void start() {
        try { stopTask(); } catch (Exception ignored) {}
        try { loadId(); } catch (Exception ignored) {}
        if (!enabled()) return; // silent: shares the board's token section
        long period = (long) intervalMinutes() * 20L * 60L;
        try {
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override public void run() { captureSyncThenUpload(); }
            }, 600L, period); // first shot 30s after boot
        } catch (Exception e) {
            try { plugin.getLogger().warning("Spawn snapshot failed to start: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    public synchronized void reload() {
        try { start(); } catch (Exception ignored) {}
    }

    public synchronized void shutdown() {
        try { stopTask(); } catch (Exception ignored) {}
    }

    private synchronized void stopTask() {
        try { if (task != null) task.cancel(); } catch (Exception ignored) {}
        task = null;
    }

    private File idFile() {
        return new File(plugin.getDataFolder(), "discord_snapshot_id.txt");
    }

    private void loadId() {
        messageId = null;
        try {
            File f = idFile();
            if (!f.exists()) return;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (s.matches("\\d{10,25}")) messageId = s;
        } catch (Exception ignored) {}
    }

    private void saveId(String id) {
        messageId = id;
        try {
            plugin.getDataFolder().mkdirs();
            Files.write(idFile().toPath(), id.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    // ---------- capture (main thread) then upload (async) ----------

    /** One player standing inside the rendered view. Coarse by construction. */
    private static final class Pin {
        String name = "";
        int bx, bz;   // centre of the player's CELL-sized cell, never their block
        int by;       // CELL-floored Y, never their exact height
        int dist;     // blocks from spawn, rounded to CELL
        String dir = "N";
        int dot;
    }

    /** Everything one snapshot needs, safe to touch off the main thread. */
    private static final class Frame {
        int radius, size, originX, originZ, spawnX, spawnZ;
        int minY, maxY;
        int[] colors;   // 24-bit rgb (+ NO_SHADE flag), -1 = not loaded
        int[] heights;  // highest block y per column, -1 = not loaded
        final List<Pin> pins = new ArrayList<>();
        int online;      // non-bot players online
        int outside;     // in this world, outside the view
        int outsideNearest = Integer.MAX_VALUE; // blocks to the closest one, rounded in the legend
        int otherWorld;  // nether / end / another world
    }

    private void captureSyncThenUpload() {
        try {
            if (!enabled()) return;
            final int r = radius();
            final World w;
            try {
                List<World> worlds = Bukkit.getWorlds();
                if (worlds == null || worlds.isEmpty()) return;
                w = worlds.get(0);
            } catch (Exception e) {
                return;
            }
            final Location spawn;
            try { spawn = w.getSpawnLocation(); } catch (Exception e) { return; }
            final Frame f = new Frame();
            f.radius = r;
            f.size = r * 2;
            try {
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        try { capture(w, spawn, f); } catch (Exception ignored) {}
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                            @Override public void run() { renderAndUpload(f); }
                        });
                    }
                });
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /**
     * Fills the frame on the main thread. Chunk snapshots are cached PER CHUNK:
     * one snapshot covers 16x16 columns, so a 256x256 view needs 256 copies
     * instead of 65,536 (the old code took a fresh snapshot for every single
     * block column, five minutes apart).
     */
    private void capture(World w, Location spawn, Frame f) {
        int r = f.radius;
        int size = f.size;
        int cx = spawn.getBlockX();
        int cz = spawn.getBlockZ();
        f.spawnX = cx;
        f.spawnZ = cz;
        f.originX = cx - r;
        f.originZ = cz - r;
        f.colors = new int[size * size];
        f.heights = new int[size * size];
        f.minY = Integer.MAX_VALUE;
        f.maxY = Integer.MIN_VALUE;

        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int dz = 0; dz < size; dz++) {
            int z = f.originZ + dz;
            int ccz = z >> 4;
            int lz = z & 15;
            for (int dx = 0; dx < size; dx++) {
                int x = f.originX + dx;
                int i = dz * size + dx;
                int color = -1;
                int hy = -1;
                int ccx = x >> 4;
                if (w.isChunkLoaded(ccx, ccz)) {
                    Long key = (((long) ccx) << 32) ^ (ccz & 0xFFFFFFFFL);
                    ChunkSnapshot snap = chunks.get(key);
                    if (snap == null) {
                        try {
                            snap = w.getChunkAt(ccx, ccz).getChunkSnapshot(true, false, false);
                            chunks.put(key, snap);
                        } catch (Exception e) {
                            snap = null;
                        }
                    }
                    if (snap != null) {
                        try {
                            hy = snap.getHighestBlockYAt(x & 15, lz);
                            Material m = snap.getBlockType(x & 15, hy, lz);
                            if (m != null && !m.isAir()) color = blockColor(m);
                        } catch (Exception e) {
                            color = -1;
                        }
                    }
                }
                f.colors[i] = color;
                f.heights[i] = hy;
                if (color >= 0) {
                    if (hy < f.minY) f.minY = hy;
                    if (hy > f.maxY) f.maxY = hy;
                }
            }
        }
        if (f.minY == Integer.MAX_VALUE) { f.minY = 63; f.maxY = 70; }
        if (f.maxY < f.minY) f.maxY = f.minY;

        try { collectPins(w, f, cx, cz); } catch (Exception ignored) {}
    }

    /** Reads online players (skipping bots) into pins / counters. Never stores Player objects. */
    private void collectPins(World w, Frame f, int cx, int cz) {
        int r = f.radius;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (p == null) continue;
                String name = p.getName();
                if (name == null || name.isEmpty()) continue;
                if (BotNames.isBot(plugin, name)) continue;
                f.online++;
                Location loc = p.getLocation();
                if (loc == null || loc.getWorld() == null || !loc.getWorld().getUID().equals(w.getUID())) {
                    f.otherWorld++;
                    continue;
                }
                int bx = loc.getBlockX();
                int bz = loc.getBlockZ();
                int ddx = bx - cx;
                int ddz = bz - cz;
                if (ddx < -r || ddx >= r || ddz < -r || ddz >= r) {
                    f.outside++;
                    int away = (int) Math.round(Math.sqrt((double) ddx * ddx + (double) ddz * ddz));
                    if (away < f.outsideNearest) f.outsideNearest = away;
                    continue;
                }
                Pin pin = new Pin();
                pin.name = name;
                // Quantised before it is stored: nothing downstream ever sees the real block.
                pin.bx = clampTo(cellCentre(bx), f.originX, f.originX + f.size - 1);
                pin.bz = clampTo(cellCentre(bz), f.originZ, f.originZ + f.size - 1);
                pin.by = Math.floorDiv(loc.getBlockY(), CELL) * CELL;
                int qdx = pin.bx - cx;
                int qdz = pin.bz - cz;
                pin.dist = roundDist(Math.sqrt((double) qdx * qdx + (double) qdz * qdz));
                pin.dir = compass(qdx, qdz);
                f.pins.add(pin);
            } catch (Exception ignored) {}
        }
        // Nearest first: gets colour 0 and is listed first in the legend.
        try {
            f.pins.sort(new Comparator<Pin>() {
                @Override public int compare(Pin a, Pin b) { return Integer.compare(a.dist, b.dist); }
            });
        } catch (Exception ignored) {}
        for (int i = 0; i < f.pins.size(); i++) f.pins.get(i).dot = i % PIN_COLORS.length;
    }

    /** 8-way compass label; north = -z. */
    private static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0) deg += 360;
        int sector = (int) Math.round(deg / 45.0) % 8;
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[sector];
    }

    /** Centre of the CELL-sized cell containing a coordinate. */
    private static int cellCentre(int v) {
        return Math.floorDiv(v, CELL) * CELL + CELL / 2;
    }

    /** Distance rounded to whole cells; one cell minimum means "at spawn". */
    private static int roundDist(double d) {
        int q = (int) Math.round(d / CELL) * CELL;
        return Math.max(CELL, q);
    }

    private static int clampTo(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------- palette ----------

    private static final String[] DYES = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    private static final int[] CONCRETE = {
        0xCFD5D6, 0xE0721B, 0xA9309B, 0x2389C6, 0xF1AF15, 0x5EA818, 0xF5AEC4, 0x36393D,
        0x7D7D73, 0x157788, 0x64209C, 0x2C2E8F, 0x603C20, 0x495B24, 0x8E2121, 0x080A0F
    };
    private static final int[] WOOL = {
        0xE9ECEC, 0xF07613, 0xBD44B3, 0x3AAFD9, 0xF8C627, 0x70B919, 0xED8DAC, 0x3E4447,
        0x8E8E86, 0x158991, 0x792AAC, 0x35399D, 0x724728, 0x546D1B, 0xA12722, 0x141519
    };
    private static final int[] TERRACOTTA = {
        0xD1B1A1, 0xA15325, 0x95576C, 0x706C8A, 0xBA8523, 0x677534, 0xA04D4E, 0x392923,
        0x876B62, 0x575B5B, 0x6A4B70, 0x4A3C6B, 0x4C3223, 0x4B522B, 0x8E3C2E, 0x251610
    };

    private static final String[] WOOD_SPECIES = {
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
        "cherry", "pale_oak", "bamboo", "crimson", "warped"
    };
    private static final int[] PLANKS = {
        0xBC9862, 0x745A34, 0xD7C178, 0xA8784E, 0xAF5D2F, 0x4C351B, 0x7A4A31,
        0xE1B6B8, 0xDCD3C3, 0xC6B55B, 0x6A3A55, 0x2E7C74
    };
    private static final int[] BARK = {
        0x8F6B43, 0x3B2C1B, 0xD8CDA0, 0x55451C, 0x674D33, 0x3C2A16, 0x4D3A28,
        0x35201F, 0x6C6152, 0x7F7A35, 0x4A2530, 0x2E5A56
    };
    private static final int[] LEAVES = {
        0x3F6E28, 0x2C3E22, 0x6B8C43, 0x2F7A2A, 0x5C7C28, 0x2C4C1E, 0x3A6B2C,
        0xE1A3B4, 0x5C8A4A, 0x4E7530, 0x5C3A2E, 0x3A7A6E
    };

    /**
     * Top-down colour for one block. Deliberately close to the vanilla map
     * palette so quartz reads white, stone grey, path tan, concrete dyed etc.
     * Ordering matters: specific names first, broad key-word fallbacks last.
     */
    private static int blockColor(Material m) {
        String n = m.name();
        // --- liquids ---
        if (n.contains("WATER")) return 0x3F76E4 | NO_SHADE;
        if (n.contains("LAVA")) return 0xE25822 | NO_SHADE;

        // --- dyed families (concrete / wool / carpet / terracotta / stained glass) ---
        int dye = dyeIndex(n);
        if (dye >= 0) {
            if (n.endsWith("_CONCRETE") || n.endsWith("_CONCRETE_POWDER")) return CONCRETE[dye];
            if (n.endsWith("_WOOL") || n.endsWith("_CARPET")) return WOOL[dye];
            if (n.endsWith("_TERRACOTTA") || n.endsWith("_GLAZED_TERRACOTTA")) return TERRACOTTA[dye];
            if (n.contains("STAINED_GLASS")) return mix(WOOL[dye], 0xFFFFFF, 0.45);
            if (n.endsWith("_BED")) return mix(WOOL[dye], 0x000000, 0.25);
        }

        // --- wood species (planks / logs / leaves / stairs / fences / signs ...) ---
        String wood = n.startsWith("STRIPPED_") ? n.substring(9) : n;
        int sp = speciesIndex(wood);
        if (sp >= 0) {
            if (wood.contains("LEAVES") || wood.contains("SAPLING") || wood.contains("PETAL")) return LEAVES[sp];
            if (wood.contains("LOG") || wood.contains("WOOD") || wood.contains("HYPHAE")
                    || wood.contains("STEM") || wood.contains("BAMBOO_BLOCK")) return BARK[sp];
            if (wood.contains("PLANKS") || wood.contains("_SLAB") || wood.contains("STAIRS")
                    || wood.contains("FENCE") || wood.contains("DOOR") || wood.contains("TRAPDOOR")
                    || wood.contains("SIGN") || wood.contains("SHELF") || wood.contains("BUTTON")
                    || wood.contains("PRESSURE_PLATE") || wood.contains("BOAT")) return PLANKS[sp];
        }

        // --- ground cover ---
        if (n.equals("GRASS_BLOCK") || n.equals("GRASS") || n.equals("SHORT_GRASS")
                || n.equals("TALL_GRASS") || n.equals("FERN") || n.equals("LARGE_FERN")) return 0x91BD59;
        if (n.contains("MYCELIUM")) return 0x6F6265;
        if (n.contains("PODZOL")) return 0x6D4B2B;
        if (n.contains("NYLIUM")) return n.contains("CRIMSON") ? 0x8E2B2B : 0x2E7C74;
        if (n.contains("ROOTED_DIRT") || n.contains("MUD")) return 0x8A6A55;
        if (n.equals("CLAY")) return 0xA4A8B8;
        if (n.contains("DIRT") || n.contains("FARMLAND")) return 0x8A5A3B;
        if (n.equals("MOSS_BLOCK") || n.contains("MOSS_CARPET") || n.contains("MOSS_VEIN")) return 0x4F6E34;
        if (n.contains("SANDSTONE")) return 0xD9CE9B;
        if (n.equals("SAND") || n.equals("RED_SAND")) return 0xE0D6A8;
        if (n.contains("GRAVEL") || n.contains("SUSPICIOUS")) return 0x9A8E7D;
        if (n.contains("SNOW") || n.contains("POWDER_SNOW")) return 0xF3FBFB;
        if (n.equals("ICE") || n.contains("FROSTED_ICE")) return 0x9BC4F0;
        if (n.contains("BLUE_ICE") || n.contains("PACKED_ICE")) return 0x9EC7E6;

        // --- stone family ---
        if (n.contains("QUARTZ")) return 0xE6E1DA;
        if (n.contains("DEEPSLATE")) return 0x4A4A52;
        if (n.contains("BLACKSTONE")) return 0x2E2A2E;
        if (n.contains("BASALT") || n.contains("OBSIDIAN") || n.contains("BEDROCK")
                || n.contains("CRYING") || n.contains("ANCIENT_DEBRIS") || n.contains("NETHERITE")) return 0x2B2B33;
        if (n.contains("TUFF")) return 0x5A5A50;
        if (n.contains("CALCITE")) return 0xD6D6CE;
        if (n.contains("DIORITE")) return 0xBFBFBF;
        if (n.contains("GRANITE")) return 0x9C6B5B;
        if (n.contains("ANDESITE")) return 0x8A8C8E;
        if (n.contains("COBBLE")) return 0x7A7A7A;
        if (n.contains("STONE_BRICK") || n.contains("SMOOTH_STONE")) return 0x929292;
        if (n.contains("STONE")) return 0x7F7F7F;
        if (n.contains("PRISMARINE")) return 0x6E9897;
        if (n.contains("TERRACOTTA")) return 0x9C5B4B;
        if (n.contains("BRICKS") || n.contains("BRICK")) return 0x9A5A4A;

        // --- metals / gems ---
        if (n.contains("COPPER_BLOCK") || n.contains("CUT_COPPER") || n.contains("CHISELED_COPPER")) {
            if (n.contains("OXIDIZED")) return 0x4F8C7A;
            if (n.contains("WEATHERED")) return 0x6D8A6E;
            if (n.contains("EXPOSED")) return 0x9C714B;
            return 0xB4644B;
        }
        if (n.equals("IRON_BLOCK") || n.contains("IRON_BARS") || n.contains("ANVIL")) return 0xD8D8D8;
        if (n.equals("GOLD_BLOCK")) return 0xF6D33C;
        if (n.contains("DIAMOND_BLOCK")) return 0x63DBD0;
        if (n.contains("EMERALD_BLOCK")) return 0x35C566;
        if (n.contains("LAPIS_BLOCK")) return 0x2A4B9C;
        if (n.equals("REDSTONE_BLOCK")) return 0xB01E1E;
        if (n.contains("COAL_BLOCK")) return 0x1A1A1A;
        if (n.contains("AMETHYST") || n.contains("PURPUR")) return 0x9A7BC8;
        if (n.contains("SLIME_BLOCK")) return 0x77C56C;
        if (n.contains("HONEY")) return 0xD79A2C;
        if (n.contains("SOUL_SOIL") || n.contains("SOUL_SAND")) return 0x5B4A3A;
        if (n.contains("_ORE")) return 0x8D8D93;

        // --- light sources / utility ---
        if (n.contains("GLOWSTONE") || n.contains("SHROOMLIGHT") || n.contains("FROGLIGHT")
                || n.contains("SEA_LANTERN") || n.contains("TORCH") || n.contains("LANTERN")
                || n.contains("CAMPFIRE") || n.contains("CANDLE")) return 0xF0D070;
        if (n.contains("GLASS")) return 0xC9E6EC;
        if (n.contains("CHEST") || n.contains("CRAFTING") || n.contains("BARREL")
                || n.contains("FURNACE") || n.contains("SMOKER") || n.contains("LECTERN")
                || n.contains("BOOKSHELF") || n.contains("JUKEBOX")) return 0x8A6238;
        if (n.contains("NETHERRACK")) return 0x6E2B2B;
        if (n.contains("END_STONE")) return 0xDDE0A8;
        if (n.contains("CACTUS") || n.contains("BAMBOO") || n.contains("SUGAR_CANE")) return 0x5D7C3A;
        if (n.contains("MELON") || n.contains("PUMPKIN")) return 0x9C8B2B;
        if (n.contains("HAY_BLOCK")) return 0xC5A11F;
        if (n.contains("LEAVES") || n.contains("VINE") || n.contains("AZALEA")) return 0x3E7A32;
        if (n.contains("FLOWER") || n.contains("TULIP") || n.contains("CROP") || n.contains("WHEAT")
                || n.contains("CARROT") || n.contains("POTATO") || n.contains("BEETROOT")
                || n.contains("BERRY") || n.contains("MUSHROOM") || n.contains("ROOTS")
                || n.contains("SPROUT")) return 0x5DA244;
        return 0x7A7A7A;
    }

    /** Index into DYES for a dyed block name, or -1. */
    private static int dyeIndex(String n) {
        for (int i = 0; i < DYES.length; i++) {
            if (n.startsWith(DYES[i] + "_")) return i;
        }
        return -1;
    }

    /** Index into the wood tables for "<species>_..." (stripped already removed), or -1. */
    private static int speciesIndex(String n) {
        for (int i = 0; i < WOOD_SPECIES.length; i++) {
            if (n.startsWith(WOOD_SPECIES[i] + "_")) return i;
        }
        return -1;
    }

    private static int mix(int rgb, int with, double amount) {
        int r = (int) (((rgb >> 16) & 0xFF) * (1 - amount) + ((with >> 16) & 0xFF) * amount);
        int g = (int) (((rgb >> 8) & 0xFF) * (1 - amount) + ((with >> 8) & 0xFF) * amount);
        int b = (int) ((rgb & 0xFF) * (1 - amount) + (with & 0xFF) * amount);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---------- render ----------

    private void renderAndUpload(Frame f) {
        try {
            if (!enabled()) return;
            BufferedImage big = render(f);
            if (big == null) return;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(400000);
            ImageIO.write(big, "png", bos);
            byte[] png = bos.toByteArray();
            if (png == null || png.length < 500 || png.length > 8000000) return;
            if (messageId == null || messageId.isEmpty()) {
                String found = lookupExisting();
                if (found != null) saveId(found);
            }
            long unix = Instant.now().getEpochSecond();
            String caption = "Spawn \u00B7 north \u2191 \u00B7 updated <t:" + unix + ":R>";
            String desc = legend(f);
            int color = f.pins.isEmpty() ? 0x4E5058 : 0x57F287;
            if (messageId != null && !messageId.isEmpty()) {
                String[] created = new String[1];
                int code = sendMultipart(true, messageId, caption, desc, color, png, created);
                if (code == 404) {
                    messageId = null;
                    String[] created2 = new String[1];
                    sendMultipart(false, null, caption, desc, color, png, created2);
                    if (created2[0] != null) saveId(created2[0]);
                }
            } else {
                String[] created = new String[1];
                sendMultipart(false, null, caption, desc, color, png, created);
                if (created[0] != null) saveId(created[0]);
            }
        } catch (Exception ignored) {}
    }

    /** Legend under the image: who is standing where inside the view. */
    private String legend(Frame f) {
        StringBuilder d = new StringBuilder();
        try {
            if (f.pins.isEmpty()) {
                if (f.online <= 0) d.append("Nobody online right now.");
                else d.append("No players within ").append(f.radius).append(" blocks of spawn.");
            } else {
                d.append("**").append(f.pins.size()).append(f.pins.size() == 1 ? " player" : " players")
                        .append("** around spawn (").append(CELL).append("-block cells):\n");
                int shown = 0;
                for (Pin p : f.pins) {
                    if (shown++ >= 10) {
                        d.append("+").append(f.pins.size() - 10).append(" more\n");
                        break;
                    }
                    d.append(PIN_DOTS[p.dot]).append(" **").append(p.name).append("** \u00B7 ");
                    if (p.dist <= CELL) d.append("at spawn");
                    else d.append("~").append(p.dist).append(" blocks ").append(p.dir);
                    d.append(" \u00B7 ~y").append(p.by).append("\n");
                }
            }
            if (f.outside > 0) {
                d.append("\n").append(f.outside).append(f.outside == 1 ? " player is" : " players are")
                        .append(" online away from spawn");
                if (f.outsideNearest != Integer.MAX_VALUE) {
                    // Rounded to 50 blocks: a rough "how far", not a base locator.
                    int near = Math.max(50, (int) (Math.round(f.outsideNearest / 50.0) * 50));
                    d.append(" (nearest ~").append(near).append(" blocks)");
                }
                d.append(".");
            }
            if (f.otherWorld > 0) {
                d.append("\n").append(f.otherWorld).append(f.otherWorld == 1 ? " player is" : " players are")
                        .append(" in another world.");
            }
        } catch (Exception ignored) {}
        String s = d.toString().trim();
        return s.length() > 1000 ? s.substring(0, 1000) + "\u2026" : s;
    }

    private BufferedImage render(Frame f) {
        int size = f.size;
        if (size <= 0 || f.colors == null || f.colors.length < size * size) return null;
        int scale = 1024 / Math.max(1, size);
        if (scale < 2) scale = 2;
        if (scale > 6) scale = 6;
        int out = size * scale;
        BufferedImage img = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);

        // 1) block colours + local height shading (so slopes read as slopes)
        int span = Math.max(1, f.maxY - f.minY);
        double lift = span >= 6 ? 0.34 : 0.0; // flat ground must not turn into noise
        for (int i = 0; i < size * size; i++) {
            int c = f.colors[i];
            int rgb;
            if (c < 0) {
                rgb = 0x121218; // not loaded / no data
            } else {
                int base = c & 0xFFFFFF;
                if ((c & NO_SHADE) == 0 && lift > 0.0) {
                    int y = f.heights[i];
                    double t = (y - f.minY) / (double) span;
                    double k = (1.0 - lift) + lift * Math.max(0.0, Math.min(1.0, t));
                    base = mix(base, 0x000000, 1.0 - k);
                }
                rgb = base;
            }
            int px = (i % size) * scale;
            int py = (i / size) * scale;
            for (int yy = 0; yy < scale; yy++) {
                int row = py + yy;
                for (int xx = 0; xx < scale; xx++) img.setRGB(px + xx, row, rgb);
            }
        }

        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // 2) faint 32-block grid for scale
            g.setColor(new Color(0, 0, 0, 38));
            g.setStroke(new BasicStroke(1f));
            int gx = Math.floorDiv(f.originX + 31, 32) * 32;
            for (int x = gx; x < f.originX + size; x += 32) {
                int px = (x - f.originX) * scale;
                g.drawLine(px, 0, px, out - 1);
            }
            int gz = Math.floorDiv(f.originZ + 31, 32) * 32;
            for (int z = gz; z < f.originZ + size; z += 32) {
                int pz = (z - f.originZ) * scale;
                g.drawLine(0, pz, out - 1, pz);
            }

            // 3) spawn marker: white cross with dark outline
            int mx = (f.spawnX - f.originX) * scale + scale / 2;
            int mz = (f.spawnZ - f.originZ) * scale + scale / 2;
            int arm = Math.max(9, scale * 4);
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(0x101014));
            g.drawLine(mx - arm, mz, mx + arm, mz);
            g.drawLine(mx, mz - arm, mx, mz + arm);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.WHITE);
            g.drawLine(mx - arm, mz, mx + arm, mz);
            g.drawLine(mx, mz - arm, mx, mz + arm);

            // 4) player pins, farthest first so the nearest ends up on top.
            //    Each pin is a CELL-sized square with a disc in its centre: the
            //    player is somewhere in that area, not on that pixel. No facing
            //    tail -- yaw is the most tactical detail and is never published.
            int radius = Math.max(7, scale * 3);
            for (int i = f.pins.size() - 1; i >= 0; i--) {
                Pin p = f.pins.get(i);
                int rgb = PIN_COLORS[p.dot];
                int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
                int cellX = (p.bx - CELL / 2 - f.originX) * scale;
                int cellZ = (p.bz - CELL / 2 - f.originZ) * scale;
                g.setColor(new Color(cr, cg, cb, 70));
                g.fillRect(cellX, cellZ, CELL * scale, CELL * scale);
                g.setColor(new Color(cr, cg, cb, 160));
                g.setStroke(new BasicStroke(1.5f));
                g.drawRect(cellX, cellZ, CELL * scale, CELL * scale);
                // disc: dark outline, white ring, colour
                int px = (p.bx - f.originX) * scale + scale / 2;
                int py = (p.bz - f.originZ) * scale + scale / 2;
                disc(g, px, py, radius + 3, 0x101014);
                disc(g, px, py, radius + 2, 0xFFFFFF);
                disc(g, px, py, radius, rgb);
            }

            // 5) compass (north is up)
            drawCompass(g, out);

            // 6) frame
            g.setColor(new Color(0x0C0C10));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(0, 0, out - 1, out - 1);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void disc(Graphics2D g, int x, int y, int r, int rgb) {
        g.setColor(new Color(rgb));
        g.fillOval(x - r, y - r, r * 2, r * 2);
    }

    private static void drawCompass(Graphics2D g, int out) {
        int r = Math.max(16, out / 28);
        int cx = out - r - 10;
        int cy = r + 10;
        g.setColor(new Color(0x0C0C10, false));
        g.fillOval(cx - r - 3, cy - r - 3, (r + 3) * 2, (r + 3) * 2);
        g.setColor(new Color(0xFFFFFF));
        g.setStroke(new BasicStroke(2f));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);
        int[] xs = {cx, cx - r * 2 / 3, cx + r * 2 / 3};
        int[] ys = {cy - r * 3 / 4, cy + r / 2, cy + r / 2};
        g.setColor(new Color(0xFFFFFF));
        g.fillPolygon(xs, ys, 3);
        g.setColor(new Color(0x101014));
        g.setStroke(new BasicStroke(1.5f));
        g.drawPolygon(xs, ys, 3);
    }

    // ---------- Discord REST (multipart for the image) ----------

    private HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        }
        return http;
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 5 * 60 * 1000) return;
        lastWarnMs = now;
        try { plugin.getLogger().warning(msg); } catch (Exception ignored) {}
    }

    /**
     * POST (create) or PATCH (edit) a message carrying spawn.png.
     * Returns the HTTP status; created id goes into outId[0].
     */
    private int sendMultipart(boolean edit, String id, String caption, String desc, int color,
                              byte[] png, String[] outId) {
        try {
            String boundary = "AllPurpose" + UUID.randomUUID().toString().replace("-", "");
            String json = "{\"content\":" + json(caption)
                    + ",\"embeds\":[{\"description\":" + json(desc)
                    + ",\"image\":{\"url\":\"attachment://spawn.png\"}"
                    + ",\"color\":" + color + "}]"
                    + ",\"attachments\":[{\"id\":0,\"filename\":\"spawn.png\"}]"
                    + ",\"allowed_mentions\":{\"parse\":[]}}";
            byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + json
                    + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"spawn.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[head.length + png.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(png, 0, body, head.length, png.length);
            System.arraycopy(tail, 0, body, head.length + png.length, tail.length);
            String url = edit ? API + "/channels/" + channelId() + "/messages/" + id
                    : API + "/channels/" + channelId() + "/messages";
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("User-Agent", "DiscordBot (AllPurpose spawn snapshot, 1.0)");
            if (edit) rb.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body));
            else rb.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<String> res = client().send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int code = res == null ? -1 : res.statusCode();
            if (!edit && (code == 200 || code == 201) && outId != null && outId.length > 0) {
                outId[0] = firstId(res.body(), channelId());
            }
            if (code == 401) warn("Spawn snapshot: 401 — bot token invalid.");
            else if (code == 403) warn("Spawn snapshot: 403 — bot cannot post in channel " + channelId() + ".");
            else if (code == 404 && !edit) warn("Spawn snapshot: 404 — channel " + channelId() + " not found.");
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Adopt a previous snapshot message (bot's own, carries spawn.png). */
    private String lookupExisting() {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(API + "/channels/" + channelId() + "/messages?limit=50"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose spawn snapshot, 1.0)")
                    .GET()
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res == null || res.statusCode() != 200 || res.body() == null) return null;
            String body = res.body();
            if (!body.contains("spawn.png")) return null;
            Pattern p = Pattern.compile("\"id\"\\s*:\\s*\"(\\d{10,25})\"");
            Matcher m = p.matcher(body);
            List<Integer> starts = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            while (m.find()) {
                starts.add(m.start());
                ids.add(m.group(1));
            }
            for (int i = 0; i < ids.size(); i++) {
                String cur = ids.get(i);
                if (cur.equals(channelId())) continue;
                int from = starts.get(i);
                int to = (i + 1 < starts.size()) ? starts.get(i + 1) : Math.min(body.length(), from + 12000);
                if (to <= from) continue;
                if (body.substring(from, to).contains("spawn.png")) return cur;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstId(String body, String channelId) {
        try {
            if (body == null) return null;
            Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"(\\d{10,25})\"").matcher(body);
            while (m.find()) {
                String id = m.group(1);
                if (!id.equals(channelId)) return id;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
