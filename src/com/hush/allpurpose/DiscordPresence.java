package com.hush.allpurpose;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Discord gateway presence: keeps the bot showing ONLINE in the member list.
 *
 * The status board is REST-only (message edits need no gateway), and REST
 * alone never marks a bot online — only a live gateway session does. This
 * client holds one session with zero intents (no privileged approval needed):
 * Hello -> Identify (online + Watching activity) -> heartbeat until closed.
 * Reconnects with backoff on drops. Pure java.net.http websocket, no JDA.
 */
public class DiscordPresence {

    private final AllPurpose plugin;
    private final Object lock = new Object();
    private volatile WebSocket ws = null;
    private volatile BukkitTask heartbeat = null;
    private volatile long heartbeatMs = 41250;
    private volatile int seq = -1;
    private final AtomicInteger connectSeq = new AtomicInteger(0);
    private volatile boolean wantOn = false;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public DiscordPresence(AllPurpose plugin) {
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

    public void start() {
        wantOn = true;
        connect();
    }

    public void stop() {
        wantOn = false;
        close();
    }

    public void reload() {
        try { stop(); } catch (Exception ignored) {}
        try { Thread.sleep(1500); } catch (Exception ignored) {}
        start();
    }

    private void close() {
        synchronized (lock) {
            try { if (heartbeat != null) heartbeat.cancel(); } catch (Exception ignored) {}
            heartbeat = null;
            try {
                if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "stop");
            } catch (Exception ignored) {}
            ws = null;
        }
    }

    /** Refresh the Watching text, e.g. "7/33 players". No-op when offline. */
    public void updateActivity() {
        try {
            WebSocket w = ws;
            if (w == null) return;
            int online = Bukkit.getOnlinePlayers().size();
            int max = Math.max(1, Bukkit.getMaxPlayers());
            w.sendText(json("{\"op\":3,\"d\":{\"since\":null,\"activities\":[{\"name\":\""
                    + online + "/" + max + " players\",\"type\":3}],\"status\":\"online\",\"afk\":false}}"), true);
        } catch (Exception ignored) {}
    }

    private void connect() {
        if (!wantOn) return;
        final int attempt = connectSeq.incrementAndGet();
        try {
            String t = token();
            if (t.isEmpty()) return;
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            CompletableFuture<WebSocket> f = client.newWebSocketBuilder()
                    .header("User-Agent", "DiscordBot (AllPurpose presence, 1.0)")
                    .buildAsync(URI.create("wss://gateway.discord.gg/?v=10&encoding=json"), new Listener(attempt));
            f.whenComplete((w, err) -> {
                if (err != null) {
                    if (warned.compareAndSet(false, true)) {
                        try { plugin.getLogger().warning("Discord presence: connect failed (" + err.getMessage() + "), retrying in background."); } catch (Exception ignored) {}
                    }
                    scheduleReconnect(attempt, 15000);
                }
            });
        } catch (Exception e) {
            scheduleReconnect(attempt, 15000);
        }
    }

    private void scheduleReconnect(int attempt, long delayMs) {
        try {
            if (!wantOn || attempt != connectSeq.get()) return;
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {
                @Override public void run() {
                    try {
                        if (!wantOn || attempt != connectSeq.get()) return;
                        connect();
                    } catch (Exception ignored) {}
                }
            }, Math.max(20L, delayMs / 50L));
        } catch (Exception ignored) {}
    }

    private void onHello(int attempt, int intervalMs) {
        synchronized (lock) {
            heartbeatMs = Math.max(5000, intervalMs);
            try { if (heartbeat != null) heartbeat.cancel(); } catch (Exception ignored) {}
            heartbeat = null;
        }
        sendIdentify();
        synchronized (lock) {
            if (attempt != connectSeq.get()) return;
            try { if (heartbeat != null) heartbeat.cancel(); } catch (Exception ignored) {}
            try {
                heartbeat = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                    @Override public void run() {
                        try {
                            WebSocket w = ws;
                            if (w == null) return;
                            String payload = seq >= 0
                                    ? "{\"op\":1,\"d\":" + seq + "}"
                                    : "{\"op\":1,\"d\":null}";
                            w.sendText(payload, true);
                        } catch (Exception ignored) {}
                    }
                }, heartbeatMs / 50L, heartbeatMs / 50L);
            } catch (Exception ignored) {}
        }
    }

    private void sendIdentify() {
        try {
            WebSocket w = ws;
            if (w == null) return;
            int online = Bukkit.getOnlinePlayers().size();
            int max = Math.max(1, Bukkit.getMaxPlayers());
            // intents 0: session + presence only, no privileged intents needed.
            w.sendText("{\"op\":2,\"d\":{\"token\":" + json(token())
                    + ",\"intents\":0,\"properties\":{\"os\":\"linux\",\"browser\":\"allpurpose\",\"device\":\"allpurpose\"}"
                    + ",\"presence\":{\"status\":\"online\",\"afk\":false,\"since\":null,\"activities\":[{\"name\":\""
                    + online + "/" + max + " players\",\"type\":3}]}}}", true);
        } catch (Exception ignored) {}
    }

    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static int intField(String s, String key, int def) {
        try {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(s);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return def;
    }

    private class Listener implements WebSocket.Listener {
        private final int attempt;
        private final StringBuilder buf = new StringBuilder();

        Listener(int attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (lock) {
                if (attempt != connectSeq.get() || !wantOn) {
                    try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "stale"); } catch (Exception ignored) {}
                    return;
                }
                ws = webSocket;
            }
            warned.set(false);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                buf.append(data);
                if (last) {
                    String msg = buf.toString();
                    buf.setLength(0);
                    handle(msg, attempt);
                }
            } catch (Exception ignored) {}
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (lock) {
                if (webSocket == ws) ws = null;
                try { if (heartbeat != null) heartbeat.cancel(); } catch (Exception ignored) {}
                heartbeat = null;
            }
            scheduleReconnect(attempt, 8000);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            try { webSocket.abort(); } catch (Exception ignored) {}
            synchronized (lock) {
                if (webSocket == ws) ws = null;
                try { if (heartbeat != null) heartbeat.cancel(); } catch (Exception ignored) {}
                heartbeat = null;
            }
            scheduleReconnect(attempt, 8000);
        }
    }

    private void handle(String msg, int attempt) {
        try {
            if (attempt != connectSeq.get() || !wantOn) return;
            int op = intField(msg, "op", -1);
            if (msg.contains("\"s\"")) {
                int s = intField(msg, "s", -2);
                if (s >= 0) seq = s;
            }
            if (op == 10) { // Hello
                int interval = 41250;
                try {
                    Matcher m = Pattern.compile("\"heartbeat_interval\"\\s*:\\s*(\\d+)").matcher(msg);
                    if (m.find()) interval = Integer.parseInt(m.group(1));
                } catch (Exception ignored) {}
                onHello(attempt, interval);
            } else if (op == 11) { // Heartbeat ACK: session alive
                warned.set(false);
            } else if (op == 7) { // Reconnect requested
                try {
                    WebSocket w = ws;
                    if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
                } catch (Exception ignored) {}
                synchronized (lock) {
                    ws = null;
                    try { if (heartbeat != null) heartbeat.cancel(); } catch (Exception ignored) {}
                    heartbeat = null;
                }
                scheduleReconnect(attempt, 2000);
            } else if (op == 9) { // Invalid session: re-identify after a beat
                scheduleReconnect(attempt, 5000);
            }
        } catch (Exception ignored) {}
    }
}
