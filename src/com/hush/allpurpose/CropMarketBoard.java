package com.hush.allpurpose;

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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.scheduler.BukkitTask;

/**
 * The crops market board: ONE message in the Discord channel, edited in place
 * forever (same trick as the status board - the id is remembered in
 * discord_cropmarket_id.txt, and a lost id adopts the existing message by its
 * marker instead of posting a duplicate).
 *
 * Every crop's line carries its real item texture as a guild emoji (uploaded
 * lazily, a couple per refresh, through DiscordStatusBoard#uploadEmoji so the
 * guild only ever holds one copy of each icon; unicode fallback otherwise),
 * its current sell price and how far it has moved from base.
 */
public class CropMarketBoard {

    private static final String API = "https://discord.com/api/v10";
    /** Marker used to adopt the message again after a lost id file. */
    private static final String MARKER = "Crop market";
    private static final String UP = "\u25B2";     // ▲
    private static final String DOWN = "\u25BC";   // ▼
    private static final String FLAT = "\u25AC";   // ▬

    private final AllPurpose plugin;
    private HttpClient http = null;
    private BukkitTask task = null;
    private volatile String messageId = null;
    private long lastWarnMs = 0;
    /** Once Discord says no (perm/rate/budget) stop asking for the whole run. */
    private volatile boolean uploadsBlocked = false;
    private int uploadsThisRun = 0;

    public CropMarketBoard(AllPurpose plugin) {
        this.plugin = plugin;
    }

    // ---------- config ----------

    private boolean enabled() {
        try {
            if (!plugin.getConfig().getBoolean("crops-market.board", true)) return false;
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
            String c = plugin.getConfig().getString("crops-market.channel-id", "");
            if (c == null || c.trim().isEmpty() || !c.trim().matches("\\d{10,25}")) {
                c = plugin.getConfig().getString("discord-status.channel-id", "1559211061553401986");
            }
            if (c == null) return "";
            c = c.trim();
            return c.matches("\\d{10,25}") ? c : "";
        } catch (Exception e) {
            return "1559211061553401986";
        }
    }

    private int updateMinutes() {
        try {
            int m = plugin.getConfig().getInt("crops-market.board-minutes", 5);
            if (m < 1) m = 1;
            if (m > 120) m = 120;
            return m;
        } catch (Exception e) {
            return 5;
        }
    }

    private boolean emojiUpload() {
        try {
            return plugin.getConfig().getBoolean("crops-market.board-emoji", true);
        } catch (Exception e) {
            return true;
        }
    }

    private int emojiPerRun() {
        try {
            int n = plugin.getConfig().getInt("crops-market.board-emoji-per-run", 4);
            if (n < 1) n = 1;
            if (n > 10) n = 10;
            return n;
        } catch (Exception e) {
            return 4;
        }
    }

    private int emojiLimit() {
        try {
            int n = plugin.getConfig().getInt("crops-market.board-emoji-limit", 40);
            if (n < 0) n = 0;
            if (n > 100) n = 100;
            return n;
        } catch (Exception e) {
            return 40;
        }
    }

    // ---------- lifecycle ----------

