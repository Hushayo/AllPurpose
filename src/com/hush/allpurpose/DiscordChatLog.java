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
 * LAYOUT (owner request 2026-10-19): plain text lines, NOT embeds — head first,
 * then the rank tag, then the name/text:
 *
 *     <:head_ab12cd34ef56:1234> [ADMIN] **KingsterVV**: btw cmere
 *     <:head_ab12cd34ef56:1234> [ADMIN] [#8] KingsterVV joined the game
 *
 * EVERY line that is about a player gets the same head + rank prefix: chat (the
 * speaker), join / quit / death (the player the line is about). Only
 * console/broadcast and AllPurpose system lines (no player) stay bare.
 *
 * The head is the status board's cached guild emoji (same bot/guild, so it
 * renders): DiscordStatusBoard.headEmojiTag() returns the player's uploaded
 * head, the Steve head, or a bust glyph while it is still being uploaded. The
 * board uploads one head per tick for everyone online, so a chatting player's
 * head is usually cached already and always appears within a tick or two.
 * discord-chatlog.heads: false drops the prefix (old plain lines).
 * Custom guild emoji (hi_* / head_* from the status board) render automatically
 * since it is the same bot/guild.
 */
public class DiscordChatLog implements Listener {

    private static final String API = "https://discord.com/api/v10";

    /** Batching: Discord caps a message at 2000 chars; stay clear of it. */
    private static final int MAX_LINES = 10;
    private static final int MAX_CHARS = 1900;
    private static final int MAX_MSG = 500;

    private final AllPurpose plugin;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private boolean taskOn = false;
    private boolean warned = false;
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

