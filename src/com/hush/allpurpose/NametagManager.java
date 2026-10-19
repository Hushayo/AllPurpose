package com.hush.allpurpose;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

public class NametagManager {

    private final AllPurpose plugin;

    // ---------- Nametag: rank tag + hearts + lifetime kills ----------
    private final java.util.Map<UUID, UUID> tagHolo = new java.util.HashMap<>();      // player -> TextDisplay
    private final java.util.Map<UUID, Location> tagHoloAt = new java.util.HashMap<>(); // player -> last synced spot
    private final java.util.Map<UUID, String> tagSentTab = new java.util.HashMap<>();  // player -> last pushed line
    private final java.util.Map<UUID, Long> tagPushedAt = new java.util.HashMap<>();   // player -> last push (ms)
    private final java.util.Map<UUID, String> tagSentHolo = new java.util.HashMap<>(); // player -> last holo text
    private static final String TAG_META = "allpurpose_tag";
    private final java.util.Set<String> tagErrors = new java.util.HashSet<>();

    public NametagManager(AllPurpose plugin) {
        this.plugin = plugin;
    }

    private void pushTab(Player p, String bracketedPrefix) {
        // TAB renders tablist/nametag as prefix + NAME + suffix, so a prefix left
        // on a colour code paints the player's name with it. Always hand it over
        // with a reset at the end.
        bracketedPrefix = endNameColour(bracketedPrefix);
        // TAB blocks playerListName changes, so push via its API (top priority). Reflection = no hard dependency.
        String[] apiClasses = {"com.plainvanilla.tab.api.TabAPI", "me.neznamy.tab.api.TabAPI"};
        String[] playerClasses = {"com.plainvanilla.tab.api.TabPlayer", "me.neznamy.tab.api.TabPlayer"};
        for (int i = 0; i < apiClasses.length; i++) {
            try {
                Class<?> tabAPI = Class.forName(apiClasses[i]);
                Class<?> tabPlayer = Class.forName(playerClasses[i]);
                Object api = tabAPI.getMethod("getInstance").invoke(null);
                Object tp = api.getClass().getMethod("getPlayer", java.util.UUID.class).invoke(api, p.getUniqueId());
                if (tp == null) return; // TAB hasn't loaded them yet; join task retries
                try {
                    Object mgr = api.getClass().getMethod("getTabListFormatManager").invoke(api);
                    if (mgr != null) mgr.getClass().getMethod("setPrefix", tabPlayer, String.class).invoke(mgr, tp, bracketedPrefix);
                } catch (Exception ignored) {}
                try {
                    Object mgr = api.getClass().getMethod("getNameTagManager").invoke(api);
                    if (mgr != null) mgr.getClass().getMethod("setPrefix", tabPlayer, String.class).invoke(mgr, tp, bracketedPrefix);
                } catch (Exception ignored) {}
                return;
            } catch (ClassNotFoundException e) {
                // try next package name
            } catch (Exception ignored) {
                return;
            }
        }
    }

    public void applyRank(Player p) {
        if (p == null) return;
        String tag = "";
        String raw = "";
        try { tag = plugin.getRanks().fullTag(p); } catch (Exception ignored) {}
        try { raw = plugin.getRanks().fullTagRaw(p); } catch (Exception ignored) {}
        if (tag == null) tag = "";
        if (raw == null) raw = "";
        if (tag.isEmpty() && raw.isEmpty()) return;
        if (tag.isEmpty()) {
            try { tag = TextUtil.legacy(raw); } catch (Exception ignored) {}
        }
        if (raw.isEmpty()) raw = tag;
        try {
            p.setDisplayName(endNameColour(tag) + p.getName());
            p.playerListName(TextUtil.prefixComponent(endNameColour(raw)).append(Component.text(p.getName())));
        } catch (Exception ignored) {}
        if (useTabLine()) {
            applyNametag(p); // owns the TAB prefix+suffix (tag, hearts, kills)
        } else {
            pushTab(p, tag);
        }
    }

