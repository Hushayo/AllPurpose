package com.hush.allpurpose;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Instrument;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * Phase 4: the shame ceremony. On their next join a combat logger is frozen in
 * place, ringed with fish, title-carded and serenaded by a descending bass line
 * until the timer runs out. Escalates with repeat offences (15/30/60s).
 */
public class ShameCeremony implements Listener {

    private static final class Session {
        final UUID id;
        final String name;
        final Location anchor;
        final int offence;
        final boolean test;
        final long endAt;
        final boolean wasInvulnerable;
        final List<UUID> fish = new ArrayList<>();
        BukkitTask task;
        int noteStep;

        Session(UUID id, String name, Location anchor, int offence, boolean test,
                long endAt, boolean wasInvulnerable) {
            this.id = id;
            this.name = name;
            this.anchor = anchor;
            this.offence = offence;
            this.test = test;
            this.endAt = endAt;
            this.wasInvulnerable = wasInvulnerable;
        }
    }

    private final AllPurpose plugin;
    private final CombatManager combat;
    private final NamespacedKey fishKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ShameCeremony(AllPurpose plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        this.fishKey = new NamespacedKey(plugin, "shame_fish");
    }

    public boolean isInCeremony(UUID id) {
        return id != null && sessions.containsKey(id);
    }