    public synchronized void start() {
        stopTask();
        try { loadId(); } catch (Exception ignored) {}
        if (!enabled()) return;
        long period = (long) updateMinutes() * 20L * 60L;
        try {
            task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override public void run() { refresh(); }
            }, 1800L, period); // first shot ~90s after boot
        } catch (Exception e) {
            try { plugin.getLogger().warning("Crops market board failed to start: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    public synchronized void reload() {
        try { start(); } catch (Exception ignored) {}
    }

    public synchronized void shutdown() {
        stopTask();
    }

    private synchronized void stopTask() {
        try { if (task != null) task.cancel(); } catch (Exception ignored) {}
        task = null;
    }

    private File idFile() {
        return new File(plugin.getDataFolder(), "discord_cropmarket_id.txt");
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

    // ---------- refresh ----------

    private void refresh() {
        try {
            if (!enabled()) return;
            CropMarket market = plugin.getCropMarket();
            if (market == null) return;
            uploadsThisRun = 0;
            String payload = buildPayload(market);
            if (payload == null) return;
            if (messageId == null || messageId.isEmpty()) {
                String found = lookupExisting();
                if (found != null) saveId(found);
            }
            if (messageId != null && !messageId.isEmpty()) {
                if (patch(messageId, payload) == 404) {
                    messageId = null;
                    String found = lookupExisting();
                    if (found != null) { saveId(found); patch(found, payload); }
                    else { String id = post(payload); if (id != null) saveId(id); }
                }
            } else {
                String id = post(payload);
                if (id != null) saveId(id);
            }
        } catch (Exception ignored) {}
    }

    private String buildPayload(CropMarket market) {
        List<CropMarket.Crop> rows = market.boardOrder();
        if (rows == null || rows.isEmpty()) return null;
        long unix = Instant.now().getEpochSecond();
        int lines = 0;
        double multSum = 0.0;
        int multN = 0;
        StringBuilder d = new StringBuilder();
        d.append("**").append(MARKER).append("** \u00B7 sell prices follow what players actually sell\n");
        for (CropMarket.Crop c : rows) {
            String line = cropLine(c);
            if (line == null) continue;
            if (d.length() + line.length() > 3600) break; // stay well inside 4096
            d.append(line).append("\n");
            lines++;
            multSum += c.mult;
            multN++;
        }
        if (lines == 0) return null;
        int avgPct = multN > 0 ? (int) Math.round((multSum / multN - 1.0) * 100.0) : 0;
        String soldTxt = market.lastSold() > 0
                ? String.format(Locale.ROOT, "%,d", market.lastSold()) + " units sold last window"
                : "no sales recorded yet";
        d.append("\n").append(tag("wheat"))
                .append(" average ").append(avgPct >= 0 ? "+" : "").append(avgPct).append("% vs base \u00B7 ")
                .append(soldTxt).append("\n");
        d.append("Updated \u00B7 <t:").append(unix).append(":R>").append(" \u00B7 /cropsmarket info");

        int color = avgPct <= -20 ? 0xED4245 : (avgPct < -5 ? 0xFEE75C : 0x57F287);
        StringBuilder e = new StringBuilder();
        e.append("{\"description\":").append(json(d.toString()));
        e.append(",\"color\":").append(color);
        e.append("}");
        return "{\"embeds\":[" + e + "],\"allowed_mentions\":{\"parse\":[]}}";
    }

    /** One crop line: emoji, name, sell price, and how far it sits from base. */
    private String cropLine(CropMarket.Crop c) {
        try {
            if (c == null) return null;
            String tag = tag(c.key);
            double pct = (c.mult - 1.0) * 100.0;
            String arrow = pct > 1.0 ? UP : (pct < -1.0 ? DOWN : FLAT);
            return "- " + tag + " **" + c.label + "** " + Menus.plain(c.price)
                    + " " + arrow + String.format(Locale.ROOT, "%+d%%", (int) Math.round(pct));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- emoji (shared, lazy, capped) ----------

    /**
     * Guild emoji tag for one crop: the cached custom emoji when we have it,
     * otherwise a unicode glyph. New icons are uploaded a few per refresh so a
     * fresh guild fills in over a few runs without hammering the emoji API.
     */
    private String tag(String key) {
        String fallback = unicode(key);
        try {
            DiscordStatusBoard board = plugin.getStatusBoard();
            if (board == null) return fallback;
            String name = "crop_" + key;
            String have = board.emojiTag(name);
            if (have != null) return have;
            if (!emojiUpload() || uploadsBlocked) return fallback;
            if (uploadsThisRun >= emojiPerRun()) return fallback;
            if (board.emojiCount("crop_") >= emojiLimit()) { uploadsBlocked = true; return fallback; }
            byte[] png = CropIcons.get(key);
            if (png == null) return fallback;
            uploadsThisRun++;
            String id = board.uploadEmoji(name, png);
            if (id == null) {
                uploadsBlocked = true; // perm/rate/budget: stop asking this session
                return fallback;
            }
            String tag = board.emojiTag(name);
            return tag != null ? tag : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Unicode stand-in per crop (used until/unless the custom emoji lands). */
    private static String unicode(String key) {
        if (key == null) return "\uD83C\uDF31";
        switch (key) {
            case "wheat": case "hay_block": case "wheat_seeds": return "\uD83C\uDF3E"; // 🌾
            case "carrot": return "\uD83E\uDD55";                                      // 🥕
            case "potato": return "\uD83E\uDD54";                                      // 🥔
            case "beetroot": return "\uD83C\uDF60";                                    // 🍠
            case "melon": case "melon_seeds": return "\uD83C\uDF49";                   // 🍉
            case "pumpkin": case "pumpkin_seeds": return "\uD83C\uDF83";               // 🎃
            case "cactus": return "\uD83C\uDF35";                                      // 🌵
            case "bamboo": return "\uD83C\uDF8B";                                      // 🎋
            case "sugar_cane": return "\uD83C\uDF8D";                                  // 🎍
            case "sugar": return "\uD83C\uDF6C";                                       // 🍬
            case "kelp": case "dried_kelp": case "dried_kelp_block": return "\uD83C\uDF3F"; // 🌿
            case "nether_wart": return "\uD83D\uDD34";                                 // 🔴
            case "sweet_berries": return "\uD83E\uDED0";                               // 🫐
            case "glow_berries": return "\u2728";                                      // ✨
            case "honeycomb": case "bee_nest": case "beehive": return "\uD83D\uDC1D";  // 🐝
            case "chorus_fruit": return "\uD83C\uDF47";                                // 🍇
            case "chorus_flower": return "\uD83C\uDF38";                               // 🌸
            case "egg": return "\uD83E\uDD5A";                                         // 🥚
            case "cocoa_beans": return "\uD83C\uDF6B";                                 // 🍫
            case "brown_mushroom": case "red_mushroom":
            case "crimson_fungus": case "warped_fungus": return "\uD83C\uDF44";        // 🍄
            case "bone_meal": return "\uD83E\uDDB4";                                   // 🦴
            case "torchflower_seeds": return "\uD83C\uDF3C";                           // 🌼
            case "pitcher_pod": return "\uD83C\uDF3A";                                 // 🌺
            default: return "\uD83C\uDF31";                                            // 🌱
        }
    }

    // ---------- Discord REST ----------

    private HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        }
        return http;
    }

    private void warn(String msg) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastWarnMs < 300000L) return; // one warning per 5 min
            lastWarnMs = now;
            plugin.getLogger().warning(msg);
        } catch (Exception ignored) {}
    }

    private int patch(String id, String payload) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId() + "/messages/" + id))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (AllPurpose crop market, 1.0)")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            int code = res == null ? -1 : res.statusCode();
            if (code == 403) warn("Crops market board: 403 - bot cannot edit the message in channel " + channelId() + ".");
            else if (code == 429) warn("Crops market board: 429 rate-limited - backing off, retrying next tick.");
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    private String post(String payload) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId() + "/messages"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (AllPurpose crop market, 1.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            int code = res == null ? -1 : res.statusCode();
            if (code == 403) warn("Crops market board: 403 - bot cannot post in channel " + channelId() + ".");
            else if (code == 429) warn("Crops market board: 429 rate-limited - backing off, will retry next tick.");
            if (code != 200 && code != 201) return null;
            return firstId(res.body(), channelId());
        } catch (Exception e) {
            return null;
        }
    }

    /** Adopt the bot's previous market message (carries the marker). */
    private String lookupExisting() {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(API + "/channels/" + channelId() + "/messages?limit=50"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose crop market, 1.0)")
                    .GET()
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res == null || res.statusCode() != 200 || res.body() == null) return null;
            String body = res.body();
            if (!body.contains(MARKER)) return null;
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
                String slice = body.substring(from, to);
                if (slice.contains(MARKER) && slice.contains("\"author\"")) return cur;
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
        return b.append("\"").toString();
    }
}
