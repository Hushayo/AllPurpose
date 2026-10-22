package com.hush.allpurpose;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Smooth rank-tag animation.
 *
 * <p>Every tick (configurable) the phase is derived from wall-clock time and the
 * gradient of each opt-in rank is hue-rotated by {@code phase * 360°}. Because the
 * hue is periodic, phase 1.0 equals phase 0.0 — there is no visible loop jump —
 * and at 20 Hz each character only changes by a fraction of a degree per update,
 * so there are no frames to see.
 *
 * <p>Only the live tab list / nametag can animate. A chat message is rendered once
 * when it is sent, so its prefix is necessarily a snapshot.
 */
public final class RankAnimation {

    private final AllPurpose plugin;
    private BukkitTask task;
    private final Set<String> ranks = new HashSet<>();
    private long cycleMs = 10_000L;

    public RankAnimation(AllPurpose plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads config and (re)starts the ticker. Safe to call on reload. */
    public synchronized void reload() {
        stop();
        ranks.clear();
        if (!plugin.getConfig().getBoolean("rank-animation.enabled", false)) return;

        cycleMs = Math.max(2_000L, plugin.getConfig().getLong("rank-animation.cycle-seconds", 10L) * 1000L);
        for (String name : plugin.getConfig().getStringList("rank-animation.ranks")) {
            if (name == null || name.trim().isEmpty()) continue;
            String key = plugin.getRanks().findKey(name.trim());
            if (key != null) ranks.add(TextUtil.norm(key));
        }
        if (ranks.isEmpty()) return;

        long ticks = Math.max(1L, plugin.getConfig().getLong("rank-animation.interval-ticks", 1L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    public synchronized void stop() {
        if (task != null) {
            try { task.cancel(); } catch (Exception ignored) {}
            task = null;
        }
    }

    private void tick() {
        try {
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            if (online.isEmpty()) return;

            double phase = (System.currentTimeMillis() % cycleMs) / (double) cycleMs;
            Map<String, String> cache = new HashMap<>(4);
            for (Player p : online) {
                String key = plugin.getRanks().playerRankKey(p);
                if (key == null || !ranks.contains(TextUtil.norm(key))) continue;
                String prefix = cache.computeIfAbsent(key, k -> animate(k, phase));
                if (prefix != null) plugin.getNametag().applyAnimatedPrefix(p, prefix);
            }
        } catch (Exception ignored) {
            // never let one bad frame kill the ticker
        }
    }

    /** Re-renders a rank's stored gradient at the given phase, or returns it unchanged. */
    private String animate(String key, double phase) {
        String raw = plugin.getRanks().prefixOf(key);
        if (raw == null) return null;

        int open = raw.indexOf("<gradient:");
        int tagEnd = open < 0 ? -1 : raw.indexOf('>', open);
        int close = tagEnd < 0 ? -1 : raw.indexOf("</gradient>", tagEnd);
        if (close < 0) return raw;

        String spec = raw.substring(open + 10, tagEnd);
        int colon = spec.indexOf(':');
        if (colon < 0) return raw;

        String h1 = hex(spec.substring(0, colon));
        String h2 = hex(spec.substring(colon + 1));
        if (h1 == null || h2 == null) return raw;

        String inner = raw.substring(tagEnd + 1, close);
        return TextUtil.gradientAnimated(inner, h1, h2, phase);
    }

    private static String hex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return null;
        }
        return s.toUpperCase(Locale.ROOT);
    }
}