    // ---------- Nametag / tabline: tag + hearts + lifetime kills ----------
    // Two renderers, selected by nametag.mode:
    //   tab      -> push "[TAG] Name | HP | Kills" through TAB's API (1.21+ client nametag, no entities)
    //   hologram -> float a TextDisplay above the head (works with or without TAB)
    //   both     -> both at once
    //   off      -> disabled
    // TAB owns the nametag when installed, so in "tab" mode we drive it via TAB's API and
    // fall back to a scoreboard team when TAB is missing.

    public String nametagMode() {
        try {
            String m = TextUtil.norm(plugin.getConfig().getString("nametag.mode", "tab"));
            if (m.equals("tab") || m.equals("hologram") || m.equals("both") || m.equals("off")) return m;
        } catch (Exception ignored) {}
        return "tab";
    }

    public boolean useTabLine() {
        String m = nametagMode();
        return m.equals("tab") || m.equals("both");
    }

    public boolean useHoloLine() {
        String m = nametagMode();
        return m.equals("hologram") || m.equals("both");
    }

    private String heartsOf(Player p) {
        try {
            return String.format(Locale.ROOT, "%.1f", p.getHealth() / 2.0);
        } catch (Exception e) {
            return "0";
        }
    }

    private double absorptionOf(Player p) {
        try {
            return Math.max(0.0, p.getAbsorptionAmount());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String absHeartsOf(Player p) {
        try {
            return String.format(Locale.ROOT, "%.1f", absorptionOf(p) / 2.0);
        } catch (Exception e) {
            return "0";
        }
    }

    private String totalHeartsOf(Player p) {
        try {
            double h;
            try { h = p.getHealth(); } catch (Exception e) { h = 0.0; }
            return String.format(Locale.ROOT, "%.1f", (h + absorptionOf(p)) / 2.0);
        } catch (Exception e) {
            return "0";
        }
    }

    private String hpOf(Player p) {
        try {
            return String.format(Locale.ROOT, "%.1f", p.getHealth());
        } catch (Exception e) {
            return "0";
        }
    }

    private String fill(String fmt, Player p) {
        if (fmt == null) return "";
        String out = fmt;
        String key = plugin.getRanks().playerRankKey(p);
        String rank = key == null ? "" : plugin.getRanks().displayName(key);
        String prefix = key == null ? "" : plugin.getRanks().prefixOf(key);
        out = out.replace("{rank}", rank);
        out = out.replace("{prefix}", prefix);
        out = out.replace("{name}", p.getName());
        out = out.replace("{hearts}", heartsOf(p));
        out = out.replace("{abs}", absHeartsOf(p));
        out = out.replace("{total}", totalHeartsOf(p));
        out = out.replace("{hp}", hpOf(p));
        out = out.replace("{maxhp}", String.format(Locale.ROOT, "%.1f", maxHealthOf(p)));
        out = out.replace("{kills}", String.valueOf(plugin.getLeaderboard().lbKills(p.getUniqueId())));
        out = out.replace("{deaths}", String.valueOf(plugin.getLeaderboard().lbDeaths(p.getUniqueId())));
        // {pingplain} must go first: "{ping}" would not match inside "{pingplain}"
        // (the next char is 'p', not '}'), but replacing the longer token first
        // keeps this robust if the set ever grows.
        out = out.replace("{pingplain}", pingOf(p));
        out = out.replace("{ping}", pingColored(p));
        return out;
    }

    /** Round-trip ping in milliseconds, or "?" if the server will not report it. */
    private String pingOf(Player p) {
        try {
            int ms = p.getPing();
            if (ms < 0) return "?";
            return String.valueOf(ms);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Ping with a colour band, so a laggy player is obvious at a glance. */
    private String pingColored(Player p) {
        try {
            int ms = p.getPing();
            if (ms < 0) return "&8?";
            long good, ok;
            try {
                good = plugin.getConfig().getLong("nametag.ping-good-ms", 60);
                ok = plugin.getConfig().getLong("nametag.ping-ok-ms", 120);
            } catch (Exception ex) {
                good = 60; ok = 120;
            }
            return (ms <= good ? "&a" : (ms <= ok ? "&e" : "&c")) + ms;
        } catch (Throwable t) {
            return "&8?";
        }
    }

    private double maxHealthOf(Player p) {
        try {
            org.bukkit.attribute.AttributeInstance ai = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (ai != null) return ai.getValue();
        } catch (Exception ignored) {}
        try { return p.getMaxHealth(); } catch (Exception e) { return 20.0; }
    }

    private String tabLine(Player p) {
        try {
            String tpl = plugin.getConfig().getString("nametag.tab-line", "{prefix} {name} &8| &c{hearts} &8| &7Kills: &f{kills}");
            // Keep the prefix's colour off the name in the no-TAB fallback too.
            if (tpl != null) tpl = tpl.replace("{prefix}", "{prefix}§r");
            return fill(tpl, p);
        } catch (Exception e) {
            return p.getName();
        }
    }

    /**
     * TAB renders prefix + name + suffix, so a prefix that ends on a colour code
     * paints the player's name in that colour (rank gradients are the usual
     * culprit: their last colour stays active). Every prefix handed over ends
     * with §r, which only stops the colour at the name.
     */
    private static String endNameColour(String prefix) {
        if (prefix == null || prefix.isEmpty()) return prefix;
        String s;
        try { s = TextUtil.legacy(prefix); } catch (Exception e) { s = prefix; }
        return s.endsWith("§r") ? s : s + "§r";
    }

    // Hologram shows the STATS ONLY. The vanilla nametag already renders "[TAG] Name"
    // underneath, so putting the name in here too would print it twice.
    // This is the renderer that works without TAB, so ping is offered here.
    // {total} = (health + absorption) / 2, so gapple / god-apple yellow hearts count.
    private String holoLine(Player p) {
        try {
            return fill(plugin.getConfig().getString("nametag.holo-line", "&c❤ {total} &8| &7Kills: &f{kills} &8| &7Ping: {ping}"), p);
        } catch (Exception e) {
            return "";
        }
    }

    private Component lineComponent(String line, Player p) {
        try {
            return TextUtil.prefixComponent(line);
        } catch (Exception e) {
            return Component.text(p.getName());
        }
    }

    // Any code path that clears or reorders TAB state must call this, otherwise the
    // "unchanged, skip" cache below will happily never re-send a wiped prefix.
    public void invalidateTag(Player p) {
        if (p == null) return;
        tagSentTab.remove(p.getUniqueId());
        tagPushedAt.remove(p.getUniqueId());
    }

    private long tagForceMs() {
        try {
            return Math.max(1000L, plugin.getConfig().getLong("nametag.force-refresh-seconds", 5) * 1000L);
        } catch (Exception e) {
            return 5000L;
        }
    }

    // Returns true if the line was actually pushed.
    private boolean pushTagLine(Player p) {
        if (p == null || !p.isOnline()) return false;
        if (!useTabLine()) return false;
        try {
            String prefix = fill(plugin.getConfig().getString("nametag.prefix", "[{prefix}] "), p);
            String suffix = fill(plugin.getConfig().getString("nametag.suffix", " &8| &c{hearts} &8| &7Kills: &f{kills} &8| &7Ping: {ping}"), p);
            prefix = endNameColour(prefix);
            String sig = prefix + "\n" + suffix;
            String prev = tagSentTab.get(p.getUniqueId());
            long last = tagPushedAt.containsKey(p.getUniqueId()) ? tagPushedAt.get(p.getUniqueId()) : 0L;
            boolean stale = (System.currentTimeMillis() - last) > tagForceMs();
            // Skip only when the text is identical AND we pushed recently. The staleness
            // re-push is what heals a prefix wiped by TAB or another plugin.
            if (sig.equals(prev) && !stale) return false;
            // TAB owns the nametag when installed: it renders prefix+name+suffix from its
            // own API and IGNORES the vanilla display name. So the stats must go in as a suffix.
            if (tabApiPresent()) {
                if (!pushTabFull(p, prefix, suffix)) return false;
            } else {
                p.setDisplayName(TextUtil.legacy(tabLine(p)));
                p.playerListName(TextUtil.prefixComponent(tabLine(p)));
            }
            tagSentTab.put(p.getUniqueId(), sig);
            tagPushedAt.put(p.getUniqueId(), System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            logTagError(p, e);
            return false;
        }
    }

    public void applyNametag(Player p) {
        pushTagLine(p);
    }

    private boolean tabApiPresent() {
        String[] apiClasses = {"com.plainvanilla.tab.api.TabAPI", "me.neznamy.tab.api.TabAPI"};
        for (String c : apiClasses) {
            try { Class.forName(c); return true; } catch (ClassNotFoundException ignored) {}
        }
        return false;
    }

    // ---------- Inactive tag: removed per owner request ----------
    // Kept as no-ops so PlayerListener still compiles; pushTagLine no longer
    // appends anything.
    public void noteJoin(Player p) { }

    public void noteQuit(Player p) { }

    public void noteMove(org.bukkit.event.player.PlayerMoveEvent e) { }

    public boolean inactiveEnabled() { return false; }

    private String inactiveTag(Player p) { return ""; }

    // TAB draws prefix + name + suffix, so the stats ride along as the suffix.
    private boolean pushTabFull(Player p, String prefix, String suffix) {
        String[] apiClasses = {"com.plainvanilla.tab.api.TabAPI", "me.neznamy.tab.api.TabAPI"};
        String[] playerClasses = {"com.plainvanilla.tab.api.TabPlayer", "me.neznamy.tab.api.TabPlayer"};
        for (int i = 0; i < apiClasses.length; i++) {
            try {
                Class<?> tabAPI = Class.forName(apiClasses[i]);
                Class<?> tabPlayer = Class.forName(playerClasses[i]);
                Object api = tabAPI.getMethod("getInstance").invoke(null);
                Object tp = api.getClass().getMethod("getPlayer", java.util.UUID.class).invoke(api, p.getUniqueId());
                if (tp == null) return false; // TAB hasn't registered them yet; the join retry handles it
                String[] getters = {"getTabListFormatManager", "getNameTagManager"};
                for (String g : getters) {
                    try {
                        Object mgr = api.getClass().getMethod(g).invoke(api);
                        if (mgr == null) continue;
                        try { mgr.getClass().getMethod("setPrefix", tabPlayer, String.class).invoke(mgr, tp, prefix); } catch (Exception ignored) {}
                        try { mgr.getClass().getMethod("setSuffix", tabPlayer, String.class).invoke(mgr, tp, suffix); } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                }
                return true;
            } catch (ClassNotFoundException e) {
                // try the next package name
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    // These paths used to swallow every exception, which is why a silent no-op went unnoticed.
    private void logTagError(Player p, Exception e) {
        try {
            String key = p.getUniqueId() + ":" + e.getClass().getSimpleName();
            if (tagErrors.add(key)) {
                plugin.getLogger().warning("Nametag push failed for " + p.getName() + ": " + e);
            }
        } catch (Exception ignored) {}
    }

    // ---------- Hologram nametag (works with or without TAB) ----------

    private TextDisplay tagEntity(Player p) {
        try {
            UUID id = tagHolo.get(p.getUniqueId());
            if (id == null) return null;
            Entity en = Bukkit.getEntity(id);
            return en instanceof TextDisplay ? (TextDisplay) en : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void spawnTagHolo(Player p) {
        if (!useHoloLine() || p == null || !p.isOnline()) return;
        try {
            removeTagHolo(p);
            Location at = p.getLocation().add(0.0, 2.35, 0.0);
            if (at.getWorld() == null) return;
            TextDisplay td = (TextDisplay) at.getWorld().spawnEntity(at, EntityType.TEXT_DISPLAY);
            td.setBillboard(Display.Billboard.CENTER);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setSeeThrough(false);
            td.setPersistent(false);
            td.setInvulnerable(true);
            td.setViewRange(1.0f);
            try { td.addScoreboardTag(TAG_META); } catch (Exception ignored) {}
            tagHolo.put(p.getUniqueId(), td.getUniqueId());
            tagHoloAt.put(p.getUniqueId(), at.clone());
            try { td.text(lineComponent(holoLine(p), p)); } catch (Exception ignored) {}
        } catch (Exception e) {
            plugin.getLogger().warning("Nametag hologram failed: " + e.getMessage());
        }
    }

    public void removeTagHolo(Player p) {
        if (p == null) return;
        try {
            TextDisplay td = tagEntity(p);
            if (td != null) td.remove();
        } catch (Exception ignored) {}
        tagHolo.remove(p.getUniqueId());
        tagHoloAt.remove(p.getUniqueId());
    }

    public void clearOrphanTagHolo() {
        // A /reload or crash can leave these behind; tag them with metadata and sweep on enable.
        try {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                for (Entity en : w.getEntities()) {
                    try {
                        if (en instanceof TextDisplay && en.getScoreboardTags().contains(TAG_META)) en.remove();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        tagHolo.clear();
        tagHoloAt.clear();
    }

    public void repaintTag(Entity en) {
        if (!useHoloLine() && !useTabLine()) return;
        if (!(en instanceof Player)) return;
        final Player p = (Player) en;
        // Health changes mid-tick; the text must be rebuilt on the main thread next tick.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                try {
                    if (useTabLine()) {
                        // Health moved, so the text is stale by definition: force a re-push.
                        tagPushedAt.remove(p.getUniqueId());
                        pushTagLine(p);
                    }
                    if (useHoloLine()) {
                        String line = holoLine(p);
                        tagSentHolo.put(p.getUniqueId(), line);
                        TextDisplay td = tagEntity(p);
                        if (td != null && td.isValid()) td.text(lineComponent(line, p));
                    }
                } catch (Exception e) {
                    logTagError(p, e);
                }
            }
        });
    }

    public void tickNametags() {
        if (!useHoloLine() && !useTabLine()) return;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (!useHoloLine()) {
                    // Mode switched away from holograms: take down any
                    // leftover TextDisplay instead of leaving it frozen.
                    if (tagHolo.containsKey(p.getUniqueId())) removeTagHolo(p);
                } else {
                    TextDisplay td = tagEntity(p);
                    if (td == null || !td.isValid()) {
                        spawnTagHolo(p);
                        continue;
                    }
                    Location at = p.getLocation().add(0.0, 2.35, 0.0);
                    Location last = tagHoloAt.get(p.getUniqueId());
                    boolean moved = last == null
                            || !last.getWorld().equals(at.getWorld())
                            || last.distanceSquared(at) > 0.09;
                    if (moved) {
                        td.teleport(at);
                        tagHoloAt.put(p.getUniqueId(), at.clone());
                    }
                    String line = holoLine(p);
                    String prev = tagSentHolo.get(p.getUniqueId());
                    if (!line.equals(prev)) {
                        tagSentHolo.put(p.getUniqueId(), line);
                        try { td.text(lineComponent(line, p)); } catch (Exception ignored) {}
                    }
                }
                if (useTabLine()) {
                    pushTagLine(p);
                }
                try { awardLagAdvancement(p); } catch (Exception ignored) {}
            } catch (Exception e) {
                logTagError(p, e);
            }
        }
    }

    // "Time Traveler" advancement: ping at/above ping-advancement-ms earns it.
    // Trigger is impossible, so only this code can ever grant it. One-time.
    private void awardLagAdvancement(Player p) {
        try {
            int threshold = Math.max(200, plugin.getConfig().getInt("ping-advancement-ms", 1000));
            int ms;
            try { ms = p.getPing(); } catch (Throwable t) { return; }
            if (ms < threshold) return;
            org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, "time_traveler");
            org.bukkit.advancement.Advancement adv = Bukkit.getAdvancement(key);
            if (adv == null) return;
            org.bukkit.advancement.AdvancementProgress prog = p.getAdvancementProgress(adv);
            if (prog.isDone()) return;
            for (String c : prog.getRemainingCriteria()) prog.awardCriteria(c);
        } catch (Exception ignored) {}
    }

    public void clearLook(Player p) {
        try {
            p.setDisplayName(p.getName());
            p.playerListName(Component.text(p.getName()));
        } catch (Exception ignored) {}
        pushTab(p, null);
        // pushTab(null) just wiped TAB's prefix, so the cache must forget it or the
        // "unchanged, skip" path would never put the tag back.
        invalidateTag(p);
    }
}
