package com.hush.allpurpose;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldedit.bukkit.BukkitAdapter;

/**
 * Combat tag + combat-log detection (phases 1-3 of the MD plan).
 *
 * <p>State is in-memory for the seconds-long tag, and in combat-log.yml for the
 * things that must survive a disconnect: pending shame ceremonies + the
 * per-player offence count. The ceremony itself lives in {@link ShameCeremony}.
 */
public class CombatManager {

    /** One tagged player: who they are fighting and when the tag lapses. */
    public static final class State {
        public final UUID opponent;
        public final String opponentName;
        public final long startedAt;
        public long expiresAt;

        State(UUID opponent, String opponentName, long startedAt, long expiresAt) {
            this.opponent = opponent;
            this.opponentName = opponentName;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
        }

        public long remainingMillis() {
            return Math.max(0L, expiresAt - System.currentTimeMillis());
        }

        public int remainingSeconds() {
            return (int) Math.max(0L, (remainingMillis() + 999L) / 1000L);
        }
    }

    /** A logged-out player waiting for their ceremony on next join. */
    private static final class Pending {
        final String name;
        final int offence;
        final long since;

        Pending(String name, int offence, long since) {
            this.name = name;
            this.offence = offence;
            this.since = since;
        }
    }

    private final AllPurpose plugin;
    private final ShameCeremony ceremony;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Integer> offences = new HashMap<>();
    private final Set<UUID> timeoutGrace = new HashSet<>();
    private final List<String> blockedCommands = new ArrayList<>();
    private final List<String> exemptWorlds = new ArrayList<>();

    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask tickTask;

    private boolean enabled = true;
    private boolean punishOnQuit = true;
    private boolean blockPearls = true;
    private boolean blockEnderChests = true;
    private boolean shameEnabled = true;
    private boolean shameInvulnerable = true;
    private boolean shamePing = true;
    private boolean shameEffects = true;
    private boolean shameBossBar = true;
    private boolean discordAlerts = true;
    private int tagSeconds = 15;
    private int maxTagSeconds = 30;
    private int timeoutGraceSeconds = 5;
    private int shameBaseSeconds = 15;
    private int shameSecondSeconds = 30;
    private int shameThirdSeconds = 60;
    private int shameBaseFish = 10;
    private int shameFishStep = 2;
    private int shameMaxFish = 16;
    private String bypassPermission = "allpurpose.combat.bypass";
    private String shameBroadcast = "&c{player} combat logged and is now being fished at.";

    public CombatManager(AllPurpose plugin) {
        this.plugin = plugin;
        this.ceremony = new ShameCeremony(plugin, this);
    }

    public ShameCeremony getCeremony() {
        return ceremony;
    }

    public void boot() {
        reloadSettings();
        loadData();
        ceremony.sweepOrphans();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        ceremony.shutdown();
        states.clear();
        saveData();
    }

