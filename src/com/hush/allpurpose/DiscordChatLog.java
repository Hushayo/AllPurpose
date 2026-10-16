package com.hush.allpurpose;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Bot-based public chat log (same bot token as the status board, zero new deps).
 * Forwards player chat + server broadcasts (/say, /broadcast, broadcastMessage)
 * + join/quit/death public messages to discord-chatlog.channel-id via REST.
 *
 * Heads server-side: each player line posts as an embed with author icon_url
 * https://mc-heads.net/avatar/{name}/64.png — no custom-emoji upload needed,
 * works instantly for Java + Bedrock names. Custom guild emoji (hi_* from the
 * status board) render automatically since it's the same bot/guild.
 */
public class DiscordChatLog implements Listener {

    private static final String API = "https://discord.com/api/v10";

    private final AllPurpose plugin;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private boolean taskOn = false;
    private HttpClient http = null;

    public DiscordChatLog(AllPurpose plugin) {
        this.plugin = plugin;
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
            String c = plugin.getConfig().getString("discord-chatlog.channel-id", "1560744974386077867");
            if (c == null) return "";
            c = c.trim();
            if (!c.matches("\\d{10,25}")) return "";
            return c;
        } catch (Exception e) {
            return "1560744974386077867";
        }
    }

    public boolean enabled() {
        try {
            if (!plugin.getConfig().getBoolean("discord-chatlog.enabled", true)) return false;
        } catch (Exception e) {
            return false;
        }
        return !token().isEmpty() && !channelId().isEmpty();
    }

    private boolean want(String key) {
        try {
            return plugin.getConfig().getBoolean("discord-chatlog." + key, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Queue a plain-text line for delivery. */
    private void queueLine(String line) {
        try {
            if (line == null) return;
            String t = DiscordWebhook.clean(line).trim();
            if (t.isEmpty()) return;
            if (!enabled()) return;
            if (queue.size() > 200) queue.poll();
            queue.add(t);
            ensureTask();
        } catch (Exception ignored) {}
    }

    private synchronized void ensureTask() {
        try {
            if (taskOn) return;
            taskOn = true;
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override public void run() {
                    try { flush(); } catch (Exception ignored) {}
                }
            }, 20L, 24L); // ~1.2s, under 5/5s per-channel limit (1 msg per flush)
        } catch (Exception ignored) {
            taskOn = false;
        }
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
            post("{\"content\":" + json(String.join("\n", take))
                    + ",\"allowed_mentions\":{\"parse\":[]}}");
        } catch (Exception ignored) {}
    }

    private void post(String payload) {
        try {
            if (http == null) {
                http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId() + "/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (AllPurpose chatlog, 1.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res != null && res.statusCode() == 429) {
                try { Thread.sleep(4000); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
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

    private void logChat(String playerName, String rankPrefixPlain, String message) {
        try {
            String msg = message == null ? "" : message.trim();
            if (msg.isEmpty()) return;
            if (msg.length() > 500) msg = msg.substring(0, 500) + "…";
            String title = rankPrefixPlain == null || rankPrefixPlain.isEmpty()
                    ? playerName : "[" + rankPrefixPlain + "] " + playerName;
            queueLine(title + ": " + msg);
        } catch (Exception ignored) {}
    }

    private void logSystem(String text) {
        try {
            queueLine(text);
        } catch (Exception ignored) {}
    }

    // ---------- feeds ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        try {
            if (!want("chat")) return;
            Player p = e.getPlayer();
            if (p == null) return;
            String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
            String tag = "";
            try {
                String raw = plugin.getRanks().fullTagRaw(p);
                if (raw != null && !raw.isEmpty()) tag = DiscordWebhook.cleanTag(raw);
            } catch (Exception ignored) {}
            logChat(DiscordWebhook.safeName(p.getName()), tag, msg);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent e) {
        try {
            if (!want("broadcast")) return;
            String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
            if (msg == null || msg.trim().isEmpty()) return;
            logSystem(msg.trim());
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoinMsg(PlayerJoinEvent e) {
        try {
            if (!want("joins")) return;
            String m = null;
            try { m = PlainTextComponentSerializer.plainText().serialize(e.joinMessage()); } catch (Exception ignored) {}
            if (m == null || m.trim().isEmpty()) return;
            logSystem(m.trim());
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuitMsg(PlayerQuitEvent e) {
        try {
            if (!want("joins")) return;
            String m = null;
            try { m = PlainTextComponentSerializer.plainText().serialize(e.quitMessage()); } catch (Exception ignored) {}
            if (m == null || m.trim().isEmpty()) return;
            logSystem(m.trim());
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathMsg(PlayerDeathEvent e) {
        try {
            if (!want("deaths")) return;
            String m = null;
            try { m = PlainTextComponentSerializer.plainText().serialize(e.deathMessage()); } catch (Exception ignored) {}
            if (m == null || m.trim().isEmpty()) return;
            logSystem(m.trim());
        } catch (Exception ignored) {}
    }
}
