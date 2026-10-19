package com.hush.allpurpose;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Bounties: players put money on another player's head; whoever kills that
 * player collects the whole pot through Vault (EssentialsX Economy).
 *
 * The money is escrowed the moment a bounty is placed and held by the plugin
 * (bounties.yml), so it survives restarts and updates. Contributions are
 * tracked per player so an admin /bounty clear can refund everyone.
 *
 * Kills are picked up from PlayerDeathEvent#getKiller, so only a real player
 * kill pays out - a mob death or a fall leaves the bounty standing.
 */
public class BountyManager implements Listener {

    /** One player's bounty: target + who put what in. */
    public static final class Bounty {
        public final UUID target;
        public String targetName;
        public final Map<UUID, Double> contributors = new LinkedHashMap<>();

        Bounty(UUID target, String targetName) {
            this.target = target;
            this.targetName = targetName;
        }

        public double total() {
            double t = 0;
            for (double d : contributors.values()) t += d;
            return t;
        }
    }

    private final AllPurpose plugin;
    private final Map<UUID, Bounty> bounties = new LinkedHashMap<>();
    private Economy econ;
    private File file;

    public BountyManager(AllPurpose plugin) {
        this.plugin = plugin;
    }

    public void start() {
        econ = Menus.economy();
        file = new File(plugin.getDataFolder(), "bounties.yml");
        load();
        try {
            plugin.getLogger().info("[Bounty] economy hook: "
                    + (econ == null ? "NOT HOOKED (Vault/Economy missing)" : econ.getName())
                    + ", tracked bounties: " + bounties.size());
        } catch (Exception ignored) {}
    }

    /** Persist what we hold; bounties stay claimable across restarts. */
    public void shutdown() {
        save();
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
        try { return plugin.getConfig().getBoolean("bounty.enabled", true); } catch (Exception e) { return true; }
    }

    public double minBounty() {
        return Math.max(1.0, cfg("bounty.min-bounty", 100.0));
    }

    public double maxBounty() {
        return Math.max(minBounty(), cfg("bounty.max-bounty", 1000000000.0));
    }

    public double taxPercent() {
        return Menus.clamp(cfg("bounty.tax-percent", 0.0), 0.0, 50.0);
    }

    public boolean broadcast() {
        try { return plugin.getConfig().getBoolean("bounty.broadcast", true); } catch (Exception e) { return true; }
    }

    public boolean announce() {
        try { return plugin.getConfig().getBoolean("bounty.announce", true); } catch (Exception e) { return true; }
    }

