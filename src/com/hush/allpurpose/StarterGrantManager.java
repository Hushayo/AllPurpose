package com.hush.allpurpose;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Starter grant: a one-time, treasury-funded welcome for genuinely new players.
 *
 * A player who reaches starter-grant.playtime-minutes of playtime (the vanilla
 * minecraft:play_time stat, same source as /whois and the playtimetop board)
 * receives a single grant paid out of the wealth-tax treasury. The grant is
 * recorded as a treasury spend, so it shows up in /tax info and /tax ledger.
 *
 * "New" means first joined AFTER the feature was first armed; accounts that
 * existed before it are grandfathered (include-existing: true overrides this)
 * so enabling the feature never pays a backlog of veterans at once.
 *
 * AFK CAVEAT (known, deferred): play_time accrues while a player is idle, so a
 * determined alt can farm the grant by sitting connected. The intended fix -
 * counting only ACTIVE minutes and capping one grant per IP / Discord-linked
 * account - is not built yet; keep the grant disabled until then, or lower the
 * amount / raise playtime-minutes.
 */
public class StarterGrantManager implements Listener {

    private final AllPurpose plugin;
    private BukkitTask task;

    public StarterGrantManager(AllPurpose plugin) {
        this.plugin = plugin;
    }

    public void start() {
        try {
            task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override public void run() { tick(); }
            }, 20L * 60L, 20L * 60L);
        } catch (Exception ignored) {}
        try {
            plugin.getLogger().info("[StarterGrant] " + (enabled() ? "enabled" : "disabled")
                    + " (playtime " + playtimeMinutes() + "m, " + Menus.plain(amount()) + ")");
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        try { if (task != null) task.cancel(); } catch (Exception ignored) {}
    }

    // ---------- config ----------

    private double cfgD(String k, double d) {
        try { return plugin.getConfig().getDouble(k, d); } catch (Exception e) { return d; }
    }
    private boolean cfgB(String k, boolean d) {
        try { return plugin.getConfig().getBoolean(k, d); } catch (Exception e) { return d; }
    }
    private String cfgS(String k, String d) {
        try { String s = plugin.getConfig().getString(k, d); return s == null ? d : s; }
        catch (Exception e) { return d; }
    }

    public boolean enabled() { return cfgB("starter-grant.enabled", false); }
    public long playtimeMinutes() { return (long) Menus.clamp(cfgD("starter-grant.playtime-minutes", 60.0), 1.0, 1000000.0); }
    public double amount() { return Math.max(0.0, cfgD("starter-grant.amount", 5000.0)); }
    public boolean requireFunds() { return cfgB("starter-grant.require-funds", true); }
    public boolean includeExisting() { return cfgB("starter-grant.include-existing", false); }
    public boolean announce() { return cfgB("starter-grant.announce", true); }
    public String message() {
        return cfgS("starter-grant.message",
                "&aWelcome, &f{player}&a! You reached &f{time} &aof playtime - the community fund grants you &f{amount}&a.");
    }

    // ---------- checks ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        try {
            if (!enabled()) return;
            final Player p = e.getPlayer();
            // Give the play-time stat a moment to settle before reading it.
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() { tryGrant(p); }
            }, 40L);
        } catch (Exception ignored) {}
    }

    private void tick() {
        try {
            if (!enabled()) return;
            for (Player p : Bukkit.getOnlinePlayers()) tryGrant(p);
        } catch (Exception ignored) {}
    }

    private void tryGrant(Player p) {
        try {
            if (p == null || !p.isOnline()) return;
            WealthTaxManager tax = plugin.getWealthTax();
            if (tax == null) return;
            UUID id = p.getUniqueId();
            if (tax.grantClaimed(id)) return;
            if (!includeExisting()) {
                long armed = tax.grantArmedAt();
                if (armed == 0L) { tax.stampGrantArmed(); return; } // arm now, grant from the next pass
                long first = firstPlayed(id);
                if (first > 0 && first < armed) return; // existed before the feature - grandfathered
            }
            if (playtimeMinutesOf(id) < playtimeMinutes()) return;
            double amt = amount();
            if (amt <= 0) return;
            if (requireFunds() && tax.available("starter-grant") < amt) return; // wait until the grant bucket can cover it
            if (!tax.payStarterGrant(id, p.getName(), amt)) return;
            announce(p, amt);
        } catch (Exception ignored) {}
    }

    private long playtimeMinutesOf(UUID id) {
        try {
            LeaderboardManager lb = plugin.getLeaderboard();
            if (lb != null) return lb.playtimeMinutes(id.toString());
        } catch (Exception ignored) {}
        try {
            return Math.max(0, Bukkit.getOfflinePlayer(id).getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long firstPlayed(UUID id) {
        try { return Bukkit.getOfflinePlayer(id).getFirstPlayed(); } catch (Exception e) { return 0L; }
    }

    private void announce(Player p, double amt) {
        final String time = timeLabel(playtimeMinutes());
        try {
            p.sendMessage(message().replace("{player}", p.getName())
                    .replace("{amount}", Menus.plain(amt)).replace("{time}", time));
        } catch (Exception ignored) {}
        try {
            if (announce()) {
                Bukkit.broadcastMessage("§6§l✦ COMMUNITY FUND §r§7- §f" + p.getName()
                        + " §7reached §f" + time + " §7of playtime and received a §a"
                        + Menus.plain(amt) + " §7starter grant.");
            }
        } catch (Exception ignored) {}
        try { plugin.getWealthTax().say("Community fund: " + p.getName() + " received a " + Menus.plain(amt) + " starter grant."); } catch (Exception ignored) {}
    }

    private static String timeLabel(long mins) {
        if (mins > 0 && mins % 60 == 0) {
            long h = mins / 60;
            return h + " hour" + (h == 1 ? "" : "s");
        }
        return mins + " minute" + (mins == 1 ? "" : "s");
    }
}
