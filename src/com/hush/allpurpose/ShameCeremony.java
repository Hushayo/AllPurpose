package com.hush.allpurpose;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

/**
 * Phase 4: the shame ceremony. On their next join a combat logger is frozen in
 * place while the mob tribunal convenes: a ring of villagers stare them down,
 * a pumpkin-headed judge floats overhead with a gavel, chickens orbit the
 * scene, a giant floating verdict reads GUILTY, lightning cracks and a layered
 * bass/bell/xylophone line plays. Escalates with repeat offences (15/30/60s).
 */
public class ShameCeremony implements Listener {

    private static final String[] DEFAULT_TAUNTS = {
            "the villagers voted. it was unanimous.",
            "the chickens saw everything.",
            "GUILTY. obviously.",
            "the judge is a pumpkin. he is not amused.",
            "skill issue, says the jury.",
            "the gavel has spoken.",
            "your PvP licence is under review.",
            "objection overruled. you logged out.",
            "the tribunal accepts bribes. you have none.",
            "case closed. case reopened. still guilty."
    };

    private static final String[] VERDICTS = {
            "GUILTY", "GUILTY?", "VERY GUILTY", "STILL GUILTY", "GUILTY (AGAIN)"
    };

    private static final Villager.Profession[] PROFESSIONS = {
            Villager.Profession.LIBRARIAN,
            Villager.Profession.CLERIC,
            Villager.Profession.BUTCHER,
            Villager.Profession.FARMER,
            Villager.Profession.NITWIT,
            Villager.Profession.WEAPONSMITH,
            Villager.Profession.CARTOGRAPHER,
            Villager.Profession.SHEPHERD
    };

    private static final class Session {
        final UUID id;
        final String name;
        final Location anchor;
        final int offence;
        final boolean test;
        final long startedAt;
        final long endAt;
        final boolean wasInvulnerable;
        final List<UUID> props = new ArrayList<>();
        final List<UUID> villagers = new ArrayList<>();
        final List<UUID> chickens = new ArrayList<>();
        final List<UUID> viewers = new ArrayList<>();
        UUID judge;
        UUID verdict;
        BossBar bar;
        BukkitTask task;
        int ticks;
        int noteIndex;
        int tauntIndex;
        int verdictIndex;

        Session(UUID id, String name, Location anchor, int offence, boolean test,
                long startedAt, long endAt, boolean wasInvulnerable) {
            this.id = id;
            this.name = name;
            this.anchor = anchor;
            this.offence = offence;
            this.test = test;
            this.startedAt = startedAt;
            this.endAt = endAt;
            this.wasInvulnerable = wasInvulnerable;
        }

        long totalMillis() {
            return Math.max(1L, endAt - startedAt);
        }
    }

    private final AllPurpose plugin;
    private final CombatManager combat;
    private final NamespacedKey propKey;
    private final NamespacedKey legacyFishKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ShameCeremony(AllPurpose plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        this.propKey = new NamespacedKey(plugin, "shame_prop");
        this.legacyFishKey = new NamespacedKey(plugin, "shame_fish");
    }

    public boolean isInCeremony(UUID id) {
        return id != null && sessions.containsKey(id);
    }

    public void start(Player p, int offence, boolean test) {
        if (p == null || !p.isOnline()) return;
        if (!combat.shameEnabled() && !test) return;
        if (sessions.containsKey(p.getUniqueId())) return;

        int seconds = combat.shameSeconds(offence);
        long now = System.currentTimeMillis();
        Location anchor = p.getLocation().clone();
        boolean wasInvulnerable = p.isInvulnerable();
        if (combat.shameInvulnerable()) p.setInvulnerable(true);

        Session s = new Session(p.getUniqueId(), p.getName(), anchor, Math.max(1, offence), test,
                now, now + seconds * 1000L, wasInvulnerable);
        sessions.put(p.getUniqueId(), s);

        if (combat.shameBossBar()) {
            BossBar bar = BossBar.bossBar(Component.text("PUBLIC HUMILIATION", NamedTextColor.RED),
                    1.0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
            s.bar = bar;
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getWorld() == anchor.getWorld()
                        && online.getLocation().distanceSquared(anchor) <= 48.0D * 48.0D) {
                    online.showBossBar(bar);
                    s.viewers.add(online.getUniqueId());
                }
            }
            if (!s.viewers.contains(p.getUniqueId())) {
                p.showBossBar(bar);
                s.viewers.add(p.getUniqueId());
            }
        }