    /** Prefix player lines with their head emoji. false = plain text only. */
    private boolean wantHeads() {
        try {
            return plugin.getConfig().getBoolean("discord-chatlog.heads", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Public: queue a plain-text system line (coinflip / bounty / shop / tax / prize). */
    public void system(String text) {
        try { queueLine(text); } catch (Exception ignored) {}
    }

    /** System text: colour codes stripped (these lines come from plugins, never players). */
    private void queueLine(String line) {
        try {
            if (line == null) return;
            queue(DiscordWebhook.clean(line));
        } catch (Exception ignored) {}
    }

    /**
     * Player chat: NOT colour-stripped. DiscordWebhook.cleanTag's contract is
     * that the prefix is cleaned and the message is left alone — players
     * legitimately type "&#FF0000" or "&c" and it should show up as typed.
     */
    private void queuePlayerLine(String line) {
        try {
            if (line == null) return;
            queue(line);
        } catch (Exception ignored) {}
    }

    private void queue(String line) {
        try {
            if (line == null) return;
            String t = line.trim();
            if (t.isEmpty()) return;
            if (t.length() > MAX_CHARS) t = t.substring(0, MAX_CHARS - 1) + "…";
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
            while (!queue.isEmpty() && take.size() < MAX_LINES) {
                String peek = queue.peek();
                if (peek == null) break;
                if (!take.isEmpty() && len + peek.length() + 1 > MAX_CHARS) break;
                queue.poll();
                take.add(peek);
                len += peek.length() + 1;
            }
            if (take.isEmpty()) { queue.poll(); return; } // one over-long line: drop just it
            int code = post("{\"content\":" + json(String.join("\n", take))
                    + ",\"allowed_mentions\":{\"parse\":[]}}");
            if (code >= 400 && !warned) {
                warned = true;
                try {
                    plugin.getLogger().warning("Discord chat log: post to channel " + channelId()
                            + " returned HTTP " + code + " — check the bot can Send Messages there.");
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** Returns the HTTP status, or -1 when the request never got a response. */
    private int post(String payload) {
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
            if (res == null) return -1;
            if (res.statusCode() == 429) {
                try { Thread.sleep(4000); } catch (Exception ignored) {}
            }
            return res.statusCode();
        } catch (Exception e) {
            return -1;
        }
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

    /** Discord markdown inside **bold** breaks if the name carries a control char
     * (Bedrock names can), so escape the few that matter. */
    private static String mdSafe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '_' || c == '~' || c == '`' || c == '|' || c == '\\') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /** The speaker's head as a guild emoji (same bot/guild as the status board),
     * or "" when heads are off / the board has nothing cached. */
    private String headPrefix(Player p) {
        try {
            if (!wantHeads()) return "";
            DiscordStatusBoard board = plugin.getStatusBoard();
            if (board == null) return "";
            String tag = board.headEmojiTag(p.getName(), p.getUniqueId());
            if (tag == null || tag.isEmpty()) return "";
            return tag + " ";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Player chat -> "<head> [RANK] **Name**: msg".
     *
     * rawPrefix is RankManager.fullTagRaw(), already bracketed per tag
     * ("[FIRE+] [CO-OWNER] "), so it is used verbatim: wrapping it again produced
     * the old double-bracket line ("[[MEMBER]] NotSlabby: ...").
     */
    private void logChat(Player p, String playerName, String rawPrefix, String message) {
        try {
            String msg = message == null ? "" : message.trim();
            if (msg.isEmpty()) return;
            if (msg.length() > MAX_MSG) msg = msg.substring(0, MAX_MSG) + "…";
            // cleanTag() strips the prefix's codes; the chat text itself is sent
            // as typed (see queuePlayerLine).
            String tag = DiscordWebhook.cleanTag(rawPrefix);
            String head = headPrefix(p);
            String name = mdSafe(playerName);
            String body = tag.isEmpty() ? name : tag + " " + name;
            queuePlayerLine(head + body + ": " + msg);
        } catch (Exception ignored) {}
    }

    /**
     * Join / quit / death: same "<head> [RANK] <text>" shape as chat, but the
     * text is another plugin's message and is passed through untouched (the
     * speaker is the subject here, not the author). Players with no rank and no
     * cached head degrade to just the message, as before.
     */
    private void logPlayerEvent(Player p, String message) {
        try {
            String m = message == null ? "" : message.trim();
            if (m.isEmpty()) return;
            String tag = "";
            String head = "";
            if (p != null) {
                try { tag = DiscordWebhook.cleanTag(plugin.getRanks().fullTagRaw(p)); } catch (Exception ignored) {}
                head = headPrefix(p);
            }
            queueLine(head + (tag.isEmpty() ? "" : tag + " ") + m);
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
            String raw = "";
            try {
                String tag = plugin.getRanks().fullTagRaw(p);
                if (tag != null) raw = tag;
            } catch (Exception ignored) {}
            logChat(p, DiscordWebhook.safeName(p.getName()), raw, msg);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent e) {
        try {
            if (!want("broadcast")) return;
            String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
            if (msg == null || msg.trim().isEmpty()) return;
            queueLine(msg.trim());
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoinMsg(PlayerJoinEvent e) {
        try {
            if (!want("joins")) return;
            String m = null;
            try { m = PlainTextComponentSerializer.plainText().serialize(e.joinMessage()); } catch (Exception ignored) {}
            if (m == null || m.trim().isEmpty()) return;
            logPlayerEvent(e.getPlayer(), m);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuitMsg(PlayerQuitEvent e) {
        try {
            if (!want("joins")) return;
            String m = null;
            try { m = PlainTextComponentSerializer.plainText().serialize(e.quitMessage()); } catch (Exception ignored) {}
            if (m == null || m.trim().isEmpty()) return;
            logPlayerEvent(e.getPlayer(), m);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathMsg(PlayerDeathEvent e) {
        try {
            if (!want("deaths")) return;
            String m = null;
            try { m = PlainTextComponentSerializer.plainText().serialize(e.deathMessage()); } catch (Exception ignored) {}
            if (m == null || m.trim().isEmpty()) return;
            logPlayerEvent(e.getEntity(), m);
        } catch (Exception ignored) {}
    }
}
