package com.hush.allpurpose;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Staleness guard for the spare-laptop spectator live-cam (separate from
 * SpawnSnapshot). The cam script on spare edits ONE Discord message in place;
 * Discord refreshes its edited_timestamp on every edit. If this whole machine
 * dies (no script running to self-delete), this ticker notices the message
 * went stale and DELETES it so the board never shows a dead render. When the
 * feed resumes, the cam script posts a fresh message.
 *
 * Marker: SPAWN_CAM_LIVE in the message content, authored by our own bot.
 */
public class SpectatorCam {

    private static final String API = "https://discord.com/api/v10";
    private static final String MARKER = "SPAWN_CAM_LIVE";

    private final AllPurpose plugin;
    private HttpClient http = null;
    private BukkitTask task = null;

    public SpectatorCam(AllPurpose plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        try {
            if (!plugin.getConfig().getBoolean("discord-spectator-cam.enabled", true)) return false;
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
            String c = plugin.getConfig().getString("discord-spectator-cam.channel-id", "");
            if (c == null || c.trim().isEmpty()) {
                c = plugin.getConfig().getString("discord-status.channel-id", "1559211061553401986");
            }
            c = c.trim();
            if (!c.matches("\\d{10,25}")) return "";
            return c;
        } catch (Exception e) {
            return "1559211061553401986";
        }
    }

    private long staleMinutes() {
        try {
            long m = plugin.getConfig().getLong("discord-spectator-cam.stale-minutes", 20);
            if (m < 5) m = 5;
            if (m > 180) m = 180;
            return m;
        } catch (Exception e) {
            return 20;
        }
    }

    private long checkMinutes() {
        try {
            long m = plugin.getConfig().getLong("discord-spectator-cam.check-minutes", 5);
            if (m < 2) m = 2;
            if (m > 60) m = 60;
            return m;
        } catch (Exception e) {
            return 5;
        }
    }

    public synchronized void start() {
        try { stopTask(); } catch (Exception ignored) {}
        if (!enabled()) return;
        long period = checkMinutes() * 60L * 20L;
        try {
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override public void run() { tick(); }
            }, 1200L, period);
            plugin.getLogger().info("SpectatorCam stale-guard ON in channel " + channelId());
        } catch (Exception e) {
            plugin.getLogger().warning("SpectatorCam failed to start: " + e.getMessage());
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

    private void tick() {
        try {
            if (!enabled()) return;
            String body = get("/channels/" + channelId() + "/messages?limit=25");
            if (body == null) return;
            for (String msg : jsonObjects(body)) {
                if (!msg.contains(MARKER)) continue;
                if (!msg.contains("\"bot\":true") && !msg.contains("\"bot\": true")) continue;
                String id = topField(msg, "id");
                if (id == null || !id.matches("\\d{10,25}")) continue;
                String edited = topField(msg, "edited_timestamp");
                if (edited == null || "null".equals(edited)) edited = topField(msg, "timestamp");
                if (edited == null) continue;
                long ageMin;
                try {
                    ageMin = (Instant.now().getEpochSecond() - Instant.parse(edited).getEpochSecond()) / 60;
                } catch (Exception ex) { continue; }
                if (ageMin >= staleMinutes()) {
                    del("/channels/" + channelId() + "/messages/" + id);
                    try { plugin.getLogger().info("SpectatorCam: deleted stale live-cam message (edited " + ageMin + "m ago)."); } catch (Exception ignored) {}
                }
                return; // only one cam message exists
            }
        } catch (Exception ignored) {}
    }

    /** Split a JSON array body into its top-level objects (string/escape aware). */
    private static java.util.List<String> jsonObjects(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) return out;
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { out.add(s.substring(start, i + 1)); start = -1; } }
        }
        return out;
    }

    /** Read a DIRECT child string field of a JSON object ("key":"value" at depth 1). */
    private static String topField(String obj, String key) {
        if (obj == null || key == null) return null;
        int depth = 0;
        int i = 0;
        int n = obj.length();
        while (i < n) {
            char c = obj.charAt(i);
            if (c == '"') {
                int j = i + 1;
                boolean esc = false;
                StringBuilder sb = new StringBuilder();
                while (j < n) {
                    char d = obj.charAt(j);
                    if (esc) { sb.append(d); esc = false; j++; continue; }
                    if (d == '\\') { esc = true; j++; continue; }
                    if (d == '"') break;
                    sb.append(d); j++;
                }
                int k = j + 1;
                while (k < n && Character.isWhitespace(obj.charAt(k))) k++;
                boolean isKey = k < n && obj.charAt(k) == ':';
                if (isKey && depth == 1 && key.equals(sb.toString())) {
                    k++;
                    while (k < n && Character.isWhitespace(obj.charAt(k))) k++;
                    if (k < n && obj.charAt(k) == '"') {
                        int v = k + 1;
                        boolean e2 = false;
                        StringBuilder vb = new StringBuilder();
                        while (v < n) {
                            char d = obj.charAt(v);
                            if (e2) { vb.append(d); e2 = false; v++; continue; }
                            if (d == '\\') { e2 = true; v++; continue; }
                            if (d == '"') break;
                            vb.append(d); v++;
                        }
                        return vb.toString();
                    }
                    return null;
                }
                i = j + 1;
                continue;
            }
            if (c == '{' || c == '[') { depth++; i++; continue; }
            if (c == '}' || c == ']') { depth--; i++; continue; }
            i++;
        }
        return null;
    }

    private HttpClient client() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        return http;
    }

    private String get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose spectatorcam, 1.0)")
                    .GET().build();
            HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (res == null || res.statusCode() != 200) return null;
            return res.body();
        } catch (Exception e) {
            return null;
        }
    }

    private void del(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("User-Agent", "DiscordBot (AllPurpose spectatorcam, 1.0)")
                    .DELETE().build();
            client().send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }
}
