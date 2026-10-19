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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Live Discord server-status board (bot, zero new dependencies).
 *
 * Posts one embed to a fixed channel and EDITS it every N seconds — never
 * resends. Restarts reuse the stored message id (plugins/AllPurpose/
 * discord_status_id.txt); when the file is missing it scans recent channel
 * history for our hidden URL marker and adopts that message instead.
 *
 * Discord message text cannot embed arbitrary SVG files — only unicode emoji
 * or server custom emoji, and the emoji-create API only accepts PNG/JPEG/GIF
 * (SVG upload is rejected). So this board uses white 128px PNGs rasterized
 * from the Heroicons solid SVG set (MIT, heroicons.com), bundled in
 * BoardIcons and uploaded once as server emoji (hi_users, hi_bolt, hi_cube,
 * hi_smile, hi_sparkles, hi_online, hi_offline) via POST /guilds/{id}/emojis.
 * The previous Twemoji set (ap_*) is deleted after the switch. Until the
 * upload succeeds (or when disabled / the bot lacks Manage Emojis) plain
 * unicode glyphs render as fallback.
 *
 * Token lives in config (discord-status.bot-token) and /allpurpose reload
 * picks it up: reload() restarts the ticker so the next edit uses the new
 * token/channel immediately. REST-only (java.net.http), no JDA, no gateway.
 */
public class DiscordStatusBoard {

    /** Hidden marker: a stable board sentence. Restarts scan channel history
     * for it and adopt that message instead of posting a dup. Pure text
     * search — nothing visible is added to the board for it. */
    private static final String MARKER = "Add this server in Minecraft";
    private static final String API = "https://discord.com/api/v10";
    /** {emoji name} — Heroicons solid (MIT), white 128px PNGs bundled in BoardIcons. */
    private static final String[][] ICONS = {
        {"hi_users"},    // players header
        {"hi_bolt"},     // ping
        {"hi_cube"},     // version
        {"hi_smile"},    // playing-now / empty
        {"hi_sparkles"}, // join hint
        {"hi_online"},   // online dot (check-circle)
        {"hi_offline"},  // offline dot (x-circle)
    };
    /** Previous Twemoji set — deleted from the guild once the Heroicons are live. */
    private static final String[] OLD_ICONS = {
        "ap_pick", "ap_online", "ap_offline", "ap_users",
        "ap_bolt", "ap_box", "ap_smile", "ap_sparkle",
    };

    private final AllPurpose plugin;
    private HttpClient http = null;
    private BukkitTask task = null;
    private volatile String messageId = null;
    /** Custom-emoji ids by name; empty until the one-time upload finishes. */
    private final ConcurrentHashMap<String, String> emojiIds = new ConcurrentHashMap<>();
    private long lastWarnMs = 0;

    public DiscordStatusBoard(AllPurpose plugin) {
        this.plugin = plugin;
    }

    // ---------- config ----------

    private boolean enabled() {
        try {
            if (!plugin.getConfig().getBoolean("discord-status.enabled", true)) return false;
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
            String c = plugin.getConfig().getString("discord-status.channel-id", "1559211061553401986");
            if (c == null) return "";
            c = c.trim();
            if (!c.matches("\\d{10,25}")) return "";
            return c;
        } catch (Exception e) {
            return "1559211061553401986";
        }
    }

    private int updateSeconds() {
        try {
            int s = plugin.getConfig().getInt("discord-status.update-seconds", 15);
            if (s < 10) s = 10; // Discord: edits are cheap, but <10s is spammy
            if (s > 300) s = 300;
            return s;
        } catch (Exception e) {
            return 15;
        }
    }

    private String serverAddress() {
        try {
            String a = plugin.getConfig().getString("discord-status.server-address", "");
            if (a != null && !a.trim().isEmpty()) return a.trim();
            String h = plugin.getConfig().getString("transfer-host", "15.235.212.121");
            int p = plugin.getConfig().getInt("transfer-port", 10066);
            return (h == null || h.isEmpty() ? "15.235.212.121" : h.trim()) + ":" + p;
        } catch (Exception e) {
            return "15.235.212.121:10066";
        }
    }

    private String sessionLine() {
        try {
            String s = plugin.getConfig().getString("discord-status.session", "Secret SMP session 3");
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "Secret SMP session 3";
        }
    }

    /** Quote line: live server MOTD, config session as fallback. */
    private String motdLine() {
        try {
            String m = Bukkit.getMotd();
            if (m != null) {
                m = decodeEscapes(m);
                m = DiscordWebhook.clean(m).replaceAll("\\s+", " ").trim();
                if (!m.isEmpty()) {
                    if (m.length() > 150) m = m.substring(0, 150) + "…";
                    return m;
                }
            }
        } catch (Exception ignored) {}
        return sessionLine();
    }

