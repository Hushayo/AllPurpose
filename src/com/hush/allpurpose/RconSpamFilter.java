package com.hush.allpurpose;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Suppresses vanilla "Thread RCON Client /x.x.x.x started / shutting down"
 * INFO lines from console/log without disabling RCON itself.
 *
 * Also groups bot "X issued server command: /..." spam: one summary per
 * window like "[BotCmd] Name: /warp crystal (12x)" instead of a flood.
 *
 * Everything log4j is done via reflection so the build needs no log4j jar on
 * the compile classpath (paper-api does not ship log4j). At runtime Paper
 * provides org.apache.logging.log4j:LogManager + core Filter.
 */
public final class RconSpamFilter {

    private RconSpamFilter() {}

    private static Object installedFilter = null;
    private static Object loggerRef = null;

    // ---------- Bot command grouping state (lock-free for logging threads) ----------
    private static volatile Set<String> botNames = Collections.emptySet();
    private static volatile String botMode = "group";

    private static final class CountEntry {
        final String displayName;
        final String command;
        final AtomicInteger count;
        CountEntry(String displayName, String command, int first) {
            this.displayName = displayName;
            this.command = command;
            this.count = new AtomicInteger(first);
        }
    }

    private static final ConcurrentHashMap<String, CountEntry> counts = new ConcurrentHashMap<>();

    private static final Pattern CMD =
            Pattern.compile("^(.+?) issued server command: (/.+)\\s*$");

    /** group | hide | off (anything else behaves as group). */
    public static void setBotMode(String mode) {
        try {
            if (mode == null) { botMode = "group"; return; }
            String m = mode.trim().toLowerCase(Locale.ROOT);
            if (m.equals("hide") || m.equals("off") || m.equals("group")) botMode = m;
            else botMode = "group";
        } catch (Exception ignored) {}
    }

