package com.hush.allpurpose;

import java.awt.Graphics2D;
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
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

/**
 * Spawn snapshot: top-down render of spawn, edited into ONE Discord message
 * every few minutes (no channel spam, no new infra, no player slot).
 *
 * Capture runs on the main thread (chunk snapshots are cheap copies, unloaded
 * chunks are skipped, never generated); render + upload run async. Uploads
 * via the same bot token as the status board (discord-status.bot-token).
 */
public class SpawnSnapshot {

    private static final String API = "https://discord.com/api/v10";

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
            final int cx = spawn.getBlockX();
            final int cz = spawn.getBlockZ();
            final int size = r * 2;
            // Sync capture: one int per column (rgb, -1 = unloaded).
            final int[] cols = new int[size * size];
            try {
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        try {
                            for (int dz = 0; dz < size; dz++) {
                                for (int dx = 0; dx < size; dx++) {
                                    cols[dz * size + dx] = columnColor(w, cx - r + dx, cz - r + dz);
                                }
                            }
                        } catch (Exception ignored) {}
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                            @Override public void run() { renderAndUpload(cols, size); }
                        });
                    }
                });
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /** Top-down color for one block column. -1 when the chunk isn't loaded. */
    private static int columnColor(World w, int x, int z) {
        try {
            int ccx = x >> 4;
            int ccz = z >> 4;
            if (!w.isChunkLoaded(ccx, ccz)) return -1;
            ChunkSnapshot snap;
            try { snap = w.getChunkAt(ccx, ccz).getChunkSnapshot(true, false, false); }
            catch (Exception e) { return -1; }
            int lx = x & 15;
            int lz = z & 15;
            int y = snap.getHighestBlockYAt(lx, lz);
            Material m;
            try { m = snap.getBlockType(lx, y, lz); } catch (Exception e) { return -1; }
            if (m == null || m.isAir()) return -1;
            int base = blockColor(m);
            // Height shading for depth (water stays flat).
            if (m.name().contains("WATER") || m.name().contains("LAVA")) return base;
            double f = 0.72 + 0.28 * Math.min(1.0, Math.max(0.0, (y - 55.0) / 45.0));
            int r = Math.min(255, (int) (((base >> 16) & 0xFF) * f));
            int g = Math.min(255, (int) (((base >> 8) & 0xFF) * f));
            int b = Math.min(255, (int) ((base & 0xFF) * f));
            return (r << 16) | (g << 8) | b;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Classic map colors by material name. */
    private static int blockColor(Material m) {
        String n = m.name();
        if (n.contains("WATER")) return 0x3F76E4;
        if (n.contains("LAVA")) return 0xE25822;
        if (n.equals("GRASS_BLOCK")) return 0x91BD59;
        if (n.contains("LEAVES") || n.equals("MOSS_BLOCK") || n.contains("MOSS_CARPET")) return 0x3E7A32;
        if (n.contains("LOG") || n.equals("BROWN_MUSHROOM_BLOCK")) return 0x6B5230;
        if (n.contains("PLANKS") || n.contains("SIGN") || n.contains("FENCE") || n.contains("STAIRS") && n.contains("OAK")) return 0xA0824D;
        if (n.contains("DIRT") || n.contains("FARMLAND") || n.contains("PODZOL") || n.contains("ROOTED")) return 0x8A5F3C;
        if (n.contains("SANDSTONE") || n.equals("SAND") || n.equals("RED_SAND")) return 0xDBD3A0;
        if (n.contains("GRAVEL")) return 0x9A8E7D;
        if (n.contains("STONE") || n.contains("COBBLE") || n.contains("ANDESITE") || n.contains("GRANITE") || n.contains("DIORITE")) return 0x7D7D7D;
        if (n.contains("DEEPSLATE") || n.contains("TUFF") || n.contains("BASALT")) return 0x4A4A52;
        if (n.contains("OBSIDIAN") || n.contains("BEDROCK") || n.contains("CRYING")) return 0x2B2B33;
        if (n.contains("SNOW") || n.contains("ICE") || n.contains("FROSTED")) return 0xE8F4F8;
        if (n.contains("GLASS")) return 0xBFE3EA;
        if (n.contains("WOOL") || n.contains("CARPET") && !n.contains("MOSS")) return 0xE8E8E8;
        if (n.contains("ORE") || n.contains("ANCIENT_DEBRIS")) return 0x8D8D93;
        if (n.contains("BRICKS") || n.contains("TERRACOTTA")) return 0x9C5B4B;
        if (n.contains("NETHERRACK") || n.contains("CRIMSON") || n.contains("WARPED")) return 0x6E2B2B;
        if (n.contains("SOUL")) return 0x5B4A3A;
        if (n.contains("END_STONE") || n.contains("PURPUR")) return 0xDDE0A8;
        if (n.contains("FLOWER") || n.contains("TULIP") || n.contains("SAPLING") || n.contains("CROP") || n.contains("WHEAT") || n.contains("CARROT") || n.contains("POTATO")) return 0x5DA244;
        if (n.contains("TORCH") || n.contains("LANTERN") || n.contains("GLOWSTONE") || n.contains("SEA_LANTERN") || n.contains("SHROOMLIGHT")) return 0xF5D76E;
        if (n.contains("CHEST") || n.contains("CRAFTING") || n.contains("BARREL") || n.contains("FURNACE")) return 0x7A5C38;
        if (n.contains("BED")) return 0xB03434;
        return 0x777777;
    }

    private void renderAndUpload(int[] cols, int size) {
        try {
            if (!enabled()) return;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < cols.length; i++) {
                int c = cols[i];
                img.setRGB(i % size, i / size, c < 0 ? 0x14141A : c);
            }
            int out = Math.min(512, size * 2);
            BufferedImage big = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = big.createGraphics();
            try { g.drawImage(img, 0, 0, out, out, null); } finally { g.dispose(); }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(200000);
            ImageIO.write(big, "png", bos);
            byte[] png = bos.toByteArray();
            if (png == null || png.length < 500 || png.length > 8000000) return;
            if (messageId == null || messageId.isEmpty()) {
                String found = lookupExisting();
                if (found != null) saveId(found);
            }
            long unix = Instant.now().getEpochSecond();
            String caption = "Spawn · updated <t:" + unix + ":R>";
            if (messageId != null && !messageId.isEmpty()) {
                String[] created = new String[1];
                int code = sendMultipart(true, messageId, caption, png, created);
                if (code == 404) {
                    messageId = null;
                    String[] created2 = new String[1];
                    sendMultipart(false, null, caption, png, created2);
                    if (created2[0] != null) saveId(created2[0]);
                }
            } else {
                String[] created = new String[1];
                sendMultipart(false, null, caption, png, created);
                if (created[0] != null) saveId(created[0]);
            }
        } catch (Exception ignored) {}
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
    private int sendMultipart(boolean edit, String id, String caption, byte[] png, String[] outId) {
        try {
            String boundary = "AllPurpose" + UUID.randomUUID().toString().replace("-", "");
            String json = "{\"content\":" + json(caption)
                    + ",\"embeds\":[{\"image\":{\"url\":\"attachment://spawn.png\"},\"color\":5763719}]"
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