    public List<Double> presets() {
        List<Double> out = new ArrayList<>();
        try {
            List<String> raw = plugin.getConfig().getStringList("bounty.presets");
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

    // ---------- state ----------

    /** Highest pot first. */
    public synchronized List<Bounty> top() {
        List<Bounty> out = new ArrayList<>(bounties.values());
        out.sort(new Comparator<Bounty>() {
            @Override public int compare(Bounty a, Bounty b) {
                return Double.compare(b.total(), a.total());
            }
        });
        return out;
    }

    public synchronized Bounty of(UUID target) {
        return target == null ? null : bounties.get(target);
    }

    public double amountOf(UUID target) {
        Bounty b = of(target);
        return b == null ? 0.0 : b.total();
    }

    // ---------- actions ----------

    public synchronized boolean place(Player p, UUID target, String targetName, double amount) {
        if (!enabled()) { p.sendMessage("\u00a7cBounties are disabled."); return false; }
        Economy e = economy();
        if (e == null) { p.sendMessage("\u00a7cNo economy hooked (Vault missing)."); return false; }
        if (target == null) { p.sendMessage("\u00a7cUnknown player."); return false; }
        if (target.equals(p.getUniqueId())) { p.sendMessage("\u00a7cYou cannot put a bounty on yourself."); return false; }
        double amt = Math.floor(amount);
        if (amt < minBounty()) { p.sendMessage("\u00a7cMinimum bounty is " + Menus.money(e, minBounty()) + "\u00a7c."); return false; }
        if (amt > maxBounty()) { p.sendMessage("\u00a7cMaximum bounty is " + Menus.money(e, maxBounty()) + "\u00a7c."); return false; }
        if (!e.has(p, amt)) {
            p.sendMessage("\u00a7cYou need " + Menus.money(e, amt) + "\u00a7c (you have " + Menus.money(e, balance(p)) + "\u00a7c).");
            return false;
        }
        EconomyResponse r = e.withdrawPlayer(p, amt);
        if (r == null || !r.transactionSuccess()) {
            p.sendMessage("\u00a7cWithdraw failed: " + (r == null ? "no response" : r.errorMessage));
            return false;
        }
        Bounty b = bounties.get(target);
        if (b == null) {
            b = new Bounty(target, targetName == null ? "?" : targetName);
            bounties.put(target, b);
        }
        if (targetName != null && !targetName.isEmpty()) b.targetName = targetName;
        b.contributors.merge(p.getUniqueId(), amt, new java.util.function.BiFunction<Double, Double, Double>() {
            @Override public Double apply(Double a, Double c) { return a + c; }
        });
        save();
        double total = b.total();
        p.sendMessage("\u00a76\u00a7l\u2620 \u00a7fBounty placed: \u00a7a" + Menus.money(e, amt) + "\u00a7f on \u00a7e" + b.targetName
                + "\u00a7f (total \u00a7a" + Menus.money(e, total) + "\u00a7f).");
        Player t = Bukkit.getPlayer(target);
        if (t != null && t.isOnline()) {
            t.sendMessage("\u00a76\u00a7l\u2620 \u00a7cA bounty of " + Menus.money(e, amt) + " was placed on you by " + p.getName()
                    + "\u00a7c (total " + Menus.money(e, total) + "\u00a7c).");
        }
        if (broadcast()) {
            try {
                Bukkit.broadcastMessage("\u00a76\u00a7l\u2620 \u00a7f" + p.getName() + " \u00a77put \u00a7a" + Menus.money(e, amt)
                        + " \u00a77on \u00a7f" + b.targetName + "\u00a77's head (total \u00a7a" + Menus.money(e, total) + "\u00a77)!");
            } catch (Exception ignored) {}
        }
        say(p.getName() + " placed a " + Menus.money(e, amt) + " bounty on " + b.targetName + " (total " + Menus.money(e, total) + ")");
        return true;
    }

    /** Admin: refund every contribution and clear the bounty. */
    public synchronized boolean clear(Player admin, String targetName, UUID target) {
        Bounty b = bounties.remove(target);
        if (b == null) return false;
        Economy e = economy();
        for (Map.Entry<UUID, Double> c : b.contributors.entrySet()) {
            if (e != null) {
                try { e.depositPlayer(Bukkit.getOfflinePlayer(c.getKey()), c.getValue()); } catch (Exception ignored) {}
            }
        }
        save();
        if (admin != null) {
            admin.sendMessage("\u00a77Cleared the bounty on \u00a7f" + b.targetName + "\u00a77 - \u00a7a"
                    + Menus.money(e, b.total()) + "\u00a77 refunded to " + b.contributors.size() + " player(s).");
        }
        Player t = Bukkit.getPlayer(target);
        if (t != null && t.isOnline()) t.sendMessage("\u00a7aThe bounty on you was cleared.");
        return true;
    }

    /** Called when a player is killed by another player. */
    public synchronized void claim(Player victim, Player killer) {
        Bounty b = bounties.get(victim.getUniqueId());
        if (b == null) return;
        bounties.remove(victim.getUniqueId());
        Economy e = economy();
        double amount = b.total();
        double win = Math.max(0.0, amount - amount * taxPercent() / 100.0);
        if (e != null) {
            try { e.depositPlayer(killer, win); } catch (Exception ignored) {}
        }
        save();
        killer.sendMessage("\u00a76\u00a7l\u2620 \u00a7aYou claimed the bounty on \u00a7f" + b.targetName + "\u00a7a: \u00a7f" + Menus.money(e, win) + "\u00a7a!");
        victim.sendMessage("\u00a76\u00a7l\u2620 \u00a7cYour bounty of \u00a7f" + Menus.money(e, amount) + "\u00a7c was claimed by " + killer.getName() + "\u00a7c.");
        if (broadcast()) {
            try {
                Bukkit.broadcastMessage("\u00a76\u00a7l\u2620 \u00a7f" + killer.getName() + " \u00a77claimed the \u00a7a"
                        + Menus.money(e, win) + " \u00a77bounty on \u00a7f" + b.targetName + "\u00a77!");
            } catch (Exception ignored) {}
        }
        say(killer.getName() + " claimed the " + Menus.money(e, win) + " bounty on " + b.targetName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        try {
            Player victim = e.getEntity();
            if (victim == null) return;
            Player killer = victim.getKiller();
            if (killer == null) return;
            if (killer.getUniqueId().equals(victim.getUniqueId())) return;
            if (!enabled()) return;
            if (bounties.containsKey(victim.getUniqueId())) claim(victim, killer);
        } catch (Exception ignored) {}
    }

    // ---------- persistence ----------

    private synchronized void load() {
        try {
            if (file == null || !file.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = y.getConfigurationSection("bounties");
            if (root == null) return;
            for (String key : root.getKeys(false)) {
                try {
                    UUID target = UUID.fromString(key);
                    ConfigurationSection s = root.getConfigurationSection(key);
                    if (s == null) continue;
                    Bounty b = new Bounty(target, s.getString("name", "?"));
                    ConfigurationSection cs = s.getConfigurationSection("contributors");
                    if (cs != null) {
                        for (String k : cs.getKeys(false)) {
                            try { b.contributors.put(UUID.fromString(k), cs.getDouble(k)); } catch (Exception ignored) {}
                        }
                    }
                    if (b.total() > 0) bounties.put(target, b);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Bounty] load failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private synchronized void save() {
        try {
            if (file == null) return;
            YamlConfiguration y = new YamlConfiguration();
            for (Bounty b : bounties.values()) {
                String base = "bounties." + b.target.toString();
                y.set(base + ".name", b.targetName);
                for (Map.Entry<UUID, Double> en : b.contributors.entrySet()) {
                    y.set(base + ".contributors." + en.getKey().toString(), en.getValue());
                }
            }
            y.save(file);
        } catch (Exception e) {
            try { plugin.getLogger().warning("[Bounty] save failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    /** Plain-text line for the Discord chat log (no-op when it is disabled). */
    public void say(String line) {
        try {
            DiscordChatLog log = plugin.getChatLog();
            if (log != null) log.system(line);
        } catch (Exception ignored) {}
    }
}