        spawnTribunal(s, combat.shameMobCount(offence));
        if (combat.shameEffects()) introEffects(s, offence);
        showTitle(p, "GUILTY", "the mob has spoken", NamedTextColor.RED);
        broadcast(p, offence, test);
        p.sendMessage(Component.text("You combat logged. The tribunal is now in session.",
                NamedTextColor.RED));

        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 2L);
    }

    /** Called by the admin command: end early, keep the pending/offence state. */
    public void stop(UUID id) {
        Session s = id == null ? null : sessions.get(id);
        if (s != null) finish(s, false, false, true);
    }

    public void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (s.task != null) s.task.cancel();
            hideBar(s);
            removeProps(s, false);
            restoreInvulnerable(s);
        }
        sessions.clear();
    }

    public void sweepOrphans() {
        try {
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    try {
                        if (e.getPersistentDataContainer().has(propKey, PersistentDataType.BYTE)
                                || e.getPersistentDataContainer().has(legacyFishKey, PersistentDataType.BYTE)) {
                            e.remove();
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Shame prop sweep failed: " + e.getMessage());
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
        s.ticks++;
        keepFrozen(p, s);
        animateTribunal(s);

        int left = (int) Math.max(0L, (s.endAt - now + 999L) / 1000L);
        updateBar(s, left);
        if (s.ticks % 5 == 0) {
            p.sendActionBar(Component.text("ON TRIAL  " + left + "s", NamedTextColor.RED));
        }
        if (s.ticks % 10 == 0) playBeat(s);
        if (s.ticks % 20 == 0 && combat.shameEffects()) auraParticles(s, p);
        if (s.ticks % 80 == 0) nextVerdict(s);
        if (s.ticks % 100 == 0) {
            taunt(s, p);
            if (combat.shameEffects()) lightning(s);
        }
        if (left <= 5 && s.ticks % 20 == 0) heartbeat(s);
    }

    private void keepFrozen(Player p, Session s) {
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
    }

    // ------------------------------------------------------------------
    // Tribunal props
    // ------------------------------------------------------------------

    private void spawnTribunal(Session s, int count) {
        World w = s.anchor.getWorld();
        if (w == null) return;

        int villagers = Math.max(3, count);
        for (int i = 0; i < villagers; i++) {
            double angle = (Math.PI * 2.0D / villagers) * i;
            Location loc = s.anchor.clone().add(Math.cos(angle) * 3.4D, 0.1D, Math.sin(angle) * 3.4D);
            try {
                Villager v = w.spawn(loc, Villager.class);
                v.setProfession(PROFESSIONS[i % PROFESSIONS.length]);
                v.setVillagerLevel(2);
                v.setAI(false);
                v.setGravity(false);
                v.setInvulnerable(true);
                v.setSilent(true);
                v.setPersistent(false);
                tag(v);
                s.villagers.add(v.getUniqueId());
                s.props.add(v.getUniqueId());
            } catch (Exception ignored) {}
        }

        for (int i = 0; i < 5; i++) {
            double angle = (Math.PI * 2.0D / 5) * i;
            Location loc = s.anchor.clone().add(Math.cos(angle) * 4.6D, 1.1D, Math.sin(angle) * 4.6D);
            try {
                Chicken c = w.spawn(loc, Chicken.class);
                c.setAI(false);
                c.setGravity(false);
                c.setInvulnerable(true);
                c.setSilent(true);
                c.setPersistent(false);
                tag(c);
                s.chickens.add(c.getUniqueId());
                s.props.add(c.getUniqueId());
            } catch (Exception ignored) {}
        }

        try {
            ArmorStand judge = w.spawn(s.anchor.clone().add(0, 3.5D, 0), ArmorStand.class);
            judge.setGravity(false);
            judge.setInvulnerable(true);
            judge.setArms(true);
            judge.setBasePlate(false);
            judge.setSmall(false);
            judge.setPersistent(false);
            judge.getEquipment().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            judge.getEquipment().setItemInMainHand(new ItemStack(Material.STICK));
            tag(judge);
            s.judge = judge.getUniqueId();
            s.props.add(judge.getUniqueId());
        } catch (Exception ignored) {}

        try {
            TextDisplay verdict = w.spawn(s.anchor.clone().add(0, 4.8D, 0), TextDisplay.class);
            verdict.text(Component.text("GUILTY", NamedTextColor.RED, TextDecoration.BOLD));
            verdict.setBillboard(Display.Billboard.CENTER);
            verdict.setAlignment(TextDisplay.TextAlignment.CENTER);
            verdict.setShadowed(true);
            verdict.setSeeThrough(false);
            verdict.setTextOpacity((byte) -1);
            verdict.setBackgroundColor(Color.fromARGB(0x55000000));
            verdict.setViewRange(2.0f);
            verdict.setPersistent(false);
            tag(verdict);
            s.verdict = verdict.getUniqueId();
            s.props.add(verdict.getUniqueId());
        } catch (Exception ignored) {}
    }

    private void animateTribunal(Session s) {
        // Villagers slowly turn to follow the offender. No AI, no movement.
        for (UUID id : s.villagers) {
            try {
                Entity e = Bukkit.getEntity(id);
                if (e == null || !e.isValid()) continue;
                face(e, s.anchor.clone().add(0, 1.0D, 0));
                if (s.ticks % 20 == 0) {
                    e.getWorld().playSound(e.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.45f, 0.8f);
                }
            } catch (Exception ignored) {}
        }

        // Chickens orbit fast and low, flapping around the ring.
        int n = s.chickens.size();
        for (int i = 0; i < n; i++) {
            try {
                Entity e = Bukkit.getEntity(s.chickens.get(i));
                if (e == null || !e.isValid()) continue;
                double angle = (Math.PI * 2.0D / Math.max(1, n)) * i + s.ticks * 0.16D;
                double radius = 4.2D + Math.sin(s.ticks * 0.1D + i) * 0.5D;
                double height = 1.0D + Math.sin(s.ticks * 0.18D + i * 1.3D) * 0.7D;
                Location loc = s.anchor.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
                e.teleport(loc);
                face(e, s.anchor.clone().add(0, 1.0D, 0));
                if (s.ticks % 20 == 0) {
                    e.getWorld().spawnParticle(Particle.CLOUD, loc, 2, 0.1D, 0.1D, 0.1D, 0.01D);
                    e.getWorld().playSound(loc, Sound.ENTITY_CHICKEN_AMBIENT, 0.5f, 1.3f);
                }
            } catch (Exception ignored) {}
        }

        // Floating pumpkin judge bobs overhead and slams the gavel.
        if (s.judge != null) {
            try {
                Entity e = Bukkit.getEntity(s.judge);
                if (e != null && e.isValid()) {
                    Location loc = s.anchor.clone().add(0,
                            3.5D + Math.sin(s.ticks * 0.07D) * 0.3D, 0);
                    e.teleport(loc);
                    face(e, s.anchor.clone().add(0, 1.0D, 0));
                    if (s.ticks % 40 == 0) {
                        e.getWorld().playSound(loc, Sound.BLOCK_ANVIL_LAND, 0.65f, 1.4f);
                        e.getWorld().spawnParticle(Particle.CRIT, loc.clone().add(0, -0.4D, 0),
                                8, 0.3D, 0.2D, 0.3D, 0.1D);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void nextVerdict(Session s) {
        if (s.verdict == null) return;
        try {
            Entity e = Bukkit.getEntity(s.verdict);
            if (e instanceof TextDisplay td) {
                String text = VERDICTS[s.verdictIndex++ % VERDICTS.length];
                td.text(Component.text(text, NamedTextColor.RED, TextDecoration.BOLD));
            }
        } catch (Exception ignored) {}
    }

    private void tag(Entity e) {
        try {
            e.getPersistentDataContainer().set(propKey, PersistentDataType.BYTE, (byte) 1);
        } catch (Exception ignored) {}
    }

    private void face(Entity e, Location target) {
        try {
            double dx = target.getX() - e.getLocation().getX();
            double dy = target.getY() - e.getLocation().getY();
            double dz = target.getZ() - e.getLocation().getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            e.setRotation(yaw, pitch);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // The show
    // ------------------------------------------------------------------

    private void introEffects(Session s, int offence) {
        World w = s.anchor.getWorld();
        if (w == null) return;
        Location at = s.anchor.clone().add(0, 1.2D, 0);
        try { w.strikeLightningEffect(s.anchor); } catch (Exception ignored) {}
        try { w.playSound(s.anchor, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.15f); } catch (Exception ignored) {}
        try { w.playSound(s.anchor, Sound.BLOCK_BELL_USE, 1.0f, 0.7f); } catch (Exception ignored) {}
        try { w.playSound(s.anchor, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.0f); } catch (Exception ignored) {}
        if (offence >= 3) {
            try { w.playSound(s.anchor, Sound.EVENT_RAID_HORN, 0.45f, 0.9f); } catch (Exception ignored) {}
        }
        try {
            w.spawnParticle(Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.POOF, at, 50, 2.5D, 1.2D, 2.5D, 0.05D);
            w.spawnParticle(Particle.ELECTRIC_SPARK, at, 30, 2.0D, 1.5D, 2.0D, 0.1D);
        } catch (Exception ignored) {}
    }

    private void playBeat(Session s) {
        Note.Tone[] melody = {
                Note.Tone.G, Note.Tone.F, Note.Tone.E, Note.Tone.D,
                Note.Tone.C, Note.Tone.D, Note.Tone.E, Note.Tone.F
        };
        Instrument[] lead = {
                Instrument.BELL, Instrument.CHIME, Instrument.XYLOPHONE, Instrument.BIT
        };
        int step = s.noteIndex++;
        Note.Tone tone = melody[step % melody.length];
        int octave = (step / melody.length) % 2;
        Note note = Note.natural(octave, tone);
        Instrument instrument = lead[(step / melody.length) % lead.length];
        try {
            for (Player near : s.anchor.getWorld().getPlayers()) {
                if (near.getLocation().distanceSquared(s.anchor) > 48.0D * 48.0D) continue;
                if (step % 4 == 0) {
                    near.playNote(s.anchor, Instrument.BASS_GUITAR, Note.natural(0, tone));
                }
                near.playNote(s.anchor, instrument, note);
                if (step % 8 == 0) {
                    near.playSound(s.anchor, Sound.ENTITY_VILLAGER_AMBIENT, 0.4f, 0.8f);
                }
            }
        } catch (Exception ignored) {}
    }

    private void auraParticles(Session s, Player p) {
        World w = s.anchor.getWorld();
        if (w == null) return;
        Location at = s.anchor.clone().add(0, 1.1D, 0);
        try {
            w.spawnParticle(Particle.NAUTILUS, at, 4, 1.4D, 1.0D, 1.4D, 0.02D);
            w.spawnParticle(Particle.END_ROD, at, 2, 1.8D, 1.2D, 1.8D, 0.01D);
            w.spawnParticle(Particle.NOTE, p.getEyeLocation().add(0, 0.7D, 0), 2, 0.5D, 0.3D, 0.5D, 1.0D);
        } catch (Exception ignored) {}
    }

    private void lightning(Session s) {
        try {
            double angle = Math.random() * Math.PI * 2.0D;
            Location loc = s.anchor.clone().add(Math.cos(angle) * 6.0D, 0, Math.sin(angle) * 6.0D);
            s.anchor.getWorld().strikeLightningEffect(loc);
        } catch (Exception ignored) {}
    }

    private void heartbeat(Session s) {
        try {
            s.anchor.getWorld().playSound(s.anchor, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 0.8f);
        } catch (Exception ignored) {}
    }

    private void taunt(Session s, Player p) {
        List<String> taunts = plugin.getConfig().getStringList("combat-log.shame.taunts");
        if (taunts == null || taunts.isEmpty()) taunts = List.of(DEFAULT_TAUNTS);
        int idx = s.tauntIndex++;
        if (idx >= taunts.size() * 4) {
            s.tauntIndex = 0;
            idx = 0;
        }
        String line = taunts.get(idx % taunts.size());
        Component msg = Component.text("the mob mutters about " + p.getName() + ": " + line,
                NamedTextColor.GRAY, TextDecoration.ITALIC);
        for (Player near : s.anchor.getWorld().getPlayers()) {
            if (near.getLocation().distanceSquared(s.anchor) <= 48.0D * 48.0D) near.sendMessage(msg);
        }
    }

    private void updateBar(Session s, int left) {
        if (s.bar == null) return;
        try {
            long remaining = Math.max(0L, s.endAt - System.currentTimeMillis());
            float progress = Math.max(0.0f, Math.min(1.0f, (float) remaining / (float) s.totalMillis()));
            s.bar.progress(progress);
            s.bar.name(Component.text("PUBLIC HUMILIATION  " + left + "s", NamedTextColor.RED));
        } catch (Exception ignored) {}
    }

    private void removeProps(Session s, boolean showy) {
        World w = s.anchor.getWorld();
        for (UUID id : s.props) {
            try {
                Entity e = Bukkit.getEntity(id);
                if (e == null || !e.isValid()) continue;
                if (showy && w != null) {
                    Location loc = e.getLocation();
                    if (e instanceof Villager) {
                        w.spawnParticle(Particle.POOF, loc, 12, 0.3D, 0.5D, 0.3D, 0.02D);
                        w.playSound(loc, Sound.ENTITY_VILLAGER_YES, 0.5f, 1.1f);
                    } else if (e instanceof Chicken) {
                        w.spawnParticle(Particle.CLOUD, loc, 8, 0.2D, 0.3D, 0.2D, 0.02D);
                        w.playSound(loc, Sound.ENTITY_CHICKEN_HURT, 0.4f, 1.4f);
                    } else if (e instanceof ArmorStand) {
                        w.spawnParticle(Particle.POOF, loc, 15, 0.3D, 0.5D, 0.3D, 0.02D);
                        w.playSound(loc, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.6f);
                    }
                }
                e.remove();
            } catch (Exception ignored) {}
        }
        s.props.clear();
        s.villagers.clear();
        s.chickens.clear();
        s.judge = null;
        s.verdict = null;
        // Fallback for props whose UUID lookup failed after a world reload.
        try {
            if (w == null) return;
            for (Entity e : w.getNearbyEntities(s.anchor, 10, 10, 10)) {
                try {
                    if (e.getPersistentDataContainer().has(propKey, PersistentDataType.BYTE)
                            || e.getPersistentDataContainer().has(legacyFishKey, PersistentDataType.BYTE)) {
                        e.remove();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void finish(Session s, boolean completed, boolean restorePending, boolean message) {
        if (s.task != null) {
            s.task.cancel();
            s.task = null;
        }
        sessions.remove(s.id);
        hideBar(s);
        removeProps(s, completed);
        if (completed) {
            spawnVerdictBanner(s, "CASE DISMISSED", NamedTextColor.GREEN);
            if (combat.shameEffects()) finaleEffects(s);
        }

        Player p = Bukkit.getPlayer(s.id);
        if (p != null && p.isOnline()) {
            restoreInvulnerable(s);
            if (message) {
                showTitle(p,
                        completed ? "CASE DISMISSED" : "THE MOB REMEMBERS",
                        completed ? "the tribunal has spoken" : "the tribunal keeps a list",
                        completed ? NamedTextColor.GREEN : NamedTextColor.RED);
                p.sendMessage(Component.text(completed
                        ? "Case dismissed. The mob will be watching."
                        : "The mob will remember this.", NamedTextColor.GRAY));
            }
        }
        if (restorePending && !s.test) {
            combat.restorePending(s.id, s.name, s.offence);
        }
    }

    private void spawnVerdictBanner(Session s, String text, NamedTextColor color) {
        World w = s.anchor.getWorld();
        if (w == null) return;
        try {
            TextDisplay td = w.spawn(s.anchor.clone().add(0, 4.8D, 0), TextDisplay.class);
            td.text(Component.text(text, color, TextDecoration.BOLD));
            td.setBillboard(Display.Billboard.CENTER);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setTextOpacity((byte) -1);
            td.setBackgroundColor(Color.fromARGB(0x55000000));
            td.setViewRange(2.0f);
            td.setPersistent(false);
            tag(td);
            UUID id = td.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    Entity e = Bukkit.getEntity(id);
                    if (e != null) {
                        e.getWorld().spawnParticle(Particle.POOF, e.getLocation(), 10, 0.3D, 0.3D, 0.3D, 0.02D);
                        e.remove();
                    }
                } catch (Exception ignored) {}
            }, 45L);
        } catch (Exception ignored) {}
    }

    private void finaleEffects(Session s) {
        World w = s.anchor.getWorld();
        if (w == null) return;
        Location at = s.anchor.clone().add(0, 1.2D, 0);
        try {
            w.spawnParticle(Particle.FIREWORK, at, 90, 2.2D, 1.8D, 2.2D, 0.15D);
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 45, 2.0D, 1.5D, 2.0D, 0.25D);
            w.spawnParticle(Particle.CLOUD, at, 80, 3.0D, 2.0D, 3.0D, 0.05D);
            w.playSound(s.anchor, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            w.playSound(s.anchor, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.7f, 1.0f);
            w.playSound(s.anchor, Sound.BLOCK_BELL_USE, 0.9f, 1.4f);
        } catch (Exception ignored) {}
    }

    private void hideBar(Session s) {
        if (s.bar == null) return;
        for (UUID id : s.viewers) {
            try {
                Player v = Bukkit.getPlayer(id);
                if (v != null && v.isOnline()) v.hideBossBar(s.bar);
            } catch (Exception ignored) {}
        }
        s.viewers.clear();
        s.bar = null;
    }

    private void restoreInvulnerable(Session s) {
        try {
            Player p = Bukkit.getPlayer(s.id);
            if (p != null && p.isOnline()) p.setInvulnerable(s.wasInvulnerable);
        } catch (Exception ignored) {}
    }

    private void showTitle(Player p, String title, String subtitle, NamedTextColor color) {
        try {
            p.showTitle(Title.title(
                    Component.text(title, color),
                    Component.text(subtitle, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))));
        } catch (Exception ignored) {}
    }

    private void broadcast(Player p, int offence, boolean test) {
        try {
            String raw = plugin.getConfig().getString("combat-log.shame.broadcast",
                    "&c{player} combat logged - the mob tribunal is now in session.");
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