    public void start(Player p, int offence, boolean test) {
        if (p == null || !p.isOnline()) return;
        if (!combat.shameEnabled() && !test) return;
        if (sessions.containsKey(p.getUniqueId())) return;
        int seconds = combat.shameSeconds(offence);
        Location anchor = p.getLocation().clone();
        boolean wasInvulnerable = p.isInvulnerable();
        if (combat.shameInvulnerable()) p.setInvulnerable(true);
        Session s = new Session(p.getUniqueId(), p.getName(), anchor, Math.max(1, offence), test,
                System.currentTimeMillis() + seconds * 1000L, wasInvulnerable);
        sessions.put(p.getUniqueId(), s);
        spawnFish(s, combat.shameFish(offence));
        showTitle(p);
        broadcast(p, offence, test);
        p.sendMessage(Component.text("You combat logged. Stay still - the fish are watching.",
                NamedTextColor.RED));
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 10L);
    }

    /** Called by the admin command: end early, keep the pending/offence state. */
    public void stop(UUID id) {
        Session s = id == null ? null : sessions.get(id);
        if (s != null) finish(s, false, false, true);
    }

    public void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (s.task != null) s.task.cancel();
            removeFish(s);
            restoreInvulnerable(s);
        }
        sessions.clear();
    }

    public void sweepOrphans() {
        try {
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    try {
                        if (e.getPersistentDataContainer().has(fishKey, PersistentDataType.BYTE)) {
                            e.remove();
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Shame fish sweep failed: " + e.getMessage());
        }
    }

    private void tick(Session s) {
        Player p = Bukkit.getPlayer(s.id);
        if (p == null || !p.isOnline()) {
            finish(s, false, true, false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= s.endAt) {
            finish(s, true, false, true);
            return;
        }
        // Backup freeze: the move listener handles rotation, this catches
        // teleports/knockback that bypass a cancelled move.
        try {
            Location loc = p.getLocation();
            if (!loc.getWorld().equals(s.anchor.getWorld())
                    || loc.distanceSquared(s.anchor) > 0.05D) {
                Location lock = s.anchor.clone();
                lock.setYaw(loc.getYaw());
                lock.setPitch(loc.getPitch());
                p.teleport(lock);
            }
        } catch (Exception ignored) {}
        int left = (int) Math.max(0L, (s.endAt - now + 999L) / 1000L);
        p.sendActionBar(Component.text("Shame: " + left + "s", NamedTextColor.RED));
        playNotes(s, p);
    }

    private void finish(Session s, boolean completed, boolean restorePending, boolean message) {
        if (s.task != null) {
            s.task.cancel();
            s.task = null;
        }
        sessions.remove(s.id);
        removeFish(s);
        Player p = Bukkit.getPlayer(s.id);
        if (p != null && p.isOnline()) {
            restoreInvulnerable(s);
            if (message) {
                p.sendActionBar(Component.text(completed
                        ? "The fish forgive you. For now."
                        : "The fish will remember this.", NamedTextColor.GRAY));
                p.sendMessage(Component.text(completed
                        ? "The fish forgive you. For now."
                        : "The fish will remember this.", NamedTextColor.GRAY));
            }
        }
        if (restorePending && !s.test) {
            combat.restorePending(s.id, s.name, s.offence);
        }
    }

    private void restoreInvulnerable(Session s) {
        try {
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && p.isOnline()) p.setInvulnerable(s.wasInvulnerable);
        } catch (Exception ignored) {}
    }

    private void spawnFish(Session s, int count) {
        World w = s.anchor.getWorld();
        if (w == null || count <= 0) return;
        EntityType[] types = {
                EntityType.COD, EntityType.SALMON, EntityType.TROPICAL_FISH, EntityType.PUFFERFISH
        };
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0D / count) * i;
            Location loc = s.anchor.clone().add(Math.cos(angle) * 2.4D, 0.1D, Math.sin(angle) * 2.4D);
            try {
                Entity e = w.spawnEntity(loc, types[i % types.length]);
                e.setPersistent(false);
                e.setGravity(false);
                e.getPersistentDataContainer().set(fishKey, PersistentDataType.BYTE, (byte) 1);
                if (e instanceof LivingEntity le) {
                    le.setAI(false);
                    le.setInvulnerable(true);
                    le.setSilent(true);
                }
                s.fish.add(e.getUniqueId());
            } catch (Exception ignored) {}
        }
    }

    private void removeFish(Session s) {
        for (UUID id : s.fish) {
            try {
                Entity e = Bukkit.getEntity(id);
                if (e != null && e.isValid()) e.remove();
            } catch (Exception ignored) {}
        }
        s.fish.clear();
        // Fallback: catches fish whose UUID lookup failed after a world reload.
        try {
            World w = s.anchor.getWorld();
            if (w == null) return;
            for (Entity e : w.getNearbyEntities(s.anchor, 8, 8, 8)) {
                try {
                    if (e.getPersistentDataContainer().has(fishKey, PersistentDataType.BYTE)) e.remove();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void playNotes(Session s, Player p) {
        Note.Tone[] tones = {
                Note.Tone.G, Note.Tone.F, Note.Tone.E, Note.Tone.D,
                Note.Tone.C, Note.Tone.D, Note.Tone.E, Note.Tone.F
        };
        Note note = Note.natural(0, tones[s.noteStep % tones.length]);
        s.noteStep++;
        try {
            for (Player near : s.anchor.getWorld().getPlayers()) {
                if (near.getLocation().distanceSquared(s.anchor) <= 1600.0D) {
                    near.playNote(s.anchor, Instrument.BASS_GUITAR, note);
                }
            }
        } catch (Exception ignored) {}
    }

    private void showTitle(Player p) {
        try {
            Component sub = Component.text("the fish are disappointed in you", NamedTextColor.GRAY);
            p.showTitle(Title.title(
                    Component.text("YOU COMBAT LOGGED", NamedTextColor.RED),
                    sub,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))));
        } catch (Exception ignored) {}
    }

    private void broadcast(Player p, int offence, boolean test) {
        try {
            String raw = plugin.getConfig().getString("combat-log.shame.broadcast",
                    "&c{player} combat logged and is now being fished at.");
            raw = raw.replace("{player}", p.getName())
                    .replace("{offence}", String.valueOf(offence));
            if (test) raw = "[test] " + raw;
            Component msg = LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
            for (Player online : Bukkit.getOnlinePlayers()) online.sendMessage(msg);
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        Location to = e.getTo();
        if (to == null) return;
        Location from = e.getFrom();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        Location lock = s.anchor.clone();
        lock.setYaw(to.getYaw());
        lock.setPitch(to.getPitch());
        e.setTo(lock);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(s, false, true, false);
    }
}