    /**
     * server.properties does NOT interpret backslash-u escapes, so a MOTD
     * written with them shows up literally. Decode those (plus backslash-n)
     * before stripping color codes, otherwise the raw escape text lands on
     * the board.
     */
    private static String decodeEscapes(String s) {
        try {
            StringBuffer b = new StringBuffer();
            Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
            while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
            m.appendTail(b);
            return b.toString().replace("\\n", " ");
        } catch (Exception e) {
            return s;
        }
    }

    private boolean emojiUploadOn() {
        try {
            return plugin.getConfig().getBoolean("discord-status.emoji-upload", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Custom-emoji tag if uploaded, else the plain unicode glyph (same artwork). */
    private String em(String name, String unicodeFallback) {
        try {
            String id = emojiIds.get(name);
            if (id != null && id.matches("\\d{10,25}")) return "<:" + name + ":" + id + ">";
        } catch (Exception ignored) {}
        return unicodeFallback;
    }

    // ---------- lifecycle ----------

    private DiscordPresence presence = null;
    private int tickCount = 0;

    public synchronized void start() {
        try { stopTask(); } catch (Exception ignored) {}
        try { loadId(); } catch (Exception ignored) {}
        if (!enabled()) {
            plugin.getLogger().info("Discord status board OFF (set discord-status.bot-token).");
            return;
        }
        long period = (long) updateSeconds() * 20L;
        try {
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override public void run() { tick(); }
            }, 40L, period);
            plugin.getLogger().info("Discord status board ON in channel " + channelId()
                    + " every " + updateSeconds() + "s.");
            // Gateway presence so the bot shows ONLINE in the member list
            // (REST edits alone never mark it online). Zero intents.
            try {
                if (presence == null) presence = new DiscordPresence(plugin);
                else presence.stop();
                presence.start();
            } catch (Exception ignored) {}
            // One-time icon upload (async, non-blocking): ticks before it
            // finishes simply use unicode glyphs, later edits use <:name:id>.
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                    @Override public void run() { ensureEmojis(); }
                });
            } catch (Exception ignored) {}
        } catch (Exception e) {
            plugin.getLogger().warning("Discord status board failed to start: " + e.getMessage());
        }
    }

    /** /allpurpose reload path: re-reads token/channel/interval, restarts ticker. */
    public synchronized void reload() {
        try { start(); } catch (Exception ignored) {}
    }

    public synchronized void shutdown() {
        try { stopTask(); } catch (Exception ignored) {}
        try { if (presence != null) presence.stop(); } catch (Exception ignored) {}
    }

    /** Best-effort synchronous OFFLINE flip so the board never lies during downtime. */
    public void markOfflineSync() {
        try {
            if (messageId == null || messageId.isEmpty()) return;
            if (token().isEmpty()) return;
            String payload = buildPayload(snapshot(), false);
            patchSync(messageId, payload);
        } catch (Exception ignored) {}
    }

    private synchronized void stopTask() {
        try { if (task != null) task.cancel(); } catch (Exception ignored) {}
        task = null;
    }

    // ---------- persistence ----------

    private File idFile() {
        return new File(plugin.getDataFolder(), "discord_status_id.txt");
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

    private void clearId() {
        messageId = null;
        try { Files.deleteIfExists(idFile().toPath()); } catch (Exception ignored) {}
    }

    // ---------- tick ----------

    private void tick() {
        try {
            if (!enabled()) return;
            try {
                // Refresh the Watching text every ~5 min (every 20th 15s tick).
                if (++tickCount >= 20) {
                    tickCount = 0;
                    if (presence != null) presence.updateActivity();
                }
            } catch (Exception ignored) {}
            Snapshot snap = snapshot();
            try { syncHeadEmojis(snap); } catch (Exception ignored) {}
            String payload = buildPayload(snap, true);
            if (messageId == null || messageId.isEmpty()) {
                String found = lookupExisting();
                if (found != null) saveId(found);
            }
            if (messageId != null && !messageId.isEmpty()) {
                int code = patch(messageId, payload);
                if (code == 404) { // deleted by a mod: adopt-or-recreate once
                    clearId();
                    String found = lookupExisting();
                    if (found != null) {
                        saveId(found);
                        patch(found, payload);
                    } else {
                        String id = post(payload);
                        if (id != null) saveId(id);
                    }
                }
            } else {
                String id = post(payload);
                if (id != null) saveId(id);
            }
        } catch (Exception ignored) {}
    }

    // ---------- game snapshot (read-only) ----------

    private static class Snapshot {
        int online;
        int max;
        List<String> names = new ArrayList<>();
        List<java.util.UUID> uuids = new ArrayList<>();
        int avgPing = -1;
        double tps = -1;
        String paper = "";
    }

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        try { s.max = Bukkit.getMaxPlayers(); } catch (Exception e) { s.max = 20; }
        if (s.max <= 0) s.max = 20;
        try {
            long pingSum = 0;
            int pingN = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) continue;
                String n = p.getName();
                if (n == null || n.isEmpty()) continue;
                // Skip fighter bots when the manager exists (ranks-only boot: null, keep all).
                try {
                    if (BotNames.isBot(plugin, n)) continue;
                } catch (Exception ignored) {}
                s.names.add(n);
                try { s.uuids.add(p.getUniqueId()); } catch (Exception ignored) { s.uuids.add(null); }
                try { pingSum += Math.max(0, p.getPing()); pingN++; } catch (Exception ignored) {}
            }
            s.online = s.names.size();
            if (pingN > 0) s.avgPing = (int) (pingSum / pingN);
            // Keep uuids aligned with the sorted names (names are unique per server).
            try {
                java.util.Map<String, java.util.UUID> idByName = new java.util.HashMap<>();
                for (int i = 0; i < s.names.size(); i++) {
                    idByName.put(s.names.get(i), i < s.uuids.size() ? s.uuids.get(i) : null);
                }
                s.names.sort(String.CASE_INSENSITIVE_ORDER);
                List<java.util.UUID> reordered = new ArrayList<>(s.names.size());
                for (String key : s.names) reordered.add(idByName.get(key));
                s.uuids = reordered;
            } catch (Exception ignored) {
                try { s.names.sort(String.CASE_INSENSITIVE_ORDER); } catch (Exception ignored2) {}
            }
        } catch (Exception e) {
            s.online = 0;
        }
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0) s.tps = tps[0];
        } catch (Exception ignored) {}
        try {
            s.paper = "Paper " + Bukkit.getMinecraftVersion();
        } catch (Exception e) {
            s.paper = "Paper";
        }
        return s;
    }

    // ---------- embed payload (matches requested board) ----------

    private String buildPayload(Snapshot s, boolean online) {
        String addr = serverAddress();
        String quote = motdLine();
        long unix = Instant.now().getEpochSecond();

        String statusLine = online
                ? em("hi_online", "\uD83D\uDFE2") + " ONLINE \u00B7 `" + addr + "`"
                : em("hi_offline", "\uD83D\uDD34") + " OFFLINE \u00B7 `" + addr + "`";
        StringBuilder d = new StringBuilder();
        d.append(statusLine).append("\n");
        if (!quote.isEmpty()) d.append("> ").append(quote).append("\n");
        d.append("\n").append(em("hi_users", "\uD83D\uDC65")).append(" Players online\n");
        d.append("**").append(s.online).append(" / ").append(s.max).append("** players\n");
        d.append(bar(s.online, s.max)).append("\n");
        String pingTxt = s.avgPing >= 0 ? s.avgPing + " ms" : "\u2014";
        d.append(em("hi_bolt", "\u26A1")).append(" ").append(pingTxt).append(" \u00B7 ");
        d.append(em("hi_cube", "\uD83D\uDCE6")).append(" ").append(s.paper);
        if (s.tps > 0) d.append(" \u00B7 ").append(String.format("%.1f TPS", Math.min(20.0, s.tps)));
        d.append("\n");
        if (s.names.isEmpty()) {
            d.append(em("hi_smile", "\uD83D\uDE42")).append(" Nobody online \u2014 be the first!\n");
        } else {
            d.append(em("hi_smile", "\uD83D\uDE42")).append(" Playing right now:\n");
            int show = Math.min(s.names.size(), 25);
            int used = 0;
            for (int i = 0; i < show; i++) {
                String n = s.names.get(i);
                java.util.UUID id = (s.uuids != null && i < s.uuids.size()) ? s.uuids.get(i) : null;
                String line = "- " + headTag(n, id) + " `" + n + "`\n";
                if (used + line.length() > 1000) {
                    d.append("+ ").append(s.names.size() - i).append(" more\u2026\n");
                    break;
                }
                d.append(line);
                used += line.length();
            }
        }
        d.append(em("hi_sparkles", "\u2728")).append(" Add this server in Minecraft \u2192 Multiplayer \u2192 Add Server\n");
        d.append("Updated \u00B7 <t:").append(unix).append(":F> (<t:").append(unix).append(":R>)");

        int color = online ? 0x57F287 : 0xED4245;
        String desc = d.toString();
        // No title, no footer, no embed timestamp (owner request: Updated line
        // carries the time). Description only.
        StringBuilder e = new StringBuilder();
        e.append("{\"description\":").append(json(desc));
        e.append(",\"color\":").append(color);
        e.append("}");
        return "{\"embeds\":[" + e + "],\"allowed_mentions\":{\"parse\":[]}}";
    }

    private static String bar(int online, int max) {
        int segs = 12;
        int filled = 0;
        try {
            if (max > 0) filled = (int) Math.round((double) online / (double) max * segs);
        } catch (Exception ignored) {}
        if (filled < 0) filled = 0;
        if (filled > segs) filled = segs;
        if (online > 0 && filled == 0) filled = 1;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < filled; i++) b.append("\uD83D\uDFE9");
        for (int i = filled; i < segs; i++) b.append("\u2B1B");
        return b.toString();
    }

    // ---------- per-player head emoji (SkinsRestorer, reflection, zero hard dep) ----------
    //
    // Each "Playing right now" bullet is prefixed with the player's ACTUAL head:
    // the skin texture hash is read from SkinsRestorer's API (when installed),
    // rendered via mc-heads (https://mc-heads.net/avatar/{hash}/64.png), and
    // uploaded ONCE as a guild emoji (head_<hash20>) — later ticks reuse the
    // cached <:head_…:id>. Players with no SkinsRestorer skin (and heads still
    // uploading) get a Steve head (head_steve, uploaded once the same way).
    // At most ONE head upload per tick to respect Discord's emoji-create rate
    // limits; the cap below guards the 50-slot guild limit on unboosted servers.
    private static final String HEAD_FALLBACK = "\uD83D\uDC64"; // bust in silhouette
    // Guild emoji slots are scarce (50 unboosted, shared with hi_* icons), so
    // heads are LRU-capped: when full, the longest-unseen head is DELETED to
    // make room instead of growing forever.
    private static final int MAX_HEAD_EMOJI = 25;
    /** SkinsRestorer texture-hash -> guild emoji id (persisted). */
    private final ConcurrentHashMap<String, String> headEmojiIds = new ConcurrentHashMap<>();
    /** Player name -> SkinsRestorer texture-hash (memory; re-resolved each boot). */
    private final ConcurrentHashMap<String, String> nameToHash = new ConcurrentHashMap<>();
    /** Texture-hash -> last tick it was seen online (for LRU eviction). */
    private final ConcurrentHashMap<String, Long> headSeen = new ConcurrentHashMap<>();
    private volatile boolean headsLoaded = false;

    /** Tag for one bullet: custom head emoji when cached, Steve head when the
     * player has no SkinsRestorer skin, generic bust only as a last resort. */
    private String headTag(String name, java.util.UUID uuid) {
        try {
            String hash = nameToHash.get(name);
            if (hash != null) {
                String id = headEmojiIds.get(hash);
                if (id != null && id.matches("\\d{10,25}")) {
                    String en = headEmojiName(hash);
                    if (en != null) return "<:" + en + ":" + id + ">";
                }
            }
            String sid = headEmojiIds.get("STEVE");
            if (sid != null && sid.matches("\\d{10,25}")) return "<:head_steve:" + sid + ">";
        } catch (Exception ignored) {}
        return HEAD_FALLBACK;
    }

    /**
     * Public for the chat log: the head emoji tag for one player, reusing this
     * board's upload cache (never triggers a second upload path). Returns the
     * player's head, the Steve head, or the generic bust while the real head is
     * still being uploaded — so callers always get something head-shaped.
     * Null only when nothing at all is known.
     *
     * Called from the async chat thread, so it only READS: it loads the on-disk
     * cache on first use and resolves the speaker's own skin hash (local
     * SkinsRestorer lookup, no HTTP). Uploading stays here on the board's tick.
     */
    public String headEmojiTag(String name, java.util.UUID uuid) {
        try {
            if (!headsLoaded) { loadHeadCache(); loadHeadSeen(); headsLoaded = true; }
            if (name != null && !nameToHash.containsKey(name)) {
                String h = skinsRestorerTextureHash(uuid, name);
                if (h != null && !h.isEmpty()) nameToHash.put(name, h);
            }
            return headTag(name, uuid);
        } catch (Exception e) {
            return null;
        }
    }

    private static String headEmojiName(String hash) {
        try {
            String h = hash.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
            if (h.length() < 4) return null;
            if (h.length() > 20) h = h.substring(0, 20);
            return "head_" + h; // 2-32 chars, [a-z0-9_]: valid emoji name
        } catch (Exception e) {
            return null;
        }
    }

    /** Resolve hashes for everyone online; upload at most ONE missing head. */
    private void syncHeadEmojis(Snapshot s) {
        try {
            if (headsLoaded == false) { loadHeadCache(); loadHeadSeen(); headsLoaded = true; }
            if (s.names.isEmpty()) return;
            // 1) Resolve texture hashes (SkinsRestorer local storage: fast, no HTTP).
            for (int i = 0; i < s.names.size(); i++) {
                String n = s.names.get(i);
                if (n == null || nameToHash.containsKey(n)) continue;
                java.util.UUID id = (s.uuids != null && i < s.uuids.size()) ? s.uuids.get(i) : null;
                try {
                    String h = skinsRestorerTextureHash(id, n);
                    if (h != null && !h.isEmpty()) nameToHash.put(n, h);
                } catch (Exception ignored) {}
            }
            // Stamp recency for everyone online (drives LRU eviction below).
            try {
                long now = System.currentTimeMillis();
                for (String n : s.names) {
                    String h = nameToHash.get(n);
                    if (h != null) headSeen.put(h, now);
                }
                saveHeadSeen();
            } catch (Exception ignored) {}
            // 2) Expire heads of players gone longer than head-expiry-minutes.
            // One delete per tick (rate-limit friendly). Unknown recency
            // (pre-existing entries) is stamped now, never bulk-deleted.
            try {
                long now = System.currentTimeMillis();
                long ttl = headExpiryMinutes() * 60L * 1000L;
                boolean touched = false;
                for (String k : new java.util.ArrayList<>(headEmojiIds.keySet())) {
                    if ("STEVE".equals(k)) continue;
                    if (!headSeen.containsKey(k)) { headSeen.put(k, now); touched = true; }
                }
                String dead = null;
                long deadAge = 0;
                for (String k : headEmojiIds.keySet()) {
                    if ("STEVE".equals(k)) continue;
                    Long seen = headSeen.get(k);
                    long age = seen == null ? 0L : now - seen;
                    if (age >= ttl && (dead == null || age > deadAge)) { dead = k; deadAge = age; }
                }
                if (touched) saveHeadSeen();
                if (dead != null) {
                    String gid = resolveGuildId();
                    if (gid == null) return;
                    String eid = headEmojiIds.get(dead);
                    try { if (eid != null) deleteEmoji(gid, eid); } catch (Exception ignored) {}
                    headEmojiIds.remove(dead);
                    headSeen.remove(dead);
                    saveHeadCache();
                    saveHeadSeen();
                    try { plugin.getLogger().info("Discord status board: expired head emoji " + headEmojiName(dead) + " (unseen " + (deadAge / 60000) + "m)."); } catch (Exception ignored) {}
                    return; // one Discord mutation per tick
                }
            } catch (Exception ignored) {}
            // 3) Ensure the Steve fallback head (players with no SR skin stored).
            if (!headEmojiIds.containsKey("STEVE")) {
                if (headEmojiIds.size() >= MAX_HEAD_EMOJI) return;
                String gid = resolveGuildId();
                if (gid == null) return;
                try {
                    String existing = listEmojiId(gid, "head_steve");
                    if (existing != null) {
                        headEmojiIds.put("STEVE", existing);
                        saveHeadCache();
                    } else {
                        byte[] png = steveHeadPng();
                        if (!isPng(png)) return; // transient net failure: retry next tick
                        String created = createEmoji(gid, "head_steve", png);
                        if (created != null) {
                            headEmojiIds.put("STEVE", created);
                            saveHeadCache();
                            plugin.getLogger().info("Discord status board: Steve fallback head cached.");
                        }
                    }
                } catch (Exception ignored) {}
                return; // one upload attempt per tick, always
            }
            // 4) Upload a single missing head (rate-limit friendly).
            // LRU backstop: at cap, delete the longest-unseen head to free its slot.
            String guildForEvict = null;
            try {
                int heads = 0;
                for (String k : headEmojiIds.keySet()) if (!"STEVE".equals(k)) heads++;
                if (heads >= MAX_HEAD_EMOJI) {
                    String oldest = null;
                    long oldestSeen = Long.MAX_VALUE;
                    for (String k : headEmojiIds.keySet()) {
                        if ("STEVE".equals(k)) continue;
                        Long seen = headSeen.get(k);
                        long t = seen == null ? 0L : seen;
                        if (t < oldestSeen) { oldestSeen = t; oldest = k; }
                    }
                    if (oldest == null) return;
                    guildForEvict = resolveGuildId();
                    if (guildForEvict == null) return;
                    String eid = headEmojiIds.get(oldest);
                    try { if (eid != null) deleteEmoji(guildForEvict, eid); } catch (Exception ignored) {}
                    headEmojiIds.remove(oldest);
                    headSeen.remove(oldest);
                    saveHeadCache();
                    saveHeadSeen();
                    try { plugin.getLogger().info("Discord status board: evicted stale head emoji " + headEmojiName(oldest)); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) { return; }
            String guildId = null;
            for (int i = 0; i < s.names.size(); i++) {
                String n = s.names.get(i);
                String h = nameToHash.get(n);
                if (h == null || headEmojiIds.containsKey(h)) continue;
                String en = headEmojiName(h);
                if (en == null) continue;
                if (guildId == null) {
                    guildId = resolveGuildId();
                    if (guildId == null) return;
                }
                try {
                    String existing = listEmojiId(guildId, en);
                    if (existing != null) { headEmojiIds.put(h, existing); saveHeadCache(); return; }
                    java.util.UUID id = (s.uuids != null && i < s.uuids.size()) ? s.uuids.get(i) : null;
                    byte[] png = downloadHeadPng(h, id);
                    if (png == null) return; // transient net failure: retry next tick
                    String created = createEmoji(guildId, en, png);
                    if (created != null) {
                        headEmojiIds.put(h, created);
                        saveHeadCache();
                        plugin.getLogger().info("Discord status board: head emoji " + en + " cached.");
                    }
                } catch (Exception ignored) {}
                return; // one upload attempt per tick, always
            }
        } catch (Exception ignored) {}
    }

    /**
     * SkinsRestorer texture-hash for a player via reflection (no compile dep,
     * works across SR v14/v15: tries getSkinForPlayer(UUID,String), then
     * getSkinForPlayer(UUID), then getSkinOfPlayer(UUID), unwrapping Optional).
     * Returns null when SkinsRestorer is absent or has no skin stored.
     */
    private static String skinsRestorerTextureHash(java.util.UUID uuid, String name) {
        try {
            Class<?> provider = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider");
            Object api = provider.getMethod("get").invoke(null);
            if (api == null) return null;
            Object storage;
            try {
                storage = api.getClass().getMethod("getPlayerStorage").invoke(api);
            } catch (NoSuchMethodException e) {
                return null;
            }
            if (storage == null) return null;
            Object prop = null;
            if (uuid != null) {
                try {
                    java.lang.reflect.Method m = storage.getClass()
                            .getMethod("getSkinForPlayer", java.util.UUID.class, String.class);
                    prop = unwrapOptional(m.invoke(storage, uuid, name));
                } catch (NoSuchMethodException ignored) {}
                if (prop == null) {
                    try {
                        java.lang.reflect.Method m = storage.getClass()
                                .getMethod("getSkinForPlayer", java.util.UUID.class);
                        prop = unwrapOptional(m.invoke(storage, uuid));
                    } catch (NoSuchMethodException ignored) {}
                }
                if (prop == null) {
                    try {
                        java.lang.reflect.Method m = storage.getClass()
                                .getMethod("getSkinOfPlayer", java.util.UUID.class);
                        prop = unwrapOptional(m.invoke(storage, uuid));
                    } catch (NoSuchMethodException ignored) {}
                }
            }
            if (prop == null) return null;
            String b64;
            try {
                b64 = (String) prop.getClass().getMethod("getValue").invoke(prop);
            } catch (NoSuchMethodException e) {
                return null;
            }
            if (b64 == null || b64.isEmpty()) return null;
            Class<?> pu = Class.forName("net.skinsrestorer.api.PropertyUtils");
            try {
                java.lang.reflect.Method mh = pu.getMethod("getSkinTextureHash", String.class);
                Object h = mh.invoke(null, b64);
                if (h instanceof String && !((String) h).isEmpty()) return (String) h;
            } catch (NoSuchMethodException ignored) {}
            try {
                Class<?> spc = Class.forName("net.skinsrestorer.api.property.SkinProperty");
                java.lang.reflect.Method mh2 = pu.getMethod("getSkinTextureHash", spc);
                Object h2 = mh2.invoke(null, prop);
                if (h2 instanceof String && !((String) h2).isEmpty()) return (String) h2;
            } catch (NoSuchMethodException | ClassNotFoundException ignored) {}
            return null;
        } catch (ClassNotFoundException e) {
            return null; // SkinsRestorer not installed: caller uses generic head
        } catch (Exception e) {
            return null;
        }
    }

    private static Object unwrapOptional(Object r) {
        try {
            if (r instanceof java.util.Optional) {
                java.util.Optional<?> o = (java.util.Optional<?>) r;
                return o.orElse(null);
            }
        } catch (Exception ignored) {}
        return r;
    }

    /**
     * Head PNG for a SkinsRestorer texture-hash (mc-heads renders the true SR
     * skin, including custom /SkinSet skins Mojang CDNs never see). Falls back
     * to the Mojang helm by UUID when the hash render is unreachable.
     */
    private byte[] downloadHeadPng(String hash, java.util.UUID uuid) {
        try {
            byte[] png = httpGetBytes("https://mc-heads.net/avatar/" + hash + "/64.png");
            if (isPng(png)) return png;
        } catch (Exception ignored) {}
        try {
            if (uuid != null) {
                String dashless = uuid.toString().replace("-", "");
                byte[] png = httpGetBytes("https://minotar.net/helm/" + dashless + "/64.png");
                if (isPng(png)) return png;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private byte[] httpGetBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "AllPurpose status board (player heads, 1.0)")
                    .GET()
                    .build();
            HttpResponse<byte[]> res = client().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res == null || res.statusCode() != 200 || res.body() == null) return null;
            if (res.body().length > 256 * 1024) return null; // Discord emoji limit
            return res.body();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPng(byte[] b) {
        if (b == null || b.length < 100) return false;
        return (b[0] == (byte) 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47);
    }

    /** Classic Steve head (MHF_Steve is Mojang's official Steve-textured account). */
    private byte[] steveHeadPng() {
        try {
            byte[] png = httpGetBytes("https://mc-heads.net/avatar/MHF_Steve/64.png");
            if (isPng(png)) return png;
        } catch (Exception ignored) {}
        try {
            byte[] png = httpGetBytes("https://minotar.net/helm/MHF_Steve/64.png");
            if (isPng(png)) return png;
        } catch (Exception ignored) {}
        return null;
    }

    private File headCacheFile() {
        return new File(plugin.getDataFolder(), "discord_status_heads.json");
    }

    private void loadHeadCache() {
        try {
            File f = headCacheFile();
            if (!f.exists()) return;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"([0-9a-fA-F]{4,64}|STEVE)\"\\s*:\\s*\"(\\d{10,25})\"").matcher(s);
            while (m.find()) headEmojiIds.put(m.group(1), m.group(2));
        } catch (Exception ignored) {}
    }

    private void saveHeadCache() {
        try {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, String> e : headEmojiIds.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (!e.getValue().matches("\\d{10,25}")) continue;
                if (!first) b.append(",");
                first = false;
                b.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
            }
            b.append("}");
            plugin.getDataFolder().mkdirs();
            Files.write(headCacheFile().toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Minutes after a player is last seen online before their head emoji is
     * deleted to free its guild slot (STEVE fallback is never deleted). */
    private long headExpiryMinutes() {
        try {
            long m = plugin.getConfig().getLong("discord-status.head-expiry-minutes", 10);
            if (m < 2) m = 2;
            if (m > 10080) m = 10080;
            return m;
        } catch (Exception e) {
            return 10;
        }
    }

    private File headSeenFile() {
        return new File(plugin.getDataFolder(), "discord_status_headseen.json");
    }

    private void loadHeadSeen() {
        try {
            File f = headSeenFile();
            if (!f.exists()) return;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"([0-9a-fA-F]{4,64})\"\\s*:\\s*(\\d{1,20})").matcher(s);
            while (m.find()) {
                try { headSeen.put(m.group(1), Long.parseLong(m.group(2))); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void saveHeadSeen() {
        try {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, Long> e : headSeen.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (!first) b.append(",");
                first = false;
                b.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            }
            b.append("}");
            plugin.getDataFolder().mkdirs();
            Files.write(headSeenFile().toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    // ---------- server-emoji upload (SVG -> PNG -> guild emoji, once) ----------

    private File emojiFile() {
        return new File(plugin.getDataFolder(), "discord_status_emoji.json");
    }

    private void loadEmojiCache() {
        try {
            File f = emojiFile();
            if (!f.exists()) return;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"((?:hi|ap)_[a-z]+)\"\\s*:\\s*\"(\\d{10,25})\"").matcher(s);
            while (m.find()) emojiIds.put(m.group(1), m.group(2));
        } catch (Exception ignored) {}
    }

    private void saveEmojiCache() {
        try {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (String[] icon : ICONS) {
                String id = emojiIds.get(icon[0]);
                if (id == null || !id.matches("\\d{10,25}")) continue;
                if (!first) b.append(",");
                first = false;
                b.append("\"").append(icon[0]).append("\":\"").append(id).append("\"");
            }
            b.append("}");
            plugin.getDataFolder().mkdirs();
            Files.write(emojiFile().toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Resolve the guild owning our channel (null for DMs / errors). */
    private String resolveGuildId() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose status board, 1.0)")
                    .GET()
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res == null || res.statusCode() != 200 || res.body() == null) return null;
            Matcher m = Pattern.compile("\"guild_id\"\\s*:\\s*\"(\\d{10,25})\"").matcher(res.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Existing guild-emoji id for a name, or null. Handles either key order. */
    private String listEmojiId(String guildId, String name) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/guilds/" + guildId + "/emojis"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose status board, 1.0)")
                    .GET()
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res == null || res.statusCode() != 200 || res.body() == null) return null;
            String body = res.body();
            String q = Pattern.quote(name);
            Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"" + q + "\"[^}]*?\"id\"\\s*:\\s*\"(\\d{10,25})\""
                    + "|\"id\"\\s*:\\s*\"(\\d{10,25})\"[^}]*?\"name\"\\s*:\\s*\"" + q + "\"").matcher(body);
            if (!m.find()) return null;
            return m.group(1) != null ? m.group(1) : m.group(2);
        } catch (Exception e) {
            return null;
        }
    }

    /** Delete one guild emoji by id. Best-effort. */
    private void deleteEmoji(String guildId, String emojiId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/guilds/" + guildId + "/emojis/" + emojiId))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose status board, 1.0)")
                    .DELETE()
                    .build();
            client().send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }

    /** Upload one PNG as a guild emoji. Returns the new id or null. */
    private String createEmoji(String guildId, String name, byte[] png) {
        try {
            String payload = "{\"name\":" + json(name)
                    + ",\"image\":\"data:image/png;base64," + Base64.getEncoder().encodeToString(png) + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/guilds/" + guildId + "/emojis"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (AllPurpose status board, 1.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            int code = res == null ? -1 : res.statusCode();
            if (code == 200 || code == 201) return firstId(res.body(), guildId);
            if (code == 403) warn("Discord status board: 403 on emoji upload — bot needs Manage Emojis permission; using unicode icons.");
            else if (code == 429) warn("Discord status board: 429 on emoji upload — will use unicode icons until next restart/reload.");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One-time setup: adopt already-uploaded Heroicons, upload the missing
     * ones from the bundled PNGs, then delete the superseded Twemoji set.
     * Never re-uploads on restart (cache + guild listing checked first).
     * Any failure just leaves unicode fallbacks in place.
     */
    private void ensureEmojis() {
        try {
            if (!emojiUploadOn() || !enabled()) return;
            loadEmojiCache();
            boolean complete = true;
            for (String[] icon : ICONS) {
                String id = emojiIds.get(icon[0]);
                if (id != null && id.matches("\\d{10,25}")) continue;
                complete = false;
                break;
            }
            if (complete) {
                cleanupOldIcons(null);
                return;
            }
            String guildId = resolveGuildId();
            if (guildId == null) return; // DM channel or no access: unicode path
            for (String[] icon : ICONS) {
                try {
                    String have = emojiIds.get(icon[0]);
                    if (have != null && have.matches("\\d{10,25}")) continue;
                    String existing = listEmojiId(guildId, icon[0]);
                    if (existing != null) {
                        emojiIds.put(icon[0], existing);
                        continue;
                    }
                    byte[] png = BoardIcons.get(icon[0]);
                    if (png == null || png.length < 100) continue;
                    String created = createEmoji(guildId, icon[0], png);
                    if (created == null) return; // perm/rate problem: stop, warn() already throttled
                    emojiIds.put(icon[0], created);
                    try { Thread.sleep(1000); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }
            saveEmojiCache();
            cleanupOldIcons(guildId);
            plugin.getLogger().info("Discord status board icons: " + emojiIds.size() + "/" + ICONS.length + " custom emoji active.");
        } catch (Exception ignored) {}
    }

    /** Delete the old Twemoji ap_* guild emoji once the Heroicons are live. */
    private void cleanupOldIcons(String knownGuild) {
        try {
            boolean allNew = true;
            for (String[] icon : ICONS) {
                String id = emojiIds.get(icon[0]);
                if (id == null || !id.matches("\\d{10,25}")) { allNew = false; break; }
            }
            if (!allNew) return;
            String guildId = knownGuild != null ? knownGuild : resolveGuildId();
            if (guildId == null) return;
            for (String old : OLD_ICONS) {
                try {
                    String id = listEmojiId(guildId, old);
                    if (id != null) {
                        deleteEmoji(guildId, id);
                        emojiIds.remove(old);
                        try { Thread.sleep(500); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
            saveEmojiCache();
        } catch (Exception ignored) {}
    }

    // ---------- Discord REST ----------

    private HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        }
        return http;
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 5 * 60 * 1000) return; // one log per 5 min max
        lastWarnMs = now;
        try { plugin.getLogger().warning(msg); } catch (Exception ignored) {}
    }

    /** PATCH existing board. Returns HTTP status, -1 on transport failure. */
    private int patch(String id, String payload) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(API + "/channels/" + channelId() + "/messages/" + id))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (AllPurpose status board, 1.0)")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            int code = res == null ? -1 : res.statusCode();
            if (code == 401) warn("Discord status board: 401 — bot token invalid. Fix discord-status.bot-token then /allpurpose reload.");
            else if (code == 403) warn("Discord status board: 403 — bot cannot edit in channel " + channelId() + " (needs View + Send + Embed Links).");
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    private void patchSync(String id, String payload) {
        try { patch(id, payload); } catch (Exception ignored) {}
    }

    /** POST new board. Returns new message id or null. */
    private String post(String payload) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId() + "/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (AllPurpose status board, 1.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            int code = res == null ? -1 : res.statusCode();
            if (code == 200 || code == 201) return firstId(res.body(), channelId());
            if (code == 401) warn("Discord status board: 401 — bot token invalid. Fix discord-status.bot-token then /allpurpose reload.");
            else if (code == 403) warn("Discord status board: 403 — bot cannot post in channel " + channelId() + " (needs View + Send + Embed Links).");
            else if (code == 404) warn("Discord status board: 404 — channel " + channelId() + " not found / bot not in server.");
            else if (code == 429) warn("Discord status board: 429 rate-limited — backing off, will retry next tick.");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Scan recent channel history for our marker and return that message's id,
     * so a restart (or a lost id file) adopts the board instead of posting a dup.
     */
    private String lookupExisting() {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(API + "/channels/" + channelId() + "/messages?limit=50"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose status board, 1.0)")
                    .GET()
                    .build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res == null || res.statusCode() != 200) return null;
            String body = res.body();
            if (body == null || !body.contains(MARKER)) return null;
            // Each message object carries its "id" before its embeds: for every
            // id match, the marker inside the window up to the NEXT id belongs
            // to that message. Skip the channel id itself.
            Pattern p = Pattern.compile("\"id\"\\s*:\\s*\"(\\d{10,25})\"");
            Matcher m = p.matcher(body);
            List<Integer> starts = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            while (m.find()) {
                starts.add(m.start());
                ids.add(m.group(1));
            }
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                if (id.equals(channelId())) continue;
                int from = m == null ? 0 : starts.get(i);
                int to = (i + 1 < starts.size()) ? starts.get(i + 1) : Math.min(body.length(), from + 12000);
                if (to <= from) continue;
                String window = body.substring(from, Math.min(body.length(), Math.max(from, to)));
                if (window.contains(MARKER)) return id;
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