    public void reloadSettings() {
        try {
            enabled = plugin.getConfig().getBoolean("combat-log.enabled", true);
            tagSeconds = Math.max(5, plugin.getConfig().getInt("combat-log.tag-seconds", 15));
            maxTagSeconds = Math.max(tagSeconds, plugin.getConfig().getInt("combat-log.max-tag-seconds", 30));
            punishOnQuit = plugin.getConfig().getBoolean("combat-log.punish-on-quit", true);
            timeoutGraceSeconds = Math.max(0, plugin.getConfig().getInt("combat-log.timeout-grace-seconds", 5));
            blockPearls = plugin.getConfig().getBoolean("combat-log.block-ender-pearls", true);
            blockEnderChests = plugin.getConfig().getBoolean("combat-log.block-ender-chests", true);
            shameEnabled = plugin.getConfig().getBoolean("combat-log.shame.enabled", true);
            shameInvulnerable = plugin.getConfig().getBoolean("combat-log.shame.invulnerable", true);
            shamePing = plugin.getConfig().getBoolean("combat-log.shame.staff-ping", true);
            shameEffects = plugin.getConfig().getBoolean("combat-log.shame.effects", true);
            shameBossBar = plugin.getConfig().getBoolean("combat-log.shame.boss-bar", true);
            discordAlerts = plugin.getConfig().getBoolean("combat-log.discord-alerts", true);
            shameBaseSeconds = Math.max(5, plugin.getConfig().getInt("combat-log.shame.base-seconds", 15));
            shameSecondSeconds = Math.max(shameBaseSeconds, plugin.getConfig().getInt("combat-log.shame.second-seconds", 30));
            shameThirdSeconds = Math.max(shameSecondSeconds, plugin.getConfig().getInt("combat-log.shame.third-seconds", 60));
            shameBaseFish = Math.max(3, plugin.getConfig().getInt("combat-log.shame.base-mobs",
                    plugin.getConfig().getInt("combat-log.shame.base-fish", 10)));
            shameFishStep = Math.max(0, plugin.getConfig().getInt("combat-log.shame.mobs-per-offence",
                    plugin.getConfig().getInt("combat-log.shame.fish-per-offence", 2)));
            shameMaxFish = Math.max(shameBaseFish, plugin.getConfig().getInt("combat-log.shame.max-mobs",
                    plugin.getConfig().getInt("combat-log.shame.max-fish", 16)));
            bypassPermission = plugin.getConfig().getString("combat-log.bypass-permission", "allpurpose.combat.bypass");
            shameBroadcast = plugin.getConfig().getString("combat-log.shame.broadcast",
                    "&c{player} combat logged and is now being fished at.");

            blockedCommands.clear();
            for (String s : plugin.getConfig().getStringList("combat-log.block-commands")) {
                if (s == null) continue;
                String v = s.trim().toLowerCase(Locale.ROOT);
                if (v.startsWith("/")) v = v.substring(1);
                if (!v.isEmpty()) blockedCommands.add(v);
            }
            exemptWorlds.clear();
            for (String s : plugin.getConfig().getStringList("combat-log.exempt-worlds")) {
                if (s != null && !s.trim().isEmpty()) exemptWorlds.add(s.trim().toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Combat-log config read failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Persistence (combat-log.yml)
    // ------------------------------------------------------------------

    private void loadData() {
        dataFile = new File(plugin.getDataFolder(), "combat-log.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        pending.clear();
        offences.clear();
        try {
            ConfigurationSection os = data.getConfigurationSection("offences");
            if (os != null) {
                for (String k : os.getKeys(false)) {
                    try {
                        offences.put(UUID.fromString(k), Math.max(0, os.getInt(k)));
                    } catch (Exception ignored) {}
                }
            }
            ConfigurationSection ps = data.getConfigurationSection("pending");
            if (ps != null) {
                for (String k : ps.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(k);
                        pending.put(id, new Pending(
                                ps.getString(k + ".name", "?"),
                                Math.max(1, ps.getInt(k + ".offence", 1)),
                                ps.getLong(k + ".since", System.currentTimeMillis())));
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Combat-log data read failed: " + e.getMessage());
        }
    }

    private void saveData() {
        try {
            if (data == null) return;
            data.set("offences", null);
            data.set("pending", null);
            for (Map.Entry<UUID, Integer> e : offences.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    data.set("offences." + e.getKey(), e.getValue());
                }
            }
            for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
                String base = "pending." + e.getKey();
                data.set(base + ".name", e.getValue().name);
                data.set(base + ".offence", e.getValue().offence);
                data.set(base + ".since", e.getValue().since);
            }
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Combat-log save failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Tick / tag lifecycle
    // ------------------------------------------------------------------

    private void tick() {
        if (states.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> entry = it.next();
            State s = entry.getValue();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            if (now >= s.expiresAt) {
                it.remove();
                p.sendActionBar(Component.text("All clear", NamedTextColor.GREEN));
                continue;
            }
            int left = s.remainingSeconds();
            p.sendActionBar(Component.text("In combat: " + left + "s", NamedTextColor.RED));
        }
    }

    /** Tags both players. Called from the damage listener once per hit pair. */
    public void tag(Player a, Player b) {
        if (!enabled || a == null || b == null || a.equals(b)) return;
        if (!canTag(a) || !canTag(b)) return;
        long now = System.currentTimeMillis();
        tagOne(a, b, now);
        tagOne(b, a, now);
    }

    private void tagOne(Player p, Player other, long now) {
        State old = states.get(p.getUniqueId());
        long started = old != null ? old.startedAt : now;
        CombatTagEvent ev = new CombatTagEvent(p, other, tagSeconds);
        try {
            Bukkit.getPluginManager().callEvent(ev);
        } catch (Exception ignored) {}
        if (ev.isCancelled()) return;
        long expires = Math.min(now + tagSeconds * 1000L, started + maxTagSeconds * 1000L);
        states.put(p.getUniqueId(), new State(other.getUniqueId(), other.getName(), started, expires));
        p.sendActionBar(Component.text("In combat: " + tagSeconds + "s", NamedTextColor.RED));
    }

    public boolean canTag(Player p) {
        if (p == null || !p.isOnline()) return false;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (p.hasPermission(bypassPermission)) return false;
        World w = p.getWorld();
        if (w == null) return false;
        if (exemptWorlds.contains(w.getName().toLowerCase(Locale.ROOT))) return false;
        if (!w.getPVP()) return false;
        return pvpAllowed(p.getLocation());
    }

    /** WorldGuard PvP flag (no region = allowed). Degrades open if WG is missing. */
    private boolean pvpAllowed(Location loc) {
        try {
            if (loc == null || loc.getWorld() == null) return true;
            if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return true;
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            return query.testState(BukkitAdapter.adapt(loc), (LocalPlayer) null, Flags.PVP);
        } catch (Throwable t) {
            return true;
        }
    }

    public void handleDamage(EntityDamageByEntityEvent e) {
        if (!enabled || e == null || e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(e);
        if (attacker == null || attacker.equals(victim)) return;
        switch (e.getCause()) {
            case ENTITY_ATTACK:
            case ENTITY_SWEEP_ATTACK:
            case PROJECTILE:
            case MAGIC:
            case THORNS:
            case BLOCK_EXPLOSION:
            case ENTITY_EXPLOSION:
                break;
            default:
                return;
        }
        tag(victim, attacker);
    }

    private Player resolveAttacker(EntityDamageByEntityEvent e) {
        org.bukkit.entity.Entity damager = e.getDamager();
        if (damager instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt
                && tnt.getSource() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.AreaEffectCloud cloud
                && cloud.getSource() instanceof Player p) return p;
        return null;
    }

    public void untag(UUID id, boolean notify) {
        State removed = states.remove(id);
        if (removed != null && notify) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.sendActionBar(Component.text("All clear", NamedTextColor.GREEN));
            }
        }
    }

    // ------------------------------------------------------------------
    // Combat log (quit)
    // ------------------------------------------------------------------

    public void handleQuit(Player p, PlayerQuitEvent.QuitReason reason) {
        if (!enabled || p == null) return;
        State s = states.remove(p.getUniqueId());
        if (s == null) return;
        if (Bukkit.isStopping()) return;
        if (reason == PlayerQuitEvent.QuitReason.KICKED
                || reason == PlayerQuitEvent.QuitReason.ERRONEOUS_STATE) {
            return;
        }
        if (reason == PlayerQuitEvent.QuitReason.TIMED_OUT) {
            // A timeout is usually the router, not an escape. Give the client a
            // short grace to reconnect before recording it as a log.
            final UUID id = p.getUniqueId();
            timeoutGrace.add(id);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                timeoutGrace.remove(id);
                if (Bukkit.getPlayer(id) == null) {
                    recordLog(p, s, false);
                }
            }, Math.max(1, timeoutGraceSeconds) * 20L);
            return;
        }
        recordLog(p, s, true);
    }

    private void recordLog(Player p, State s, boolean dropItems) {
        UUID id = p.getUniqueId();
        int offence = offences.merge(id, 1, Integer::sum);
        int remaining = Math.max(0, (int) (s.remainingMillis() / 1000L));
        if (dropItems && punishOnQuit) dropInventory(p);
        pending.put(id, new Pending(p.getName(), offence, System.currentTimeMillis()));
        saveData();

        try {
            Bukkit.getPluginManager().callEvent(new CombatLogEvent(p, s.opponentName, offence, remaining));
        } catch (Exception ignored) {}

        if (shameEnabled) {
            String raw = shameBroadcast
                    .replace("{player}", p.getName())
                    .replace("{opponent}", s.opponentName == null ? "?" : s.opponentName)
                    .replace("{offence}", String.valueOf(offence));
            Component msg = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(raw);
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(msg);
            }
        }

        if (shamePing && offence >= 3) {
            Component ping = Component.text("[Shame] " + p.getName()
                    + " has combat logged " + offence + " times.", NamedTextColor.DARK_RED);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("allpurpose.shame.admin")) online.sendMessage(ping);
            }
        }

        try {
            DiscordWebhook wh = plugin.getWebhook();
            if (discordAlerts && wh != null && wh.wants("combat")) {
                wh.send("[CombatLog] " + p.getName() + " logged out in combat vs "
                        + (s.opponentName == null ? "?" : s.opponentName)
                        + " (" + remaining + "s left, offence #" + offence + ")");
            }
        } catch (Exception ignored) {}
    }

    /** Kill-on-quit v1: drop everything where they vanished, then kill them. */
    private void dropInventory(Player p) {
        try {
            Location loc = p.getLocation().clone();
            PlayerInventory inv = p.getInventory();
            for (ItemStack it : inv.getContents()) {
                if (it != null && !it.getType().isAir()) {
                    try {
                        loc.getWorld().dropItemNaturally(loc, it);
                    } catch (Exception ignored) {}
                }
            }
            inv.clear();
            try {
                p.setHealth(0.0D);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            plugin.getLogger().warning("Combat-log item drop failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Pending / offences
    // ------------------------------------------------------------------

    /** Returns the offence number for a pending ceremony and clears it. */
    public int consumePending(UUID id) {
        Pending pl = pending.remove(id);
        if (pl == null) return 0;
        saveData();
        return pl.offence;
    }

    public void restorePending(Player p, int offence) {
        if (p == null) return;
        restorePending(p.getUniqueId(), p.getName(), offence);
    }

    public void restorePending(UUID id, String name, int offence) {
        if (id == null) return;
        pending.put(id, new Pending(name == null ? "?" : name,
                Math.max(1, offence), System.currentTimeMillis()));
        saveData();
    }

    public int offence(UUID id) {
        return id == null ? 0 : offences.getOrDefault(id, 0);
    }

    public void clear(UUID id) {
        if (id == null) return;
        states.remove(id);
        pending.remove(id);
        offences.remove(id);
        timeoutGrace.remove(id);
        saveData();
    }

    public State state(UUID id) {
        return id == null ? null : states.get(id);
    }

    public boolean isTagged(UUID id) {
        return id != null && states.containsKey(id);
    }

    public boolean isInGrace(UUID id) {
        return id != null && timeoutGrace.contains(id);
    }

    // ------------------------------------------------------------------
    // Settings / helpers
    // ------------------------------------------------------------------

    public boolean isBlockedCommand(String base) {
        if (base == null) return false;
        String b = base.trim().toLowerCase(Locale.ROOT);
        if (b.startsWith("/")) b = b.substring(1);
        return blockedCommands.contains(b);
    }

    public boolean enabled() { return enabled; }
    public boolean punishOnQuit() { return punishOnQuit; }
    public boolean blockPearls() { return blockPearls; }
    public boolean blockEnderChests() { return blockEnderChests; }
    public boolean shameEnabled() { return shameEnabled; }
    public boolean shameInvulnerable() { return shameInvulnerable; }
    public boolean shamePing() { return shamePing; }
    public boolean shameEffects() { return shameEffects; }
    public boolean shameBossBar() { return shameBossBar; }
    public int tagSeconds() { return tagSeconds; }
    public String bypassPermission() { return bypassPermission; }

    public int shameSeconds(int offence) {
        if (offence <= 1) return shameBaseSeconds;
        if (offence == 2) return shameSecondSeconds;
        return shameThirdSeconds;
    }

    public int shameMobCount(int offence) {
        long count = (long) shameBaseFish + (long) shameFishStep * Math.max(0, offence - 1);
        return (int) Math.min(shameMaxFish, Math.max(0L, count));
    }
}
