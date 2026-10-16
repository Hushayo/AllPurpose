package com.hush.allpurpose;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * LuckPerms temp-group expiry notifier: tells a player when a temporary
 * parent group they held has expired.
 *
 * No compile-time dependency on LuckPerms: everything goes through reflection
 * (LuckPermsProvider -> UserManager -> User -> Nodes), mirroring JailManager's
 * console-command approach but read-only. If LuckPerms is absent the notifier
 * stays silent.
 *
 * How it works: every check interval we snapshot each online player's TEMP
 * inheritance groups. When a previously-seen temp group vanishes entirely
 * (not even a permanent grant of the same name remains), the player gets the
 * configured message. Snapshots persist to lp-expiry.yml so an expiry that
 * happens while offline fires on next join.
 *
 * Config (all read per-use, graceful defaults so live config needs no change):
 *   lp-expiry-enabled: true
 *   lp-expiry-check-seconds: 60
 *   lp-expiry-notify-on-join: true
 *   lp-expiry-watch-groups: []   (empty = all temp groups)
 *   lp-expiry-message: "&cYour rank &f{group} &chas expired!"
 */
public class LpExpiryNotifier {

    private final AllPurpose plugin;
    private final File file;
    private YamlConfiguration cfg;

    // UUID -> temp group names (lowercase) last seen holding.
    private final Map<UUID, Set<String>> known = new HashMap<>();
    // True once we have loaded the persisted snapshot (first poll/join seeds silently).
    private boolean seeded = false;
    private boolean lpMissingWarned = false;

    // Cached reflection handles (LuckPerms API present on the server, not in our lib).
    private volatile Object luckPerms;
    private volatile Object userManager;
    private volatile Method mGetUser;
    private volatile boolean lpResolved = false;

    public LpExpiryNotifier(AllPurpose plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lp-expiry.yml");
        try {
            plugin.getDataFolder().mkdirs();
            if (!file.exists()) {
                try { file.createNewFile(); } catch (Exception ignored) {}
            }
            cfg = YamlConfiguration.loadConfiguration(file);
        } catch (Exception e) {
            cfg = new YamlConfiguration();
        }
        loadPersisted();
    }

    public boolean isEnabled() {
        try { return plugin.getConfig().getBoolean("lp-expiry-enabled", true); }
        catch (Exception e) { return true; }
    }

    private long checkSeconds() {
        try { return Math.max(15L, plugin.getConfig().getLong("lp-expiry-check-seconds", 60)); }
        catch (Exception e) { return 60L; }
    }

    private boolean notifyOnJoin() {
        try { return plugin.getConfig().getBoolean("lp-expiry-notify-on-join", true); }
        catch (Exception e) { return true; }
    }

