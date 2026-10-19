package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Coinflip: two players wager the SAME amount and a 50/50 flip decides who
 * takes the pot. Both wagers are escrowed through Vault (EssentialsX Economy)
 * the moment they are placed, so a losing bet can never be dodged; every open
 * flip is refunded if the plugin disables or the wager times out.
 *
 * A player has at most one open flip. Everything is driven by CoinFlipGui:
 * the lobby lists open flips as player heads, the create screen steps an
 * amount up/down (presets + "type it in chat"), and the flip screen spins the
 * two heads before paying out.
 */
public class CoinFlipManager {

    /** One open wager. Immutable apart from removal. */
    public static final class Flip {
        public final UUID id = UUID.randomUUID();
        public final UUID creator;
        public final String creatorName;
        public final double wager;
        public final long at = System.currentTimeMillis();

        Flip(UUID creator, String creatorName, double wager) {
            this.creator = creator;
            this.creatorName = creatorName;
            this.wager = wager;
        }
    }

    private final AllPurpose plugin;
    private final Map<UUID, Flip> flips = new LinkedHashMap<>();
    private final Map<UUID, UUID> byCreator = new HashMap<>();
    private Economy econ;
    private BukkitTask sweep;

    public CoinFlipManager(AllPurpose plugin) {
        this.plugin = plugin;
    }

