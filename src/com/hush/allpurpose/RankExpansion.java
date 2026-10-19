package com.hush.allpurpose;

import java.util.UUID;
import org.bukkit.OfflinePlayer;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class RankExpansion extends PlaceholderExpansion {

    private final AllPurpose plugin;

    public RankExpansion(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "hushranks";
    }

    @Override
    public String getAuthor() {
        return "Hush";
    }

    @Override
    public String getVersion() {
        return "1.6.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer p, String params) {
        if (params == null) return null;
        String np = params.toLowerCase(java.util.Locale.ROOT);
        if (np.equals("arena") || np.equals("arena_players") || np.equals("netport")) {
            return String.valueOf(plugin.regionCount("arena"));
        }
        if (np.equals("mace") || np.equals("mace_arena")) {
            return String.valueOf(plugin.regionCount("mace_arena"));
        }
        if (np.equals("crystal") || np.equals("crystal_arena")) {
            return String.valueOf(plugin.regionCount("crystal_arena"));
        }
        if (p == null) return "";
        UUID id = p.getUniqueId();
        if (params.equalsIgnoreCase("rank")) {
            String d = plugin.rankDisplayName(id);
            return d == null ? "" : d;
        }
        if (params.equalsIgnoreCase("sortrank") || params.equalsIgnoreCase("sort_rank")) {
            // TAB sorting key: staff overlay (/rank staff) wins over the main
            // ladder rank, so a staffer holding LT1 still sorts as STAFF.
            try {
                String sk = plugin.getRanks().staffKey(id);
                if (sk != null) {
                    String dn = plugin.getRanks().displayName(sk);
                    if (dn != null && !dn.isEmpty()) return dn;
                }
            } catch (Exception ignored) {}
            String d = plugin.rankDisplayName(id);
            // Never-ranked (TAB queries during the ~2s before autoRank assigns
            // MEMBER on join): sort as member so TAB always sees a listed
            // value and never warns / drops the player to the bottom.
            if (d == null || d.isEmpty()) d = "Member";
            return d;
        }
        if (params.equalsIgnoreCase("prefix")) {
            String d = plugin.rankPrefix(id);
            return d == null ? "" : d;
        }
        // Lifetime PvP numbers (lb.yml store behind the rank ladder) + playtime.
        //   kills / deaths / kd (killer/deaths, 2 decimals) / playtime (Xd Xh Xm)
        if (np.equals("kills")) {
            try { return String.valueOf(plugin.getLeaderboard().lbKills(id)); }
            catch (Exception e) { return "0"; }
        }
        if (np.equals("deaths")) {
            try { return String.valueOf(plugin.getLeaderboard().lbDeaths(id)); }
            catch (Exception e) { return "0"; }
        }
        if (np.equals("kd")) {
            try {
                int k = plugin.getLeaderboard().lbKills(id);
                int d = plugin.getLeaderboard().lbDeaths(id);
                if (d <= 0) return String.valueOf(k);
                return String.format(java.util.Locale.ROOT, "%.2f", (double) k / (double) d);
            } catch (Exception e) { return "0"; }
        }
        // Money from the server economy (Vault / EssentialsX), shortened for the
        // sidebar: 98812.65 -> "98.8K", 1200000 -> "1.2M", 1.2e9 -> "1.2B".
        //   balance / bal / money   -> short
        if (np.equals("balance") || np.equals("bal") || np.equals("money")) {
            try {
                net.milkbowl.vault.economy.Economy e = Menus.economy();
                if (e == null) return "0";
                return Menus.shortNumber(e.getBalance(p));
            } catch (Exception e) { return "0"; }
        }
        if (np.equals("playtime")) {
            try {
                org.bukkit.entity.Player pl = p.getPlayer();
                if (pl == null) return "0m";
                long ticks;
                try { ticks = pl.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE); }
                catch (Exception e) { return "0m"; }
                long mins = ticks / (20L * 60L);
                long d = mins / 1440L;
                long h = (mins % 1440L) / 60L;
                long m = mins % 60L;
                if (d > 0) return d + "d " + h + "h";
                if (h > 0) return h + "h " + m + "m";
                return m + "m";
            } catch (Exception e) { return "0m"; }
        }
        // Hearts for TAB belowname (no entities, no lag): heart counts, not HP.
        //   hearts        base hearts, 1 decimal (health / 2)
        //   hearts_int    base hearts, rounded int (for belowname `value`)
        //   hearts_abs    absorption hearts, 1 decimal (gapple / god-apple)
        //   hearts_total  (health + absorption) / 2, 1 decimal
        //   hearts_total_int  rounded int (for belowname `value`)
        if (np.equals("hearts") || np.equals("hearts_abs") || np.equals("hearts_total")
                || np.equals("hearts_int") || np.equals("hearts_total_int")) {
            try {
                org.bukkit.entity.Player pl = null;
                try { pl = p.getPlayer(); } catch (Exception ignored) {}
                if (pl == null || !pl.isOnline()) return "0";
                double hp;
                try { hp = pl.getHealth(); } catch (Exception e) { return "0"; }
                double abs = 0.0;
                try { abs = Math.max(0.0, pl.getAbsorptionAmount()); } catch (Exception ignored) {}
                if (np.equals("hearts")) return String.format(java.util.Locale.ROOT, "%.1f", hp / 2.0);
                if (np.equals("hearts_int")) return String.valueOf((int) Math.round(hp / 2.0));
                if (np.equals("hearts_abs")) return String.format(java.util.Locale.ROOT, "%.1f", abs / 2.0);
                if (np.equals("hearts_total")) return String.format(java.util.Locale.ROOT, "%.1f", (hp + abs) / 2.0);
                if (np.equals("hearts_total_int")) return String.valueOf((int) Math.round((hp + abs) / 2.0));
            } catch (Exception ignored) {}
            return "";
        }
        return null;
    }
}
