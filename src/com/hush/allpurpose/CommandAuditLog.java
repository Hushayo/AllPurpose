package com.hush.allpurpose;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Audit log for commands and private messages (Discord "rule 12: ingame and
 * Discord"). Two independent feeds, so the sensitive one can live somewhere
 * separate:
 *
 *   command log  -> discord-commandlog.channel-id   (staff channel)
 *   DM log       -> discord-commandlog.dm-channel-id (owner + dev only) or,
 *                   when that key is empty, ONLY the local file below.
 *
 * Every line is ALSO appended to
 *   plugins/AllPurpose/commandlog/commands-YYYY-MM-DD.log
 *   plugins/AllPurpose/commandlog/dms-YYYY-MM-DD.log
 * so the record survives a deleted channel, a dead webhook or a bot outage.
 * That file is the one to cite in an incident — CoreProtect cannot see
 * commands, console actions or private messages at all.
 *
 * Design points (owner request 2026-10-21):
 * - PlayerCommandPreprocessEvent + ServerCommandEvent at MONITOR with
 *   ignoreCancelled = false, so blocked attempts (/op, guarded commands) are
 *   recorded too, not just the ones that ran.
 * - Passwords never leave the server: /login /register /changepassword /email
 *   /auth /2fa ... are written as the command name followed by "<redacted>",
 *   in Discord AND in the file. Webhook URLs, "token=..."-style arguments and
 *   Discord bot-token shapes are redacted anywhere else they appear. The bot
 *   token itself is only ever sent in the Authorization header.
 * - Discord is rate-limited by batching: one async flush every
 *   batch-seconds, up to discord-lines-per-flush lines and
 *   max-posts-per-flush messages. Anything above that becomes one
 *   "… +N more lines suppressed" line; the file still gets everything.
 * - Noise filter (spawn/home/tpa/...) and the DM-command list are config
 *   driven, so the staff channel stays readable.
 *
 * Read the config keys per call, so /allpurpose reload retunes it live.
 */
public class CommandAuditLog implements Listener {

    private static final String API = "https://discord.com/api/v10";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_CHARS = 1900;
    private static final int MAX_LINES_PER_MESSAGE = 12;

    /** Commands whose arguments are never written down (auth / credentials). */
    private static final Set<String> SENSITIVE = Set.of(
            "login", "register", "changepassword", "changepass", "passwd",
            "password", "email", "auth", "2fa", "totp", "unregister", "setpassword");

    /** Noise filter used when `ignore-commands` is absent from config. */
    private static final List<String> DEFAULT_IGNORE = List.of(
            "spawn", "home", "sethome", "tpa", "tpahere", "tpaccept", "tpdeny", "back", "warp");

    /** Private-message commands, routed to the DM feed instead of the command feed. */
    private static final List<String> DEFAULT_DM = List.of(
            "msg", "tell", "whisper", "emsg", "pm", "dm", "r", "reply");

    private static final Pattern WEBHOOK = Pattern.compile(
            "(?i)https?://(?:discord|discordapp|ptb\\.discord)\\.com/api/webhooks/\\S+");
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)\\b(token|secret|password|passwd|api[_-]?key|apikey)(\\s*[=:]\\s*)(\\S+)");
    private static final Pattern BOT_TOKEN = Pattern.compile(
            "\\b[A-Za-z0-9_-]{24,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{25,}\\b");
    private static final Pattern SECTION = Pattern.compile("(?i)\u00A7[0-9a-fk-orx]");

    private final AllPurpose plugin;
    private final ConcurrentLinkedQueue<Line> commands = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Line> dms = new ConcurrentLinkedQueue<>();

    /**
     * One audit event in two renderings: {@link #file} is the exact evidence
     * line (UUID + coordinates included), {@link #discord} is the same line
     * with both removed. The Discord feed is read by more people than the local
     * file, and a command typed at a base must not become a locator.
     */
    private static final class Line {
        final String file;
        final String discord;
        Line(String file, String discord) {
            this.file = file;
            this.discord = discord;
        }
    }
    private final AtomicInteger cmdDropped = new AtomicInteger();
    private final AtomicInteger dmDropped = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Object fileLock = new Object();
    private final Map<UUID, String> tagCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> tagCacheAt = new ConcurrentHashMap<>();
    private volatile Set<String> botNames = Collections.emptySet();
    private volatile long botNamesAt;
    private volatile HttpClient http;
    private volatile boolean warned;

    public CommandAuditLog(AllPurpose plugin) {
        this.plugin = plugin;
    }

    /** Starts the async flush task. Safe to call twice. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        try {
            long ticks = cfgInt("batch-seconds", 2, 1, 30) * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
                @Override public void run() {
                    try { flush(); } catch (Exception ignored) {}
                }
            }, ticks, ticks);
        } catch (Exception e) {
            started.set(false);
        }
    }

    /** Best-effort synchronous drain for onDisable (async scheduler may be dead). */
    public void shutdownFlush() {
        try {
            flushQueue(commands, "commands", cmdDropped, cfgStr("channel-id", "1562354261466153040"));
        } catch (Exception ignored) {}
        try {
            flushQueue(dms, "dms", dmDropped, cfgStr("dm-channel-id", ""));
        } catch (Exception ignored) {}
    }

    // ---------- config ----------

    private boolean cfgBool(String key, boolean def) {
        try { return plugin.getConfig().getBoolean("discord-commandlog." + key, def); }
        catch (Exception e) { return def; }
    }

    private int cfgInt(String key, int def, int min, int max) {
        try {
            int v = plugin.getConfig().getInt("discord-commandlog." + key, def);
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) { return def; }
    }

    private String cfgStr(String key, String def) {
        try {
            String s = plugin.getConfig().getString("discord-commandlog." + key, def);
            return s == null ? def : s.trim();
        } catch (Exception e) { return def; }
    }

    private List<String> cfgList(String key, List<String> def) {
        try {
            if (plugin.getConfig().isSet("discord-commandlog." + key)) {
                List<String> l = plugin.getConfig().getStringList("discord-commandlog." + key);
                if (l != null) return l;
            }
        } catch (Exception ignored) {}
        return def;
    }

    private List<String> ignore() {
        List<String> raw = cfgList("ignore-commands", DEFAULT_IGNORE);
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            String n = base(s);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    private List<String> dmCommands() {
        List<String> raw = cfgList("dm-commands", DEFAULT_DM);
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            String n = base(s);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
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

    private boolean enabled() {
        return cfgBool("enabled", true);
    }

    // ---------- capture ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        try {
            if (!enabled()) return;
            Player p = e.getPlayer();
            if (p == null) return;
            if (!cfgBool("include-bots", false) && isBot(p)) return;
            recordPlayer(p, e.getMessage());
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onServerCommand(ServerCommandEvent e) {
        try {
            if (!enabled()) return;
            String sender = "CONSOLE";
            try {
                if (e.getSender() != null) sender = String.valueOf(e.getSender().getName());
            } catch (Exception ignored) {}
            boolean rcon = sender.toUpperCase(Locale.ROOT).contains("RCON");
            if (!cfgBool(rcon ? "rcon" : "console", true)) return;

            String line = clean(e.getCommand());
            if (line.isEmpty()) return;
            String[] parts = line.split("\\s+");
            String name = base(parts[0]);
            if (name.isEmpty()) return;
            if (ignore().contains(name)) return;
            String args = line.length() > parts[0].length() ? line.substring(parts[0].length()).trim() : "";
            String out = ts() + " [" + (rcon ? "RCON" : "CONSOLE") + "] /" + redactCommand(name, args);
            offer(commands, out, cmdDropped);
        } catch (Exception ignored) {}
    }

    private void recordPlayer(Player p, String raw) {
        String line = clean(raw);
        if (line.isEmpty()) return;
        String[] parts = line.split("\\s+");
        String name = base(parts[0]);
        if (name.isEmpty()) return;
        String args = line.length() > parts[0].length() ? line.substring(parts[0].length()).trim() : "";

        if (dmCommands().contains(name)) {
            if (!cfgBool("dms", true)) return;
            String target;
            String message;
            if (name.equals("r") || name.equals("reply")) {
                target = "(reply)";
                message = args;
            } else {
                String[] a = args.split("\\s+", 2);
                target = a.length > 0 && !a[0].isEmpty() ? a[0] : "?";
                message = a.length > 1 ? a[1] : "";
            }
            if (message.trim().isEmpty()) return;
            String out = ts() + " " + tag(p) + name(p) + " -> " + cleanName(target)
                    + ": " + redactText(message);
            offer(dms, out, dmDropped);
            return;
        }

        if (ignore().contains(name)) return;

        Location loc = null;
        try { loc = p.getLocation(); } catch (Exception ignored) {}
        String cmd = redactCommand(name, args);
        String head = ts() + " " + tag(p) + name(p);
        // The file is the evidence copy: UUID and exact position are useful
        // there. The Discord copy drops both by default, because the channel is
        // read by more people and a command typed at a base otherwise becomes a
        // locator. `uuid-in-discord` / `coords-in-discord` re-enable them.
        String fileLine = head + " (" + p.getUniqueId() + ") /" + cmd;
        String discordLine = head
                + (cfgBool("uuid-in-discord", false) ? " (" + p.getUniqueId() + ")" : "")
                + " /" + cmd;
        if (loc != null) {
            String world = loc.getWorld() == null ? "?" : loc.getWorld().getName();
            String coords = " @ " + world + " " + loc.getBlockX() + " "
                    + loc.getBlockY() + " " + loc.getBlockZ();
            fileLine += coords;
            discordLine += cfgBool("coords-in-discord", false) ? coords : " @ " + world;
        }
        offer(commands, fileLine, discordLine, cmdDropped);
    }

    private void offer(ConcurrentLinkedQueue<Line> q, String line, AtomicInteger dropped) {
        offer(q, line, line, dropped);
    }

    private void offer(ConcurrentLinkedQueue<Line> q, String file, String discord, AtomicInteger dropped) {
        try {
            if (file == null || file.isEmpty()) return;
            int cap = cfgInt("queue-cap", 2000, 50, 20000);
            if (q.size() >= cap) {
                q.poll();
                dropped.incrementAndGet();
            }
            q.add(new Line(file, discord == null ? file : discord));
        } catch (Exception ignored) {}
    }

    // ---------- redaction ----------

    /** Built-in auth set plus config `redact-commands`. */
    private Set<String> sensitive() {
        List<String> extra = cfgList("redact-commands", null);
        if (extra == null || extra.isEmpty()) return SENSITIVE;
        Set<String> out = new java.util.HashSet<>(SENSITIVE);
        for (String s : extra) {
            String n = base(s);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** Command name + arguments, with credentials removed. */
    private String redactCommand(String name, String args) {
        if (sensitive().contains(name)) {
            return name + (args == null || args.isEmpty() ? "" : " <redacted>");
        }
        String all = (args == null || args.isEmpty()) ? name : name + " " + args;
        return redactText(all);
    }

    /** Webhook URLs, token=... pairs and bot-token shapes. */
    private static String redactText(String s) {
        if (s == null) return "";
        String t = s;
        try {
            t = WEBHOOK.matcher(t).replaceAll("<webhook>");
            t = SECRET_KV.matcher(t).replaceAll("$1$2<redacted>");
            t = BOT_TOKEN.matcher(t).replaceAll("<token>");
        } catch (Exception ignored) {}
        return t;
    }

    // ---------- delivery ----------

    private void flush() {
        try {
            if (enabled()) flushQueue(commands, "commands", cmdDropped, cfgStr("channel-id", "1562354261466153040"));
        } catch (Exception ignored) {}
        try {
            if (enabled() && cfgBool("dms", true)) {
                flushQueue(dms, "dms", dmDropped, cfgStr("dm-channel-id", ""));
            }
        } catch (Exception ignored) {}
    }

    /**
     * Drain a feed: everything goes to the local file, a bounded slice goes to
     * Discord. `channel` empty = file-only feed (the default for DMs).
     */
    private void flushQueue(ConcurrentLinkedQueue<Line> q, String kind,
                            AtomicInteger dropped, String channel) {
        if (q.isEmpty()) return;
        int cap = cfgInt("queue-cap", 2000, 50, 20000);
        List<Line> take = new ArrayList<>();
        while (take.size() < cap) {
            Line s = q.poll();
            if (s == null) break;
            take.add(s);
        }
        if (take.isEmpty()) return;

        List<String> fileLines = new ArrayList<>(take.size());
        List<String> sendLines = new ArrayList<>(take.size());
        for (Line l : take) {
            fileLines.add(l.file);
            if (l.discord != null && !l.discord.isEmpty()) sendLines.add(l.discord);
        }
        appendFile(kind, fileLines);

        if (channel == null || channel.isEmpty() || !channel.matches("\\d{10,25}")) return;
        if (token().isEmpty()) return;

        int budget = cfgInt("discord-lines-per-flush", 24, 1, 200);
        List<String> show = sendLines.size() <= budget
                ? sendLines
                : new ArrayList<>(sendLines.subList(0, budget));
        int hidden = sendLines.size() - show.size();

        List<String> messages = chunk(show);
        if (messages.isEmpty()) return;
        int posts = cfgInt("max-posts-per-flush", 3, 1, 10);
        // Reserve the last post of the budget for the suppression notice only
        // when something really will not fit, so normal traffic is never cut.
        boolean notice = hidden > 0 || messages.size() > posts;
        int allowed = notice ? Math.max(1, posts - 1) : posts;
        int unposted = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (i < allowed) post(channel, messages.get(i));
            else unposted += countLines(messages.get(i));
        }
        // Whatever did not fit (budget, post cap, or queue overflow) becomes one
        // visible line, so the channel never silently loses a gap.
        int suppressed = hidden + unposted + dropped.getAndSet(0);
        if (suppressed > 0) {
            post(channel, "… +" + suppressed + " more line" + (suppressed == 1 ? "" : "s")
                    + " suppressed (full log: plugins/AllPurpose/commandlog/" + kind + "-….log)");
        }
    }

    private static List<String> chunk(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String line : lines) {
            if (b.length() > 0 && (n >= MAX_LINES_PER_MESSAGE || b.length() + line.length() + 1 > MAX_CHARS)) {
                out.add(b.toString());
                b.setLength(0);
                n = 0;
            }
            if (b.length() > 0) b.append('\n');
            b.append(line);
            n++;
        }
        if (b.length() > 0) out.add(b.toString());
        return out;
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private void appendFile(String kind, List<String> lines) {
        if (!cfgBool("file", true)) return;
        try {
            File dir = new File(plugin.getDataFolder(), cfgStr("file-dir", "commandlog"));
            if (!dir.exists() && !dir.mkdirs()) return;
            String day = LocalDateTime.now().format(DAY);
            Path path = new File(dir, kind + "-" + day + ".log").toPath();
            boolean fresh = !Files.exists(path);
            StringBuilder sb = new StringBuilder();
            if (fresh) {
                sb.append("# AllPurpose ").append(kind)
                        .append(" audit log (server clock) — one line per event\n");
            }
            for (String l : lines) sb.append(l).append('\n');
            synchronized (fileLock) {
                Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {}
    }

    private void post(String channel, String content) {
        try {
            if (content == null || content.isEmpty()) return;
            if (http == null) {
                http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            }
            String body = "{\"content\":" + json(content) + ",\"allowed_mentions\":{\"parse\":[]}}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/channels/" + channel + "/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBot (AllPurpose auditlog, 1.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res == null) return;
            if (res.statusCode() == 429) {
                try { Thread.sleep(1500); } catch (Exception ignored) {}
            } else if (res.statusCode() >= 400 && !warned) {
                warned = true;
                try {
                    plugin.getLogger().warning("Discord command log: channel " + channel
                            + " returned HTTP " + res.statusCode()
                            + " — check the bot can View Channel + Send Messages there.");
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // ---------- formatting ----------

    private static String ts() {
        return LocalDateTime.now().format(TS);
    }

    /** "[ADMIN] " (already bracketed) or "" when the player has no rank.
     * Cached for a few seconds: a command macro must not hit LuckPerms per line. */
    private String tag(Player p) {
        try {
            UUID id = p.getUniqueId();
            long now = System.currentTimeMillis();
            Long at = tagCacheAt.get(id);
            if (at != null && now - at < 10_000L && tagCache.containsKey(id)) {
                return tagCache.get(id);
            }
            String t = DiscordWebhook.cleanTag(plugin.getRanks().fullTagRaw(p));
            String out = (t == null || t.isEmpty()) ? "" : t + " ";
            tagCache.put(id, out);
            tagCacheAt.put(id, now);
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    /** bots.yml names, reloaded at most once a minute. */
    private boolean isBot(Player p) {
        try {
            long now = System.currentTimeMillis();
            if (now - botNamesAt > 60_000L) {
                botNames = BotNames.names(plugin);
                botNamesAt = now;
            }
            return botNames.contains(p.getName().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static String name(Player p) {
        try { return DiscordWebhook.safeName(p.getName()); } catch (Exception e) { return "?"; }
    }

    private static String cleanName(String s) {
        try {
            return DiscordWebhook.safeName(SECTION.matcher(s == null ? "?" : s).replaceAll(""));
        } catch (Exception e) { return "?"; }
    }

    /** Normalise "  /minecraft:enchant  " -> "minecraft:enchant"; strips § codes. */
    private static String clean(String raw) {
        if (raw == null) return "";
        String t = SECTION.matcher(raw).replaceAll("").trim();
        while (t.startsWith("/")) t = t.substring(1).trim();
        t = t.replaceAll("\\s+", " ");
        if (t.length() > 400) t = t.substring(0, 399) + "…";
        return t;
    }

    /** "minecraft:enchant" / "bukkit:help" -> "enchant" / "help" (lowercase). */
    private static String base(String token) {
        if (token == null) return "";
        String t = token.trim();
        int colon = t.indexOf(':');
        if (colon >= 0) t = t.substring(colon + 1);
        return t.toLowerCase(Locale.ROOT);
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