    public void start() {
        econ = Menus.economy();
        try {
            plugin.getLogger().info("[Coinflip] economy hook: " + (econ == null ? "NOT HOOKED (Vault/Economy missing)" : econ.getName()));
        } catch (Exception ignored) {}
        long period = 20L * 20L;
        sweep = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                try { sweepExpired(); } catch (Exception ignored) {}
            }
        }, period, period);
    }

    /** Refunds every open flip (server stopping / plugin disabled). */
    public void shutdown() {
        try { if (sweep != null) sweep.cancel(); } catch (Exception ignored) {}
        List<Flip> open;
        synchronized (this) {
            open = new ArrayList<>(flips.values());
            flips.clear();
            byCreator.clear();
        }
        for (Flip f : open) refund(f.creator, f.wager);
    }

    public Economy economy() {
        if (econ == null) econ = Menus.economy();
        return econ;
    }

    public double balance(OfflinePlayer p) {
        Economy e = economy();
        if (e == null || p == null) return 0.0;
        try { return e.getBalance(p); } catch (Exception ex) { return 0.0; }
    }

    // ---------- config ----------

    public boolean enabled() {
        try { return plugin.getConfig().getBoolean("coinflip.enabled", true); } catch (Exception e) { return true; }
    }

    public double minWager() {
        return Math.max(1.0, cfg("coinflip.min-wager", 100.0));
    }

    public double maxWager() {
        return Math.max(minWager(), cfg("coinflip.max-wager", 1000000.0));
    }

    public double taxPercent() {
        return Menus.clamp(cfg("coinflip.tax-percent", 0.0), 0.0, 50.0);
    }

    public int expireSeconds() {
        return (int) Menus.clamp(cfg("coinflip.expire-seconds", 600.0), 30.0, 86400.0);
    }

    public boolean broadcast() {
        try { return plugin.getConfig().getBoolean("coinflip.broadcast", true); } catch (Exception e) { return true; }
    }

    public boolean announce() {
        try { return plugin.getConfig().getBoolean("coinflip.announce", true); } catch (Exception e) { return true; }
    }

    public List<Double> presets() {
        List<Double> out = new ArrayList<>();
        try {
            List<String> raw = plugin.getConfig().getStringList("coinflip.presets");
            if (raw != null) for (String s : raw) {
                Double v = Menus.parseAmount(s);
                if (v != null) out.add(v);
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            double[] def = {100, 500, 1000, 5000, 10000, 50000, 100000, 1000000};
            for (double d : def) out.add(d);
        }
        return out;
    }

    private double cfg(String key, double def) {
        try { return plugin.getConfig().getDouble(key, def); } catch (Exception e) { return def; }
    }

    /** Pot a winner collects for a given wager (after tax). */
    public double payout(double wager) {
        double pot = wager * 2.0;
        return Math.max(0.0, pot - pot * taxPercent() / 100.0);
    }

    // ---------- state ----------

    public synchronized List<Flip> open() {
        return new ArrayList<>(flips.values());
    }

    public synchronized Flip byId(UUID id) {
        return id == null ? null : flips.get(id);
    }

    public synchronized UUID ownFlip(UUID creator) {
        return creator == null ? null : byCreator.get(creator);
    }

    // ---------- actions ----------

    public synchronized boolean create(Player p, double wager) {
        if (!enabled()) { p.sendMessage("\u00a7cCoinflips are disabled."); return false; }
        Economy e = economy();
        if (e == null) { p.sendMessage("\u00a7cNo economy hooked (Vault missing)."); return false; }
        if (byCreator.containsKey(p.getUniqueId())) {
            p.sendMessage("\u00a7cYou already have an open coinflip - cancel it in \u00a7f/coinflip\u00a7c first.");
            return false;
        }
        double w = Math.floor(wager);
        if (w < minWager()) { p.sendMessage("\u00a7cMinimum wager is " + Menus.money(e, minWager()) + "\u00a7c."); return false; }
        if (w > maxWager()) { p.sendMessage("\u00a7cMaximum wager is " + Menus.money(e, maxWager()) + "\u00a7c."); return false; }
        if (!e.has(p, w)) {
            p.sendMessage("\u00a7cYou need " + Menus.money(e, w) + "\u00a7c (you have " + Menus.money(e, balance(p)) + "\u00a7c).");
            return false;
        }
        EconomyResponse r = e.withdrawPlayer(p, w);
        if (r == null || !r.transactionSuccess()) {
            p.sendMessage("\u00a7cWithdraw failed: " + (r == null ? "no response" : r.errorMessage));
            return false;
        }
        Flip f = new Flip(p.getUniqueId(), p.getName(), w);
        flips.put(f.id, f);
        byCreator.put(p.getUniqueId(), f.id);
        p.sendMessage("\u00a76\u00a7l\u25c6 \u00a7fCoinflip opened for \u00a7a" + Menus.money(e, w) + "\u00a7f. Others can take it in \u00a7e/coinflip\u00a7f.");
        say(p.getName() + " opened a coinflip for $" + (long) w + " - /coinflip to take it");
        return true;
    }

    /** Take someone else's flip. Clicking your own flip cancels it instead. */
    public synchronized boolean join(Player p, Flip f) {
        if (f == null || !flips.containsKey(f.id)) {
            p.sendMessage("\u00a7cThat coinflip is gone.");
            return false;
        }
        if (f.creator.equals(p.getUniqueId())) {
            return cancel(p);
        }
        Economy e = economy();
        if (e == null) { p.sendMessage("\u00a7cNo economy hooked (Vault missing)."); return false; }
        if (!e.has(p, f.wager)) {
            p.sendMessage("\u00a7cYou need " + Menus.money(e, f.wager) + " to take this flip.");
            return false;
        }
        EconomyResponse r = e.withdrawPlayer(p, f.wager);
        if (r == null || !r.transactionSuccess()) {
            p.sendMessage("\u00a7cWithdraw failed: " + (r == null ? "no response" : r.errorMessage));
            return false;
        }
        flips.remove(f.id);
        byCreator.remove(f.creator);
        spin(f, p);
        return true;
    }

    public synchronized boolean cancel(Player p) {
        UUID id = byCreator.get(p.getUniqueId());
        if (id == null) {
            p.sendMessage("\u00a77You have no open coinflip.");
            return false;
        }
        Flip f = flips.remove(id);
        byCreator.remove(p.getUniqueId());
        if (f != null) {
            refund(f.creator, f.wager);
            p.sendMessage("\u00a77Coinflip cancelled - \u00a7a" + Menus.money(economy(), f.wager) + "\u00a77 refunded.");
        }
        return true;
    }

    /** The flip animation, then the payout. */
    private void spin(final Flip f, final Player joiner) {
        final Player creator = Bukkit.getPlayer(f.creator);
        final boolean creatorWins = ThreadLocalRandom.current().nextBoolean();
        final UUID winnerId = creatorWins ? f.creator : joiner.getUniqueId();
        final List<Inventory> invs = new ArrayList<>();
        try {
            if (creator != null) invs.add(CoinFlipGui.openSpin(plugin, creator, f, joiner));
            invs.add(CoinFlipGui.openSpin(plugin, joiner, f, creator));
        } catch (Exception ignored) {}

        final ItemStack creatorHead = CoinFlipGui.face(Bukkit.getOfflinePlayer(f.creator), "\u00a7e\u00a7l" + f.creatorName, f.wager);
        final ItemStack joinerHead = CoinFlipGui.face(joiner, "\u00a7e\u00a7l" + joiner.getName(), f.wager);

        final int[] taskId = new int[1];
        taskId[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int step = 0;
            boolean left = true;

            @Override public void run() {
                try {
                    if (step >= 8) {
                        Bukkit.getScheduler().cancelTask(taskId[0]);
                        pay(f, joiner, winnerId, creatorHead, joinerHead, invs);
                        return;
                    }
                    CoinFlipGui.setCenter(invs, left ? creatorHead : joinerHead);
                    left = !left;
                    step++;
                } catch (Exception ignored) {}
            }
        }, 0L, 4L).getTaskId();
    }

    private void pay(Flip f, Player joiner, UUID winnerId, ItemStack creatorHead, ItemStack joinerHead, List<Inventory> invs) {
        Economy e = economy();
        double win = payout(f.wager);
        if (e != null) {
            try { e.depositPlayer(Bukkit.getOfflinePlayer(winnerId), win); } catch (Exception ignored) {}
        }
        boolean creatorWon = winnerId.equals(f.creator);
        String winnerName = creatorWon ? f.creatorName : joiner.getName();
        String loserName = creatorWon ? joiner.getName() : f.creatorName;
        try {
            CoinFlipGui.showResult(invs, creatorWon ? creatorHead : joinerHead, winnerName, Menus.money(e, win));
        } catch (Exception ignored) {}

        Player c = Bukkit.getPlayer(f.creator);
        if (c != null) {
            c.sendMessage(creatorWon
                    ? "\u00a76\u00a7l\u25c6 \u00a7aYou WON \u00a7f" + Menus.money(e, win) + "\u00a7a from " + loserName + "!"
                    : "\u00a76\u00a7l\u25c6 \u00a7cYou LOST \u00a7f" + Menus.money(e, f.wager) + "\u00a7c to " + winnerName + ".");
        }
        if (joiner != null && joiner.isOnline()) {
            joiner.sendMessage(creatorWon
                    ? "\u00a76\u00a7l\u25c6 \u00a7cYou LOST \u00a7f" + Menus.money(e, f.wager) + "\u00a7c to " + winnerName + "."
                    : "\u00a76\u00a7l\u25c6 \u00a7aYou WON \u00a7f" + Menus.money(e, win) + "\u00a7a from " + loserName + "!");
        }
        if (broadcast()) {
            try {
                Bukkit.broadcastMessage("\u00a76\u00a7l\u25c6 \u00a7f" + winnerName + " \u00a77won \u00a7a"
                        + Menus.money(e, win) + " \u00a77from \u00a7f" + loserName + " \u00a77in a coinflip!");
            } catch (Exception ignored) {}
        }
        say(winnerName + " won $" + (long) win + " from " + loserName + " in a coinflip");
    }

    private synchronized void sweepExpired() {
        long ttl = expireSeconds() * 1000L;
        long now = System.currentTimeMillis();
        List<Flip> dead = new ArrayList<>();
        for (Flip f : flips.values()) if (now - f.at >= ttl) dead.add(f);
        for (Flip f : dead) {
            flips.remove(f.id);
            byCreator.remove(f.creator);
            refund(f.creator, f.wager);
            Player p = Bukkit.getPlayer(f.creator);
            if (p != null) p.sendMessage("\u00a77Your coinflip expired - \u00a7a" + Menus.money(economy(), f.wager) + "\u00a77 refunded.");
        }
    }

    private void refund(UUID id, double amount) {
        if (amount <= 0) return;
        Economy e = economy();
        if (e == null) return;
        try { e.depositPlayer(Bukkit.getOfflinePlayer(id), amount); } catch (Exception ignored) {}
    }

    /** Plain-text line for the Discord chat log (no-op when it is disabled). */
    public void say(String line) {
        try {
            DiscordChatLog log = plugin.getChatLog();
            if (log != null) log.system(line);
        } catch (Exception ignored) {}
    }
}
