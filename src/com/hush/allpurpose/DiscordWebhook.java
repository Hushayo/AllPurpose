package com.hush.allpurpose;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Discord relay: forwards chat, joins/leaves, deaths, rank-ups and duel
 * results to a webhook. Zero new dependencies (java.net.http, already on
 * Java 21). Config `discord-webhook:` empty = disabled. `discord-events:`
 * toggles each feed; everything is read per-use so edits apply on
 * /allpurpose reload with no restart.
 *
 * Rate safety: messages queue up and flush at most one 1900-char POST per
 * 2s from an async task (well under Discord's 30/min). Mentions disabled.
 */
public class DiscordWebhook implements Listener {

    private final AllPurpose plugin;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private boolean taskOn = false;
    private HttpClient http = null;

    public DiscordWebhook(AllPurpose plugin) {
        this.plugin = plugin;
    }

    private String url() {
        try {
            String u = plugin.getConfig().getString("discord-webhook", "");
            if (u == null) return "";
            u = u.trim();
            if (!u.startsWith("https://discord.com/api/webhooks/")) return "";
            return u;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean want(String key) {
        try {
            return plugin.getConfig().getBoolean("discord-events." + key, true);
        } catch (Exception e) {
            return true;
        }
    }

    public boolean enabled() {
        try {
            if (!plugin.getConfig().getBoolean("discord-enabled", true)) return false;
        } catch (Exception e) {
            return false;
        }
        return !url().isEmpty();
    }

    /** Public gate for callers (deaths, duels, ranks feeds). */
    public boolean wants(String key) {
        try {
            if (!enabled()) return false;
            return want(key);
        } catch (Exception e) {
            return false;
        }
    }

    /** Strip color codes for Discord (plain text only). */
    public static String clean(String s) {
        try {
            if (s == null) return "";
            String t = s.replaceAll("(?i)\u00A7[0-9a-fk-orx]", "");
            t = t.replaceAll("&[0-9a-fk-or]", "");
            return t.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** clean() plus hex + MiniMessage/gradient tag strip (for rank prefixes
     * only — never apply to chat text, players type things like <3 or a
     * literal &#FF0000 legitimately).
     *
     * clean() only knows one-char codes, so a hex prefix written as
     * '&#FF0000&lFIRE+' used to leak its raw '&#FF0000' into the relayed line
     * ("[&#FF0000FIRE+] [CO-OWNER]"). Strip the multi-char hex forms first:
     * the one-char pass would otherwise eat the &R/&G/&B pairs inside
     * &x&R&R&G&G&B&B and leave a stray 'x' behind. */
    public static String cleanTag(String s) {
        try {
            if (s == null) return "";
            String t = s;
            t = t.replaceAll("(?i)\u00A7x(\u00A7[0-9a-f]){6}", ""); // §x§R§R§G§G§B§B
            t = t.replaceAll("(?i)&x(&[0-9a-f]){6}", "");           // &x&R&R&G&G&B&B
            t = t.replaceAll("(?i)&#[0-9a-f]{6}", "");              // &#RRGGBB
            t = clean(t);                                           // leftover §c / &c codes
            t = t.replaceAll("<[^<>]*>", "");                       // <gradient:..>..</gradient>
            return t.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Player names never start with "/" — a stray one (seen once on a
     * Bedrock join) is noise, drop it rather than forwarding it. */
    public static String safeName(String n) {
        try {
            if (n == null) return "?";
            String t = n.trim().replaceFirst("^/+", "").trim();
            return t.isEmpty() ? "?" : t;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Queue a line for delivery. Cheap no-op when disabled. */
    public void send(String raw) {
        try {
            if (raw == null) return;
            String line = clean(raw);
            if (line.isEmpty()) return;
            if (!enabled()) return;
            if (queue.size() > 200) queue.poll(); // shed load, never grow forever
            queue.add(line);
            ensureTask();
        } catch (Exception ignored) {}
    }

    private synchronized void ensureTask() {
        try {
            if (taskOn) return;
            taskOn = true;
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override public void run() {
                    try {
                        flush();
                    } catch (Exception ignored) {}
                }
            }, 40L, 40L);
        } catch (Exception ignored) {
            taskOn = false;
        }
    }

    /** Best-effort synchronous drain for onDisable (async scheduler may be dead). */
    public void shutdownFlush(String finalMsg) {
        try {
            if (finalMsg != null && !finalMsg.isEmpty()) {
                try {
                    if (enabled() && want("plugin")) {
                        String line = clean(finalMsg);
                        if (!line.isEmpty()) queue.add(line);
                    }
                } catch (Exception ignored) {}
            }
            List<String> take = new ArrayList<>();
            int len = 0;
            while (!queue.isEmpty() && take.size() < 10) {
                String peek = queue.peek();
                if (peek == null) break;
                if (len + peek.length() + 1 > 1900) break;
                queue.poll();
                take.add(peek);
                len += peek.length() + 1;
            }
            if (!take.isEmpty()) post(String.join("\n", take));
        } catch (Exception ignored) {}
    }

    private void flush() {
        try {
            if (queue.isEmpty()) return;
            if (!enabled()) { queue.clear(); return; }
            List<String> take = new ArrayList<>();
            int len = 0;
            while (!queue.isEmpty() && take.size() < 10) {
                String peek = queue.peek();
                if (peek == null) break;
                if (len + peek.length() + 1 > 1900) break;
                queue.poll();
                take.add(peek);
                len += peek.length() + 1;
            }
            if (take.isEmpty()) { queue.clear(); return; }
            post(String.join("\n", take));
        } catch (Exception ignored) {}
    }

    private void post(String content) {
        try {
            if (http == null) {
                http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            }
            String body = "{\"content\":" + json(content)
                    + ",\"allowed_mentions\":{\"parse\":[]}}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res != null && res.statusCode() == 429) {
                try { Thread.sleep(5000); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    // ---------- event feeds (all read-only, never cancel) ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoinFeed(PlayerJoinEvent e) {
        try {
            if (!want("joins")) return;
            Player p = e.getPlayer();
            if (p == null) return;
            send("\uD83D\uDFE2 " + safeName(p.getName()) + " joined");
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuitFeed(PlayerQuitEvent e) {
        try {
            if (!want("joins")) return;
            Player p = e.getPlayer();
            if (p == null) return;
            send("\uD83D\uDD34 " + safeName(p.getName()) + " left");
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatFeed(AsyncChatEvent e) {
        try {
            if (!want("chat")) return;
            Player p = e.getPlayer();
            if (p == null) return;
            String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
            if (msg == null) return;
            msg = msg.trim();
            if (msg.isEmpty()) return;
            if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
            String tag = "";
            try {
                String raw = plugin.getRanks().fullTagRaw(p);
                if (raw != null && !raw.isEmpty()) tag = cleanTag(raw) + " ";
            } catch (Exception ignored) {}
            send(tag + safeName(p.getName()) + ": " + msg);
        } catch (Exception ignored) {}
    }
}
