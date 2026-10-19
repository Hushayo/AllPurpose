package com.hush.allpurpose;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * /apbridge exec &lt;id&gt; &lt;mode&gt; &lt;command...&gt; - run a command and capture
 * its full output for the minecraft-bridge MCP tool, including the plugins a
 * plain RCON call cannot see (LuckPerms, CoreProtect, WorldGuard `rg list`).
 *
 * WHAT WAS ACTUALLY WRONG (verified live 2026-10-19, Paper 26.1.2)
 * 1. Paper builds a vanilla CommandSourceStack for dispatch and hands the
 *    command the CONSOLE sender, whatever CommandSender object we pass in:
 *    a custom (proxy) ConsoleCommandSender gets its output redirected to the
 *    real console ("[Essentials] CONSOLE issued server command: ..." appears
 *    in the log, our sender sees nothing), and a proxy Player throws
 *    "Cannot make &lt;sender&gt; a vanilla command listener". So there is NO
 *    sender-side interception to be had - a first version of this feature
 *    captured 0 lines for every command because of exactly that.
 * 2. Plugins answer RCON differently because RCON's reply is flushed when the
 *    command call returns. Two consequences, both observed: plugins that log
 *    their output (LuckPerms logs "[LP] > User Info: ...", CoreProtect and
 *    WorldGuard log too) are invisible to RCON because RCON only ever carries
 *    messages addressed to the RCON sender, and plugins that DO address the
 *    sender can still answer late (the socket was answered already).
 *
 * WHAT THIS COMMAND DOES INSTEAD
 * The console log is the one channel that sees everything, races nothing, and
 * survives for the whole command. So: log a unique begin marker, dispatch the
 * command AS THE CONSOLE, wait until the log file stops growing, log an end
 * marker, and copy the log lines between the two markers out of
 * logs/latest.log into plugins/AllPurpose/bridge/&lt;id&gt;.txt (+ &lt;id&gt;.json
 * meta, written last, so the bridge knows when the pair is complete). The
 * bridge polls that file over SFTP; its RCON reply is only the short ack.
 *
 * MODES (the MCP tool's `method` argument)
 * - console       : FIRST CHOICE. Dispatch as the server console + log window.
 * - player:&lt;name&gt; : EXPERIMENTAL. Dispatch as the REAL online player
 *                   &lt;name&gt; (their identity and permissions - the point is
 *                   commands that refuse a console sender). HARD LIMIT: output
 *                   that goes only to that player's chat cannot be read, since
 *                   nothing has a client connection; only what the command
 *                   also logs is captured. The player really does receive the
 *                   output, so use it on a bot/volunteer, not a bystander.
 *
 * KNOWN LIMITS
 * - Log lines written during the window by anything else (another plugin, a
 *   join/quit) are included: the window is bounded by the markers, not
 *   filtered by origin.
 * - Our own "[APBridge] ..." markers and the dispatched command's
 *   "issued server command:" echo are stripped.
 * - A command that only prints to a player sender, and logs nothing, yields an
 *   empty payload. The meta says so (warning) rather than pretending success.
 *
 * CONFIG (all optional; the defaults below apply on an old config.yml)
 *   bridge:
 *     enabled: true
 *     allow-player-mode: true     # the experimental player:&lt;name&gt; mode
 *     min-wait-ms: 1000           # never finish before this (late output)
 *     quiet-ms: 600               # ...then finish once the log stops growing
 *     max-wait-ms: 8000           # hard cap per call
 *     max-bytes: 262144           # payload cap
 *     keep-minutes: 60            # captures pruned on the next call
 *
 * Console/RCON only: a player must never be able to make the server run an
 * arbitrary command through this.
 */
public class BridgeExecCommand implements CommandExecutor {

    private static final String PERM = "allpurpose.bridge";
    /** Bridge-generated ids only: keeps the write path free of traversal. */
    private static final String ID_OK = "[A-Za-z0-9_-]{1,40}";
    private static final String MARK = "[APBridge] ";
    private static final String BEGIN = MARK + "begin ";
    private static final String END = MARK + "end ";
    /** "[03:41:00] [Server thread/INFO]: " - the log's own framing. */
    private static final Pattern LOG_PREFIX = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[^\\]]+\\]: ");
    private static final String MODE_CONSOLE = "console";
    private static final String MODE_PLAYER_PREFIX = "player:";

    private final AllPurpose plugin;

    public BridgeExecCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            return handle(sender, label, args);
        } catch (Throwable t) {
            try {
                sender.sendMessage("§c[bridge] failed: " + shortErr(t));
            } catch (Exception ignored) {}
            return true;
        }
    }

    private boolean handle(CommandSender sender, String label, String[] args) {
        // Console/RCON only. NOTE: on this Paper API RemoteConsoleCommandSender
        // does NOT extend ConsoleCommandSender (verified with javap), so both
        // are checked; anything else (players, command blocks) is refused.
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("§c/" + label + " is console/RCON only.");
            return true;
        }
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage("§cNo permission (" + PERM + ").");
            return true;
        }
        if (!on("bridge.enabled", true)) {
            sender.sendMessage("§c[bridge] disabled (config bridge.enabled: false).");
            return true;
        }
        if (args.length == 0 || !"exec".equals(TextUtil.norm(args[0]))) {
            help(sender, label);
            return true;
        }
        if (args.length < 4) {
            usage(sender, label);
            return true;
        }

        final String id = args[1];
        if (!id.matches(ID_OK)) {
            sender.sendMessage("§c[bridge] bad id (allowed: A-Z a-z 0-9 _ -, max 40).");
            return true;
        }
        final String modeArg = TextUtil.norm(args[2]);
        final String command = TextUtil.join(args, 3).trim().replaceFirst("^/+", "");
        if (command.isEmpty()) {
            usage(sender, label);
            return true;
        }
        // Never re-enter ourselves: that would nest capture windows.
        String head = TextUtil.norm(command.split("\\s+")[0]);
        if (head.equals("apbridge") || head.equals("bridgeexec") || head.endsWith(":apbridge")) {
            sender.sendMessage("§c[bridge] refusing to run /apbridge from itself.");
            return true;
        }

        final List<String> warnings = new ArrayList<String>();
        final CommandSender target;
        final String mode;
        if (modeArg.startsWith(MODE_PLAYER_PREFIX)) {
            if (!on("bridge.allow-player-mode", true)) {
                fail(sender, id, modeArg, command, "player mode is disabled (bridge.allow-player-mode: false)");
                return true;
            }
            String name = modeArg.substring(MODE_PLAYER_PREFIX.length());
            Player real = name.isEmpty() ? null : Bukkit.getPlayerExact(name);
            if (real == null || !real.isOnline()) {
                // Not fatal for the tool: the bridge falls back to console mode.
                fail(sender, id, modeArg, command, "player '" + name + "' is not online (player mode needs a real online player)");
                return true;
            }
            target = real;
            mode = MODE_PLAYER_PREFIX + real.getName();
            warnings.add("player mode: output also went to " + real.getName() + "'s chat, and only output that is logged can be captured");
        } else if (modeArg.equals(MODE_CONSOLE)) {
            target = Bukkit.getConsoleSender();
            mode = MODE_CONSOLE;
        } else {
            sender.sendMessage("§c[bridge] mode must be §fconsole §cor §fplayer:<name>§c.");
            return true;
        }

        final File log = logFile();
        if (log == null || !log.isFile()) {
            fail(sender, id, mode, command, "cannot locate logs/latest.log");
            return true;
        }

        // Ack FIRST: the RCON reply is flushed when this handler returns, so
        // the payload has to travel by file, not by reply.
        sender.sendMessage("§7[bridge] §f" + id + " §7mode=§f" + mode + " §7running - capture: §fplugins/AllPurpose/bridge/" + id + ".json");

        final String fId = id, fMode = mode, fCommand = command;
        // Dispatch on the main thread: RCON may call us off it, and command
        // bodies (and Bukkit.dispatchCommand itself) assume the main thread.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                capture(fId, fMode, fCommand, target, log, warnings);
            }
        });
        return true;
    }

    /** Marker, dispatch, wait for the log to go quiet, end marker, copy the window. */
    private void capture(final String id, final String mode, final String command,
            final CommandSender target, final File log, final List<String> warnings) {
        final long from = log.length();
        String error = null;
        try {
            Bukkit.getLogger().info(BEGIN + id);
        } catch (Exception ignored) {}
        final long dispatchedAt = System.currentTimeMillis();
        try {
            Bukkit.dispatchCommand(target, command);
        } catch (Throwable t) {
            error = shortErr(t);
        }

        final long quiet = Math.max(150L, configLong("bridge.quiet-ms", 600L));
        final long minWait = Math.max(quiet, configLong("bridge.min-wait-ms", 1000L));
        final long maxWait = Math.max(minWait + 500L, configLong("bridge.max-wait-ms", 8000L));
        final long[] lastLen = { from };
        final long[] lastChange = { dispatchedAt };
        final String[] err = { error };
        final BukkitTask[] self = new BukkitTask[1];

        self[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long len = log.length();
                if (len != lastLen[0]) {
                    lastLen[0] = len;
                    lastChange[0] = now;
                }
                boolean minDone = now - dispatchedAt >= minWait;
                boolean quietDone = minDone && now - lastChange[0] >= quiet;
                boolean hardDone = now - dispatchedAt >= maxWait;
                if (!quietDone && !hardDone) return;
                try {
                    self[0].cancel();
                } catch (Exception ignored) {}
                // One tick later: give a buffered log appender time to flush
                // before the end marker and the window read.
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override
                    public void run() {
                        finish(id, mode, command, log, from, warnings, err[0],
                                hardDone && !quietDone ? "max-wait" : "quiet");
                    }
                }, 3L);
            }
        }, 4L, 4L);
    }

    private void finish(String id, String mode, String command, File log, long from,
            List<String> warnings, String error, String reason) {
        try {
            Bukkit.getLogger().info(END + id);
        } catch (Exception ignored) {}
        List<String> lines = readWindow(log, from, log.length(), id);
        if (lines.isEmpty() && error == null) {
            warnings.add("no logged output in the window - the command may print only to its sender (try method=console from a console-capable plugin, or read the plugin's own files)");
        }
        int max = (int) Math.max(1024L, configLong("bridge.max-bytes", 262144L));
        String payload = String.join("\n", lines);
        boolean truncated = false;
        if (payload.length() > max) {
            payload = payload.substring(0, max);
            truncated = true;
            warnings.add("payload truncated at " + max + " chars");
        }
        try {
            writeCapture(id, mode, command, payload, lines.size(), error, warnings, reason, truncated);
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Bridge] " + id + " capture write failed: " + e); } catch (Exception ignored) {}
            return;
        }
        try {
            plugin.getLogger().info("[Bridge] " + id + " mode=" + mode + " lines=" + lines.size()
                    + " reason=" + reason + (error != null ? " ERROR " + error : ""));
        } catch (Exception ignored) {}
    }

    /**
     * The log lines between our begin/end markers, with the log's own
     * "[time] [thread/LEVEL]: " prefix and our plumbing lines removed.
     */
    private List<String> readWindow(File log, long from, long to, String id) {
        List<String> out = new ArrayList<String>();
        try {
            long len = log.length();
            if (to > len) to = len;
            if (from < 0) from = 0;
            if (to <= from) return out;
            // Read the whole window plus headroom for the log prefixes; keep the
            // tail if a chatty server wrote more than that.
            long cap = Math.max(4096L, configLong("bridge.max-bytes", 262144L)) + 65536L;
            boolean capped = false;
            long startAt = from;
            if (to - startAt > cap) {
                startAt = to - cap;
                capped = true;
            }
            byte[] buf;
            try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
                raf.seek(startAt);
                buf = new byte[(int) (to - startAt)];
                raf.readFully(buf);
            }
            int off = 0;
            if (capped || startAt > 0) {
                // The window can start mid-line (and mid-character): skip to the
                // first LF. A split UTF-8 sequence can only precede it.
                for (int i = 0; i < buf.length; i++) {
                    if (buf[i] == (byte) '\n') { off = i + 1; break; }
                }
            }
            String text = new String(buf, off, buf.length - off, StandardCharsets.UTF_8);
            boolean gated = text.contains(BEGIN + id);
            boolean inWindow = false;
            for (String raw : text.split("\n", -1)) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                if (line.contains(BEGIN + id)) { inWindow = true; continue; }
                if (line.contains(END + id)) break;
                if (line.contains(MARK)) continue;
                if (gated && !inWindow) continue;
                String stripped = LOG_PREFIX.matcher(line).replaceFirst("");
                if (stripped.contains("issued server command:")) continue;
                if (stripped.isEmpty()) continue;
                out.add(stripped);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Early failure (bad target, disabled mode): same files, status=error. */
    private void fail(CommandSender sender, String id, String mode, String command, String error) {
        try {
            writeCapture(id, mode, command, "", 0, error, new ArrayList<String>(), "not-run", false);
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Bridge] " + id + " meta write failed: " + e); } catch (Exception ignored) {}
        }
        sender.sendMessage("§7[bridge] §f" + id + " §cnot run: §f" + error);
    }

    private void writeCapture(String id, String mode, String command, String payload, int lineCount,
            String error, List<String> warnings, String reason, boolean truncated) throws Exception {
        File dir = new File(plugin.getDataFolder(), "bridge");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir.getPath());
        prune(dir);

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Path txt = new File(dir, id + ".txt").toPath();
        Path json = new File(dir, id + ".json").toPath();

        StringBuilder meta = new StringBuilder(256);
        meta.append("{\"id\":").append(jstr(id))
                .append(",\"mode\":").append(jstr(mode))
                .append(",\"command\":").append(jstr(command))
                .append(",\"status\":").append(jstr(error == null ? "ok" : "error"))
                .append(",\"error\":").append(error == null ? "null" : jstr(error))
                .append(",\"reason\":").append(jstr(reason))
                .append(",\"channel\":\"console-log\"")
                .append(",\"lines\":").append(lineCount)
                .append(",\"bytes\":").append(bytes.length)
                .append(",\"truncated\":").append(truncated)
                .append(",\"warnings\":[");
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) meta.append(',');
            meta.append(jstr(warnings.get(i)));
        }
        meta.append("],\"finishedAt\":").append(jstr(nowIso()))
                .append(",\"version\":").append(jstr(plugin.getDescription().getVersion()))
                .append('}');

        // .json last: its presence is what tells the bridge the pair is complete.
        writeAtomic(txt, bytes);
        writeAtomic(json, meta.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomic(Path target, byte[] data) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * &lt;server root&gt;/logs/latest.log. The root is the folder holding
     * server.properties; Bukkit.getWorldContainer() normally IS that folder,
     * but a parent walk plus server.properties' level-name is kept as fallback
     * (same lesson as the changelog datapack: never trust a world folder to be
     * the level folder on this Paper build).
     */
    private File logFile() {
        try {
            File container = Bukkit.getWorldContainer();
            File direct = new File(container, "logs/latest.log");
            if (direct.isFile()) return direct;
            try {
                File parent = container.getParentFile();
                if (parent != null) {
                    File up = new File(parent, "logs/latest.log");
                    if (up.isFile()) return up;
                }
            } catch (Exception ignored) {}
            try {
                File props = new File(container, "server.properties");
                if (props.isFile()) {
                    Properties p = new Properties();
                    try (java.io.InputStream in = new java.io.FileInputStream(props)) { p.load(in); }
                    String level = p.getProperty("level-name");
                    if (level != null && !level.trim().isEmpty()) {
                        File logs = new File(new File(container, level.trim()).getParentFile(), "logs/latest.log");
                        if (logs.isFile()) return logs;
                    }
                }
            } catch (Exception ignored) {}
            File dir = new File(System.getProperty("user.dir", "."), "logs/latest.log");
            if (dir.isFile()) return dir;
            return direct;
        } catch (Exception e) {
            return null;
        }
    }

    /** Keep the trailing window of captures only. */
    private void prune(File dir) {
        try {
            long keepMs = Math.max(1L, configLong("bridge.keep-minutes", 60L)) * 60000L;
            long cutoff = System.currentTimeMillis() - keepMs;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                try {
                    if (f.isFile() && f.lastModified() < cutoff) f.delete();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private boolean on(String key, boolean def) {
        try {
            return plugin.getConfig().getBoolean(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private long configLong(String key, long def) {
        try {
            long v = plugin.getConfig().getLong(key, def);
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§6§lBridge exec §7(bridge/RCON only)");
        sender.sendMessage("§e/" + label + " exec <id> console <command...> §7- run as console, capture the console log (use this first)");
        sender.sendMessage("§e/" + label + " exec <id> player:<name> <command...> §7- run as a real online player (experimental)");
        sender.sendMessage("§7Writes §fplugins/AllPurpose/bridge/<id>.txt §7+ §f.json§7.");
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage("§cUsage: /" + label + " exec <id> <console|player:<name>> <command...>");
    }

    private static String shortErr(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String nowIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String jstr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