    private Set<String> watchGroups() {
        Set<String> out = new HashSet<>();
        try {
            List<String> l = plugin.getConfig().getStringList("lp-expiry-watch-groups");
            if (l != null) {
                for (String g : l) {
                    if (g != null && !g.trim().isEmpty()) out.add(TextUtil.norm(g.trim()));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String messageTemplate() {
        try {
            String s = plugin.getConfig().getString("lp-expiry-message",
                    "&cYour rank &f{group} &chas expired!");
            if (s == null || s.isEmpty()) return "&cYour rank &f{group} &chas expired!";
            return s;
        } catch (Exception e) {
            return "&cYour rank &f{group} &chas expired!";
        }
    }

    private boolean isWatched(String groupNorm, Set<String> watch) {
        try {
            if (watch == null || watch.isEmpty()) return true;
            return watch.contains(TextUtil.norm(groupNorm));
        } catch (Exception e) { return true; }
    }

    // ---------- persistence ----------

    private void loadPersisted() {
        try {
            known.clear();
            if (cfg == null) return;
            if (cfg.getConfigurationSection("known") == null) return;
            for (String k : cfg.getConfigurationSection("known").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    List<String> l = cfg.getStringList("known." + k);
                    Set<String> s = new HashSet<>();
                    if (l != null) {
                        for (String g : l) {
                            if (g != null && !g.trim().isEmpty()) s.add(TextUtil.norm(g.trim()));
                        }
                    }
                    known.put(id, s);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private synchronized void savePersisted() {
        try {
            if (cfg == null) cfg = new YamlConfiguration();
            cfg.set("known", null);
            for (Map.Entry<UUID, Set<String>> e : known.entrySet()) {
                try {
                    cfg.set("known." + e.getKey().toString(), new ArrayList<>(e.getValue()));
                } catch (Exception ignored) {}
            }
            cfg.save(file);
        } catch (IOException ignored) {
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        try { savePersisted(); } catch (Exception ignored) {}
    }

    // ---------- LuckPerms reflection ----------

    /** Resolve LuckPerms API handles once. Returns false when LP is absent. */
    private boolean resolveLp() {
        if (lpResolved) return luckPerms != null && userManager != null && mGetUser != null;
        lpResolved = true;
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Method mGet = provider.getMethod("get");
            Object lp;
            try { lp = mGet.invoke(null); }
            catch (Exception e) { return false; }
            if (lp == null) return false;
            Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
            if (um == null) return false;
            Method getUser = null;
            for (Method m : um.getClass().getMethods()) {
                if (m.getName().equals("getUser") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == UUID.class) {
                    getUser = m;
                    break;
                }
            }
            if (getUser == null) return false;
            luckPerms = lp;
            userManager = um;
            mGetUser = getUser;
            return true;
        } catch (ClassNotFoundException e) {
            if (!lpMissingWarned) {
                lpMissingWarned = true;
                try { plugin.getLogger().info("[LpExpiry] LuckPerms API not found - notifier idle."); }
                catch (Exception ignored) {}
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Temp + permanent inheritance group snapshot for one player. Null when LP/user unavailable. */
    private static final class GroupView {
        final Set<String> temp = new HashSet<>();
        final Set<String> all = new HashSet<>();
    }

    @SuppressWarnings("unchecked")
    private GroupView currentGroups(UUID id) {
        try {
            if (id == null || !resolveLp()) return null;
            Object user;
            try { user = mGetUser.invoke(userManager, id); }
            catch (Exception e) { return null; }
            if (user == null) return null; // not loaded yet (join race) - retry next poll
            Collection<?> nodes;
            try {
                Method mNodes = null;
                for (Method m : user.getClass().getMethods()) {
                    if (m.getName().equals("getNodes") && m.getParameterCount() == 0) {
                        mNodes = m;
                        break;
                    }
                }
                if (mNodes == null) return null;
                Object o = mNodes.invoke(user);
                if (!(o instanceof Collection)) return null;
                nodes = (Collection<?>) o;
            } catch (Exception e) { return null; }
            GroupView v = new GroupView();
            for (Object node : nodes) {
                if (node == null) continue;
                String group = null;
                try {
                    Method mGroup = node.getClass().getMethod("getGroupName");
                    Object g = mGroup.invoke(node);
                    if (g != null) group = g.toString();
                } catch (NoSuchMethodException nsme) {
                    // Fallback: raw key like "group.vip".
                    try {
                        Method mKey = node.getClass().getMethod("getKey");
                        Object k = mKey.invoke(node);
                        if (k != null) {
                            String ks = k.toString();
                            if (ks.regionMatches(true, 0, "group.", 0, 6) && ks.length() > 6) {
                                group = ks.substring(6);
                            } else continue;
                        } else continue;
                    } catch (Exception ignored) { continue; }
                } catch (Exception ignored) { continue; }
                if (group == null || group.trim().isEmpty()) continue;
                String norm = TextUtil.norm(group.trim());
                boolean temp = false;
                try {
                    Method mHas = node.getClass().getMethod("hasExpiry");
                    Object hv = mHas.invoke(node);
                    temp = hv instanceof Boolean && (Boolean) hv;
                } catch (Exception ignored) { temp = false; }
                if (temp) {
                    // Already-expired but not yet pruned: treat as gone.
                    try {
                        Method mExpired = node.getClass().getMethod("hasExpired");
                        Object ev = mExpired.invoke(node);
                        if (ev instanceof Boolean && (Boolean) ev) {
                            continue;
                        }
                    } catch (NoSuchMethodException ignored) {
                    } catch (Exception ignored) {}
                }
                v.all.add(norm);
                if (temp) v.temp.add(norm);
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyExpired(Player p, String groupNorm) {
        try {
            if (p == null || groupNorm == null) return;
            String tpl = messageTemplate();
            String name;
            try { name = p.getName(); } catch (Exception e) { name = groupNorm; }
            String msg = tpl.replace("{group}", groupNorm).replace("{player}", name == null ? "?" : name);
            try { p.sendMessage(TextUtil.legacy(msg)); } catch (Exception ignored) {}
            try {
                plugin.getLogger().info("[LpExpiry] " + name + " group " + groupNorm + " expired.");
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /**
     * Reconcile one player against the last snapshot. First sight seeds silently
     * (no false "expired" on plugin install); returns true when the snapshot changed.
     */
    private boolean reconcile(Player p, boolean allowNotify) {
        try {
            if (p == null) return false;
            UUID id = p.getUniqueId();
            GroupView cur = currentGroups(id);
            if (cur == null) return false; // LP down or user not loaded - keep old snapshot
            Set<String> watch = watchGroups();
            Set<String> prev;
            synchronized (this) {
                prev = known.get(id);
                if (prev == null) {
                    known.put(id, new HashSet<>(cur.temp));
                    savePersisted();
                    return true;
                }
                prev = new HashSet<>(prev);
            }
            // Expired = was temp, watched, and now holds NO grant of that group at all
            // (a temp->permanent upgrade keeps the name in cur.all, so no false alarm).
            List<String> expired = new ArrayList<>();
            for (String g : prev) {
                if (!isWatched(g, watch)) continue;
                if (!cur.all.contains(g)) expired.add(g);
            }
            boolean changed = false;
            synchronized (this) {
                Set<String> next = new HashSet<>(cur.temp);
                if (!next.equals(known.get(id))) {
                    known.put(id, next);
                    changed = true;
                }
            }
            if (changed) savePersisted();
            // Notify even when the temp set only shrank by expiry (changed=true);
            // when unwatched groups shift, expired stays empty so nothing fires.
            if (allowNotify && !expired.isEmpty() && p.isOnline()) {
                java.util.Collections.sort(expired);
                for (String g : expired) notifyExpired(p, g);
            }
            return changed;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Periodic sweep: online players only. Offline expiries fire on next join. */
    public void pollOnce() {
        try {
            if (!isEnabled()) return;
            if (!resolveLp()) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { reconcile(p, true); } catch (Exception ignored) {}
            }
            if (!seeded) seeded = true;
        } catch (Exception ignored) {}
    }

    public void startPoller() {
        try {
            long period = Math.max(15L, checkSeconds()) * 20L;
            Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() {
                    try { pollOnce(); } catch (Exception ignored) {}
                }
            }, 200L, period);
        } catch (Exception ignored) {}
    }

    /** Join path: LP loads the user a few ticks after login, so check late. */
    public void handleJoin(final Player p) {
        try {
            if (p == null) return;
            if (!isEnabled() || !notifyOnJoin()) {
                // Still seed the snapshot so the poller has a baseline.
                try {
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override public void run() {
                            try {
                                if (p.isOnline()) reconcile(p, false);
                            } catch (Exception ignored) {}
                        }
                    }, 100L);
                } catch (Exception ignored) {}
                return;
            }
            try {
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override public void run() {
                        try {
                            if (p.isOnline()) reconcile(p, true);
                        } catch (Exception ignored) {}
                        // Second pass for slow LP loads (user was null on first try).
                        try {
                            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                                @Override public void run() {
                                    try {
                                        if (p.isOnline()) reconcile(p, true);
                                    } catch (Exception ignored) {}
                                }
                            }, 600L);
                        } catch (Exception ignored) {}
                    }
                }, 100L);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public void handleQuit(Player p) {
        try {
            if (p == null) return;
            // Persist the last live view so an offline expiry is caught on next join.
            try { reconcile(p, false); } catch (Exception ignored) {}
            savePersisted();
        } catch (Exception ignored) {}
    }

    /** For diagnostics: what we last saw for this player (lowercase temp groups). */
    public Set<String> lastSeen(UUID id) {
        try {
            synchronized (this) {
                Set<String> s = known.get(id);
                return s == null ? new HashSet<String>() : new HashSet<>(s);
            }
        } catch (Exception e) { return new HashSet<String>(); }
    }
}