    /** Rebuild the bot-name set (registry + online AGENT rank). Main thread. */
    public static void refreshBotNames(AllPurpose plugin) {
        try {
            Set<String> s = new HashSet<>();
            try {
                for (String n : BotNames.names(plugin)) {
                    try {
                        if (n != null) s.add(n);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            try {
                String agentKey = plugin.getRanks().findKey("AGENT");
                if (agentKey != null) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        try {
                            String k = plugin.getRanks().playerRankKey(p);
                            if (k != null && k.equalsIgnoreCase(agentKey)) {
                                s.add(p.getName().toLowerCase(Locale.ROOT));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            botNames = s;
        } catch (Exception ignored) {}
    }

    /** Emit one summary line per name+command, then clear. Main thread. */
    public static void flush(AllPurpose plugin) {
        try {
            if (!"group".equalsIgnoreCase(botMode)) {
                counts.clear();
                return;
            }
            if (counts.isEmpty()) return;
            List<String> keys = new ArrayList<>(counts.keySet());
            Collections.sort(keys);
            for (String k : keys) {
                CountEntry e = counts.remove(k);
                if (e == null) continue;
                int n;
                try { n = e.count.get(); } catch (Exception ignored) { continue; }
                if (n <= 0) continue;
                try {
                    plugin.getLogger().info("[BotCmd] " + e.displayName + ": " + e.command
                            + (n > 1 ? " (" + n + "x)" : ""));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** True when the line was a bot command and the caller should DENY it. */
    private static boolean handleBotCommand(String text) {
        try {
            String m = botMode;
            if (m == null || m.equalsIgnoreCase("off")) return false;
            Matcher mt = CMD.matcher(text);
            if (!mt.matches()) return false;
            String name = mt.group(1).trim();
            String cmd = mt.group(2).trim();
            if (name.isEmpty() || cmd.isEmpty()) return false;
            Set<String> s = botNames;
            if (s == null || s.isEmpty()) return false;
            if (!s.contains(name.toLowerCase(Locale.ROOT))) return false;
            if (m.equalsIgnoreCase("hide")) return true;
            String key = name.toLowerCase(Locale.ROOT) + "" + cmd;
            counts.compute(key, (k, old) -> {
                if (old == null) return new CountEntry(name, cmd, 1);
                old.count.incrementAndGet();
                return old;
            });
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized void install(org.bukkit.plugin.java.JavaPlugin plugin) {
        if (installedFilter != null) return;
        try {
            Class<?> logManagerCls = Class.forName("org.apache.logging.log4j.LogManager");
            Method getRootLogger = logManagerCls.getMethod("getRootLogger");
            Object rootLogger = getRootLogger.invoke(null);

            Class<?> filterCls = Class.forName("org.apache.logging.log4j.core.Filter");
            Class<?> resultCls = Class.forName("org.apache.logging.log4j.core.Filter$Result");
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object deny = Enum.valueOf((Class<Enum>) resultCls.asSubclass(Enum.class), "DENY");
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object neutral = Enum.valueOf((Class<Enum>) resultCls.asSubclass(Enum.class), "NEUTRAL");
            final Object denyFinal = deny;
            final Object neutralFinal = neutral;

            Object filterProxy = Proxy.newProxyInstance(
                    filterCls.getClassLoader(),
                    new Class<?>[] { filterCls },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("filter".equals(name)) {
                            try {
                                String text = extractText(args);
                                if (text != null && isRconSpam(text)) return denyFinal;
                                if (text != null && handleBotCommand(text)) return denyFinal;
                            } catch (Exception ignored) {}
                            return neutralFinal;
                        }
                        if ("getOnMismatch".equals(name) || "getOnMatch".equals(name)) return neutralFinal;
                        if ("isStarted".equals(name)) return Boolean.TRUE;
                        if ("isStopped".equals(name)) return Boolean.FALSE;
                        if ("start".equals(name) || "stop".equals(name)) return null;
                        if ("toString".equals(name)) return "RconSpamFilter";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
                        Class<?> rt = method.getReturnType();
                        if (rt.getName().equals("org.apache.logging.log4j.core.Filter$Result")) return neutralFinal;
                        if (rt == void.class || rt == Void.class) return null;
                        if (rt == boolean.class || rt == Boolean.class) return Boolean.FALSE;
                        if (rt == int.class || rt == Integer.class) return 0;
                        if (rt == long.class || rt == Long.class) return 0L;
                        return null;
                    });

            Method addFilter = null;
            for (Method m : rootLogger.getClass().getMethods()) {
                if (m.getName().equals("addFilter") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(filterProxy.getClass())) {
                    addFilter = m;
                    break;
                }
            }
            if (addFilter == null) {
                addFilter = rootLogger.getClass().getMethod("addFilter", filterCls);
            }
            addFilter.invoke(rootLogger, filterProxy);

            installedFilter = filterProxy;
            loggerRef = rootLogger;
            plugin.getLogger().info("[AllPurpose] RCON console spam filter on (RCON stays enabled).");
        } catch (Exception e) {
            plugin.getLogger().warning("[AllPurpose] Could not install RCON spam filter: " + e);
        }
    }

    public static synchronized void uninstall() {
        if (installedFilter == null || loggerRef == null) return;
        try {
            // Core Logger has no public removeFilter on all versions; try best-effort.
            for (Method m : loggerRef.getClass().getMethods()) {
                if (m.getName().equals("removeFilter") && m.getParameterCount() == 1) {
                    try { m.invoke(loggerRef, installedFilter); } catch (Exception ignored) {}
                    break;
                }
            }
        } catch (Exception ignored) {}
        installedFilter = null;
        loggerRef = null;
    }

    private static String extractText(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            if (arg instanceof String) return (String) arg;
            if (arg instanceof CharSequence) return arg.toString();
            // LogEvent -> getMessage().getFormattedMessage()
            try {
                Method getMessage = arg.getClass().getMethod("getMessage");
                Object msgObj = getMessage.invoke(arg);
                if (msgObj == null) continue;
                try {
                    Method getFormatted = msgObj.getClass().getMethod("getFormattedMessage");
                    Object s = getFormatted.invoke(msgObj);
                    if (s != null) return s.toString();
                } catch (NoSuchMethodException nsme) {
                    return msgObj.toString();
                }
            } catch (NoSuchMethodException ignored) {
                // Not a LogEvent; only treat raw String params as messages.
                // (Logger objects etc. must NOT match via toString.)
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isRconSpam(String text) {
        // Exact vanilla lines: "Thread RCON Client /5.189.132.216 started"
        // and "... shutting down". Match narrowly so real errors still show.
        if (text.contains("RCON Client")) {
            if (text.contains("started") || text.contains("shutting down") || text.contains("Thread RCON")) return true;
        }
        // Essentials echoes every RCON command ("[Essentials] Rcon issued server
        // command: /list"). Polling bridges run read-only /list constantly, so
        // hide exactly that line; every other RCON command stays visible.
        try {
            String low = text.toLowerCase(Locale.ROOT);
            int i = low.indexOf("rcon issued server command:");
            if (i >= 0) {
                String after = low.substring(i + "rcon issued server command:".length()).trim();
                if (after.equals("/list") || after.startsWith("/list ")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
