package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

/**
 * Floating shop labels, drawn ON DEMAND.
 *
 * A TextDisplay is an entity, so one per shop would put hundreds of tracked
 * entities (and hundreds of text packets) on the server the moment a shop
 * network grows. Instead this keeps a display alive ONLY for shops that have a
 * player nearby:
 *
 *   - spawn when a player comes within shop.label-radius
 *   - despawn when no player is within radius + shop.label-hysteresis
 *     (the buffer stops the label flickering on/off at the boundary)
 *   - hard cap of shop.label-max labels at once, nearest first
 *   - setViewRange() shrinks how far clients even render them
 *   - setPersistent(false) so a restart cannot leak orphans into the world
 *
 * Net cost scales with "shops near players", never with the total shop count.
 * Text is rebuilt only when it actually changes, so a stocked chest does not
 * re-serialize components every tick.
 */
public class ShopLabels {

    private static final String TAG = "allpurpose_shop";

    private final AllPurpose plugin;
    private final Map<String, TextDisplay> live = new HashMap<>();  // shop key -> display
    private final Map<String, String> shown = new HashMap<>();      // shop key -> rendered text
    private BukkitTask task;

    public ShopLabels(AllPurpose plugin) {
        this.plugin = plugin;
    }

    public void start() {
        sweepOrphans();
        ShopManager m = plugin.getShop();
        long period = m == null ? 20L : m.labelTicks();
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { try { tick(); } catch (Exception ignored) {} }
        }, period, period);
    }

    /** Nothing survives a restart (setPersistent(false)), but a build that used
     *  persistent displays would leave ghosts - kill every tagged one on boot. */
    public void sweepOrphans() {
        try {
            for (World w : Bukkit.getWorlds()) {
                for (Entity en : w.getEntities()) {
                    if (!(en instanceof TextDisplay)) continue;
                    try { if (en.getScoreboardTags().contains(TAG)) en.remove(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        try { if (task != null) task.cancel(); } catch (Exception ignored) {}
        removeAll();
    }

    /** Drop everything now (the periodic tick will rebuild what is still near). */
    public synchronized void removeAll() {
        for (TextDisplay td : live.values()) { try { td.remove(); } catch (Exception ignored) {} }
        live.clear();
        shown.clear();
    }

    /** Remove the label for one shop (called when a shop is deleted). */
    public synchronized void drop(String shopKey) {
        TextDisplay td = live.remove(shopKey);
        shown.remove(shopKey);
        if (td != null) { try { td.remove(); } catch (Exception ignored) {} }
    }

    // ---------- the periodic pass ----------

    private void tick() {
        ShopManager m = plugin.getShop();
        if (m == null) return;
        if (!m.enabled() || !m.labelsEnabled()) { if (!live.isEmpty()) removeAll(); return; }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        double r = m.labelRadius();
        double r2 = r * r;
        double rd = r + m.labelHysteresis();
        double rd2 = rd * rd;
        int cap = m.labelMax();

        // Candidates near someone, nearest first (so the cap keeps the closest).
        List<ShopManager.Shop> want = new ArrayList<>();
        Map<String, Double> dist = new HashMap<>();
        if (!players.isEmpty() && cap > 0) {
            for (ShopManager.Shop s : m.all()) {
                Location loc = s.location();
                if (loc == null || loc.getWorld() == null) continue;
                double best = nearest(players, loc);
                if (best < 0 || best > rd2) continue;
                dist.put(s.key(), best);
                if (best <= r2) want.add(s);
            }
            if (want.size() > 1) {
                java.util.Collections.sort(want, new java.util.Comparator<ShopManager.Shop>() {
                    @Override public int compare(ShopManager.Shop a, ShopManager.Shop b) {
                        Double da = dist.get(a.key()), db = dist.get(b.key());
                        return Double.compare(da == null ? Double.MAX_VALUE : da,
                                              db == null ? Double.MAX_VALUE : db);
                    }
                });
            }
        }
        Set<String> keep = new HashSet<>();
        int slots = Math.min(want.size(), Math.max(0, cap - live.size()));
        for (int i = 0; i < want.size(); i++) {
            ShopManager.Shop s = want.get(i);
            String k = s.key();
            boolean has = live.containsKey(k);
            if (has) { keep.add(k); continue; }
            if (slots <= 0) continue;      // over the cap: no new label for this one
            if (spawn(s) != null) { keep.add(k); slots--; }
        }

        // Drop labels whose shop vanished, fell out of range, or lost its cap slot.
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, TextDisplay> en : live.entrySet()) {
            String k = en.getKey();
            if (keep.contains(k)) continue;
            ShopManager.Shop s = m.byKey(k);
            if (s == null) { dead.add(k); continue; }
            Location loc = s.location();
            double d = loc == null ? -1 : nearest(players, loc);
            if (d < 0 || d > rd2) dead.add(k);
        }
        for (String k : dead) drop(k);

        // Enforce the cap even when live overflowed (radius shrank, players left).
        if (cap >= 0 && live.size() > cap) {
            List<String> byDist = new ArrayList<>(live.keySet());
            final Map<String, Double> d2 = new HashMap<>();
            for (String k : byDist) {
                ShopManager.Shop s = m.byKey(k);
                Location l = s == null ? null : s.location();
                d2.put(k, l == null ? Double.MAX_VALUE : nearest(players, l));
            }
            java.util.Collections.sort(byDist, new java.util.Comparator<String>() {
                @Override public int compare(String a, String b) {
                    Double da = d2.get(a), db = d2.get(b);
                    return Double.compare(da == null ? Double.MAX_VALUE : da, db == null ? Double.MAX_VALUE : db);
                }
            });
            while (live.size() > cap) drop(byDist.remove(byDist.size() - 1));
        }

        // Refresh the text that is still shown.
        for (Map.Entry<String, TextDisplay> en : live.entrySet()) {
            String k = en.getKey();
            ShopManager.Shop s = m.byKey(k);
            if (s == null) continue;
            String text = m.labelText(s);
            if (text.equals(shown.get(k))) continue;
            try { en.getValue().text(TextUtil.prefixComponent(text)); shown.put(k, text); }
            catch (Exception ignored) {}
        }
    }

    /** Squared distance to the nearest player in the same world, or -1. */
    private static double nearest(List<Player> players, Location loc) {
        double best = -1;
        for (Player p : players) {
            try {
                if (p == null || !p.isOnline()) continue;
                Location pl = p.getLocation();
                if (pl == null || pl.getWorld() == null || !pl.getWorld().equals(loc.getWorld())) continue;
                double d = pl.distanceSquared(loc);
                if (best < 0 || d < best) best = d;
            } catch (Exception ignored) {}
        }
        return best;
    }

    private TextDisplay spawn(ShopManager.Shop s) {
        try {
            Location loc = s.location();
            if (loc == null || loc.getWorld() == null) return null;
            World w = loc.getWorld();
            if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return null;
            Location at = loc.clone().add(0.5, 1.35, 0.5);
            Entity en = w.spawnEntity(at, EntityType.TEXT_DISPLAY);
            if (!(en instanceof TextDisplay)) { try { en.remove(); } catch (Exception ignored) {} return null; }
            TextDisplay td = (TextDisplay) en;
            td.setBillboard(Display.Billboard.CENTER);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            try { td.setDefaultBackground(false); } catch (Throwable ignored) {}
            td.setSeeThrough(false);
            td.setShadowed(true);
            td.setLineWidth(200);
            td.setInvulnerable(true);
            td.setPersistent(false);
            try { td.setViewRange(0.35f); } catch (Throwable ignored) {}
            try { td.addScoreboardTag(TAG); } catch (Exception ignored) {}
            String text = plugin.getShop().labelText(s);
            td.text(TextUtil.prefixComponent(text));
            live.put(s.key(), td);
            shown.put(s.key(), text);
            return td;
        } catch (Exception e) {
            return null;
        }
    }
}
