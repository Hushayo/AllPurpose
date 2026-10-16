package com.hush.allpurpose;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Owns ranks.yml / players.yml: rank data, ladder, auto-rankup.
 * Privileged mutators (staffSet, setFrozen, lpSync) deny callers outside
 * com.hush.allpurpose unless config allow-external-mutations is true. */
public class RankManager {

    private final AllPurpose plugin;
    private File ranksFile;
    private File playersFile;
    private YamlConfiguration ranks;
    private YamlConfiguration players;

    /** Guardrail: block rogue linked plugins from silent rank escalation. */
    private boolean allowExternalMutation(String method) {
        try {
            boolean external = false;
            String caller = "?";
            for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
                String c = el.getClassName();
                if (c.startsWith("com.hush.allpurpose.")) continue;
                if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("org.bukkit.")
                        || c.startsWith("RankManager") || c.startsWith("Thread")) continue;
                external = true;
                caller = c;
                break;
            }
            if (!external) return true;
            boolean allowed = false;
            try { allowed = plugin.getConfig().getBoolean("allow-external-mutations", false); } catch (Exception ignored) {}
            if (!allowed) {
                try { plugin.getLogger().warning("[AllPurpose] blocked external " + method + " from " + caller + " (allow-external-mutations=false)"); } catch (Exception ignored) {}
                return false;
            }
        } catch (Exception ignored) {}
        return true;
    }

    public RankManager(AllPurpose plugin) {
        this.plugin = plugin;
        ranksFile = new File(plugin.getDataFolder(), "ranks.yml");
        playersFile = new File(plugin.getDataFolder(), "players.yml");
        if (!ranksFile.exists()) {
            plugin.getDataFolder().mkdirs();
            ranks = new YamlConfiguration();
            saveRanks();
        } else {
            ranks = YamlConfiguration.loadConfiguration(ranksFile);
        }
        if (!playersFile.exists()) {
            players = new YamlConfiguration();
            savePlayers();
        } else {
            players = YamlConfiguration.loadConfiguration(playersFile);
        }
    }

    public String rankDisplayName(UUID id) {
        try {
            if (id == null) return null;
            String r = players.getString("players." + id.toString());
            if (r == null) return null;
            String key = findKey(r);
            if (key == null) return null;
            return displayName(key);
        } catch (Exception e) {
            return null;
        }
    }

    public String rankPrefix(UUID id) {
        try {
            if (id == null) return null;
            String r = players.getString("players." + id.toString());
            if (r == null) return null;
            String key = findKey(r);
            if (key == null) return null;
            return prefixOf(key);
        } catch (Exception e) {
            return null;
        }
    }

    public void ensureRank(String name, String prefix) {
        if (findKey(name) != null) return;
        // rankKey encodes '.' so the YAML path can't split - see TextUtil.rankKey.
        ranks.set("ranks." + TextUtil.rankKey(name) + ".name", name);
        ranks.set("ranks." + TextUtil.rankKey(name) + ".prefix", prefix);
        saveRanks();
        plugin.getLogger().info("Auto-created rank '" + name + "'.");
    }

    public void autoRank(Player p) {
        try {
            if (players.getString("players." + p.getUniqueId()) != null) return;
            String key = findKey("MEMBER");
            if (key == null) return;
            players.set("players." + p.getUniqueId(), key);
            savePlayers();
        } catch (Exception ignored) {}
    }

    public void noteName(Player p) {
        // Remember name -> UUID so rank commands work while the player is offline.
        try {
            players.set("names." + TextUtil.norm(p.getName()), p.getUniqueId().toString());
            savePlayers();
        } catch (Exception ignored) {}
    }

    public UUID resolveUuid(String name) {
        try {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) return online.getUniqueId();
            String u = players.getString("names." + TextUtil.norm(name));
            if (u != null) {
                try { return UUID.fromString(u); } catch (Exception ignored) {}
            }
            try {
                if (plugin.getLeaderboard().lbConfig().getConfigurationSection("stats") != null) {
                    for (String uuid : plugin.getLeaderboard().lbConfig().getConfigurationSection("stats").getKeys(false)) {
                        String n = plugin.getLeaderboard().lbConfig().getString("stats." + uuid + ".name");
                        if (n != null && n.equalsIgnoreCase(name)) return UUID.fromString(uuid);
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return null;
    }

    public String lastKnownName(UUID id) {
        try {
            Player online = Bukkit.getPlayer(id);
            if (online != null) return online.getName();
            String n = plugin.getLeaderboard().lbConfig().getString("stats." + id.toString() + ".name");
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {}
        return id.toString().substring(0, 8);
    }

    public List<String> knownPlayerNames() {
        List<String> out = new ArrayList<>();
        try {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            if (players.getConfigurationSection("names") != null) {
                for (String k : players.getConfigurationSection("names").getKeys(false)) {
                    if (!out.contains(k)) out.add(k);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void saveRanks() {
        try { ranks.save(ranksFile); } catch (IOException e) { plugin.getLogger().warning("Could not save ranks.yml: " + e.getMessage()); }
    }

    public void savePlayers() {
        try { players.save(playersFile); } catch (IOException e) { plugin.getLogger().warning("Could not save players.yml: " + e.getMessage()); }
    }

    public Set<String> rankKeys() {
        if (ranks.getConfigurationSection("ranks") == null) return java.util.Collections.emptySet();
        return ranks.getConfigurationSection("ranks").getKeys(false);
    }

    public List<String> rankNames() {
        List<String> out = new ArrayList<>();
        for (String k : rankKeys()) {
            String dn = ranks.getString("ranks." + k + ".name", k);
            out.add(dn);
        }
        return out;
    }

    public String findKey(String name) {
        String n = TextUtil.norm(name);
        for (String k : rankKeys()) {
            if (TextUtil.norm(k).equals(n) || TextUtil.norm(ranks.getString("ranks." + k + ".name", k)).equals(n)) return k;
        }
        return null;
    }

    public String displayName(String key) {
        return ranks.getString("ranks." + key + ".name", key);
    }

    public String prefixOf(String key) {
        return ranks.getString("ranks." + key + ".prefix", displayName(key));
    }

    public String playerRankKey(Player p) {
        String r = players.getString("players." + p.getUniqueId());
        if (r == null) return null;
        String key = findKey(r);
        return key;
    }

    public void staffSet(UUID id, String keyOrNull) {
        if (!allowExternalMutation("staffSet")) return;
        try {
            if (id == null) return;
            if (keyOrNull == null) {
                players.set("staff." + id.toString(), null);
            } else {
                String key = findKey(keyOrNull);
                if (key == null) return;
                players.set("staff." + id.toString(), key);
            }
            savePlayers();
        } catch (Exception ignored) {}
    }

    /** Freeze list for /rank upgrade: frozen players skip checkRankup + recalcOne. */
    public boolean isFrozen(UUID id) {
        try {
            if (id == null) return false;
            return players.getBoolean("frozen." + id.toString(), false);
        } catch (Exception ignored) { return false; }
    }

    public void setFrozen(UUID id, boolean frozen) {
        if (!allowExternalMutation("setFrozen")) return;
        try {
            if (id == null) return;
            if (frozen) players.set("frozen." + id.toString(), true);
            else players.set("frozen." + id.toString(), null);
            savePlayers();
        } catch (Exception ignored) {}
    }

    public String staffKey(UUID id) {
        try {
            if (id == null) return null;
            String r = players.getString("staff." + id.toString());
            if (r == null) return null;
            return findKey(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** Effective sync rank: staff overlay wins, else ladder rank, else MEMBER. */
    public String syncRankKey(UUID id) {
        try {
            String sk = staffKey(id);
            if (sk != null) return sk;
            if (id == null) return findKey("MEMBER");
            String r = players.getString("players." + id.toString());
            String key = r == null ? null : findKey(r);
            if (key != null) return key;
            return findKey("MEMBER");
        } catch (Exception e) {
            try { return findKey("MEMBER"); } catch (Exception ex) { return null; }
        }
    }

    public String syncRankName(UUID id) {
        try {
            String k = syncRankKey(id);
            if (k == null) return "MEMBER";
            return displayName(k);
        } catch (Exception e) { return "MEMBER"; }
    }

    public String staffTag(Player p) {
        try {
            if (p == null) return "";
            String key = staffKey(p.getUniqueId());
            if (key == null) return "";
            return "[" + TextUtil.legacy(prefixOf(key)) + "] ";
        } catch (Exception e) {
            return "";
        }
    }

    public String mainTag(Player p) {
        try {
            if (p == null) return "";
            String key = playerRankKey(p);
            if (key == null) return "";
            return "[" + TextUtil.legacy(prefixOf(key)) + "] ";
        } catch (Exception e) {
            return "";
        }
    }

    public String fullTagRaw(Player p) {
        try {
            if (p == null) return "";
            StringBuilder sb = new StringBuilder();
            String sk = null;
            String mk = null;
            try { sk = staffKey(p.getUniqueId()); } catch (Exception ignored) {}
            try { mk = playerRankKey(p); } catch (Exception ignored) {}
            if (sk != null) {
                String pre = "";
                try { pre = prefixOf(sk); } catch (Exception ignored) {}
                if (pre == null) pre = "";
                sb.append("[").append(pre).append("] ");
            }
            if (mk != null) {
                String pre = "";
                try { pre = prefixOf(mk); } catch (Exception ignored) {}
                if (pre == null) pre = "";
                sb.append("[").append(pre).append("] ");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public String fullTag(Player p) {
        try {
            return TextUtil.legacy(fullTagRaw(p));
        } catch (Exception e) {
            return "";
        }
    }

    public YamlConfiguration ranksConfig() {
        return ranks;
    }

    public YamlConfiguration playersConfig() {
        return players;
    }

    public List<String> getLadder() {
        try {
            List<String> cfg = plugin.getConfig().getStringList("rank-ladder");
            if (cfg != null && !cfg.isEmpty()) return cfg;
        } catch (Exception ignored) {}
        return java.util.Collections.singletonList("MEMBER");
    }

    public int rankupKills(String rankKey) {
        // Kills needed (lifetime) to ENTER this ladder rank. Config overrides these defaults.
        try {
            if (plugin.getConfig().isConfigurationSection("rankup-kills")) {
                for (String k : plugin.getConfig().getConfigurationSection("rankup-kills").getKeys(false)) {
                    String kk = findKey(k);
                    if (kk != null && TextUtil.norm(kk).equals(TextUtil.norm(rankKey))) {
                        return Math.max(1, plugin.getConfig().getInt("rankup-kills." + k, 1));
                    }
                }
            }
        } catch (Exception ignored) {}
        String n = TextUtil.norm(rankKey);
        if (n.equals("lt5")) return 5;
        if (n.equals("lt4")) return 15;
        if (n.equals("lt3")) return 30;
        if (n.equals("lt2")) return 50;
        if (n.equals("lt1")) return 75;
        if (n.equals("ht5")) return 110;
        if (n.equals("ht4")) return 150;
        if (n.equals("ht3")) return 200;
        if (n.equals("ht2")) return 260;
        if (n.equals("ht1")) return 350;
        return Integer.MAX_VALUE;
    }

    public boolean onLadder(String key) {
        try {
            if (key == null) return false;
            for (String entry : getLadder()) {
                String lk = findKey(entry);
                if (lk != null && TextUtil.norm(lk).equals(TextUtil.norm(key))) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public String correctLadderRank(int kills) {
        try {
            List<String> ladder = getLadder();
            if (ladder == null || ladder.isEmpty()) return null;
            String best = null;
            String first = null;
            boolean baseSet = false;
            for (String entry : ladder) {
                String k = findKey(entry);
                String eff = k != null ? k : entry;
                if (first == null) first = eff;
                int need;
                try { need = rankupKills(eff); } catch (Exception ignored) { continue; }
                if (need == Integer.MAX_VALUE) {
                    if (!baseSet) { best = eff; baseSet = true; }
                    continue;
                }
                if (need <= kills) best = eff;
            }
            if (best == null) best = first;
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    public String recalcOne(UUID id) {
        try {
            if (id == null) return null;
            if (isFrozen(id)) return null; // manual upgrade: recalc-proof
            String cur = null;
            try { cur = players.getString("players." + id.toString()); } catch (Exception ignored) {}
            String curKey = cur == null ? null : findKey(cur);
            if (curKey != null && !onLadder(curKey)) return null;
            int kills = 0;
            try { kills = plugin.getLeaderboard().lbKills(id); } catch (Exception ignored) {}
            String correct = correctLadderRank(kills);
            if (correct == null) return null;
            if (curKey != null && TextUtil.norm(curKey).equals(TextUtil.norm(correct))) return null;
            players.set("players." + id.toString(), correct);
            try { lpSync(id, curKey, correct); } catch (Exception ignored) {}
            String who = null;
            try {
                Player online = Bukkit.getPlayer(id);
                if (online != null) who = online.getName();
            } catch (Exception ignored) {}
            if (who == null) {
                try { who = plugin.getLeaderboard().lbConfig().getString("stats." + id.toString() + ".name"); } catch (Exception ignored) {}
            }
            if (who == null || who.isEmpty()) {
                try { who = id.toString().substring(0, 8); } catch (Exception ignored) { who = id.toString(); }
            }
            String oldName = curKey == null ? "none" : displayName(curKey);
            String newName = displayName(correct);
            return who + ": " + oldName + " -> " + newName;
        } catch (Exception ignored) {
            return null;
        }
    }

    // LuckPerms group mirror: players holding a synced hushrank automatically
    // gain/lose the same-named LP parent group (drives kit permissions etc.).
    // Runs as console `lp` commands (no LP dependency); LP's own replies land
    // in the server log. Config: lp-sync-enabled + lp-sync-groups (lowercase
    // rank keys, e.g. [lt3]). Only synced ranks are touched - staff overlays,
    // agent bots and unsynced ranks never emit commands.
    //
    // Ladder minimums (lp-sync-minimums, e.g. lt3: LT3): everyone AT or ABOVE
    // the minimum keeps the group, so ranking LT3 -> LT2 never strips lt3.
    // Computed as had-vs-has per group on every transition (this fixed the bug
    // where the old base path removed the old group before minimums ran).
    public void lpSync(UUID id, String oldKeyOrNull, String newKeyOrNull) {
        if (!allowExternalMutation("lpSync")) return;
        try {
            if (id == null) return;
            boolean enabled = true;
            try { enabled = plugin.getConfig().getBoolean("lp-sync-enabled", true); } catch (Exception ignored) {}
            if (!enabled) return;
            java.util.List<String> groups = null;
            try { groups = plugin.getConfig().getStringList("lp-sync-groups"); } catch (Exception ignored) {}
            java.util.Set<String> synced = new java.util.HashSet<>();
            if (groups != null) {
                for (String g : groups) {
                    if (g != null && !g.trim().isEmpty()) synced.add(TextUtil.norm(g.trim()));
                }
            }
            java.util.Map<String, String> minimums = new java.util.HashMap<>();
            try {
                if (plugin.getConfig().isConfigurationSection("lp-sync-minimums")) {
                    for (String g : plugin.getConfig().getConfigurationSection("lp-sync-minimums").getKeys(false)) {
                        try {
                            String mv = plugin.getConfig().getString("lp-sync-minimums." + g);
                            String mk = mv == null ? null : findKey(mv);
                            if (g != null && !g.trim().isEmpty() && mk != null) {
                                minimums.put(TextUtil.norm(g.trim()), mk);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            if (synced.isEmpty() && minimums.isEmpty()) return;
            String o = oldKeyOrNull == null ? null : TextUtil.norm(oldKeyOrNull);
            String n = newKeyOrNull == null ? null : TextUtil.norm(newKeyOrNull);
            if (o != null && o.equals(n)) return;
            java.util.List<String> ladder = null;
            try { ladder = getLadder(); } catch (Exception ignored) {}
            java.util.Set<String> allGroups = new java.util.HashSet<>(synced);
            allGroups.addAll(minimums.keySet());
            String u = id.toString();
            for (String g : allGroups) {
                boolean had = qualifiesLpGroup(g, o, ladder, minimums, synced);
                boolean has = qualifiesLpGroup(g, n, ladder, minimums, synced);
                if (has && !had) lpDispatch("lp user " + u + " parent add " + g);
                else if (!has && had) lpDispatch("lp user " + u + " parent remove " + g);
            }
        } catch (Exception ignored) {}
    }

    // Does this ladder rank earn this LP group? Direct (same-named synced rank)
    // or at/above the group's ladder minimum. Null rank earns nothing.
    private boolean qualifiesLpGroup(String group, String rankNormOrNull,
            java.util.List<String> ladder, java.util.Map<String, String> minimums,
            java.util.Set<String> synced) {
        try {
            if (group == null || group.isEmpty()) return false;
            if (rankNormOrNull == null || rankNormOrNull.isEmpty()) return false;
            if (rankNormOrNull.equals(group) && synced.contains(group)) return true;
            String minRank = minimums.get(group);
            if (minRank == null || ladder == null || ladder.isEmpty()) return false;
            int need = ladderIndex(ladder, minRank);
            if (need < 0) return false;
            int ri = ladderIndex(ladder, rankNormOrNull);
            return ri >= need;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int ladderIndex(java.util.List<String> ladder, String key) {
        try {
            if (ladder == null || key == null) return -1;
            for (int i = 0; i < ladder.size(); i++) {
                String lk = findKey(ladder.get(i));
                if (lk != null && TextUtil.norm(lk).equals(TextUtil.norm(key))) return i;
            }
            return -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void lpDispatch(final String cmd) {
        try {
            Runnable r = new Runnable() {
                @Override public void run() {
                    boolean ok = false;
                    try { ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); } catch (Exception ignored) {}
                    try { plugin.getLogger().info("[LpSync] " + cmd + " -> " + ok); } catch (Exception ignored) {}
                }
            };
            boolean sync = false;
            try { sync = Bukkit.isPrimaryThread(); } catch (Exception ignored) {}
            if (sync) r.run();
            else Bukkit.getScheduler().runTask(plugin, r);
        } catch (Exception ignored) {}
    }

    public void checkRankup(Player killer) {
        // Manual upgrades (/rank upgrade) freeze the player: recalc-proof.
        try { if (killer != null && isFrozen(killer.getUniqueId())) return; } catch (Exception ignored) {}
        // PvP ladder climb: enough lifetime kills -> promote to next ladder rank.
        // Staff / non-ladder ranks are never touched.
        try {
            if (!plugin.getConfig().getBoolean("auto-rankup", true)) return;
        } catch (Exception ex) {
            return;
        }
        try {
            List<String> ladder = getLadder();
            String cur = players.getString("players." + killer.getUniqueId());
            String curKey = cur == null ? null : findKey(cur);
            int curIdx = -1;
            if (curKey != null) {
                for (int i = 0; i < ladder.size(); i++) {
                    String lk = findKey(ladder.get(i));
                    if (lk != null && TextUtil.norm(lk).equals(TextUtil.norm(curKey))) { curIdx = i; break; }
                }
            } else {
                for (int i = 0; i < ladder.size(); i++) {
                    if (TextUtil.norm(ladder.get(i)).equals("member")) { curIdx = i; break; }
                }
            }
            if (curIdx < 0 || curIdx + 1 >= ladder.size()) return;
            String nextKey = findKey(ladder.get(curIdx + 1));
            if (nextKey == null) return;
            int need = rankupKills(nextKey);
            int have = plugin.getLeaderboard().lbKills(killer.getUniqueId());
            if (have < need) return;
            players.set("players." + killer.getUniqueId(), nextKey);
            savePlayers();
            try { lpSync(killer.getUniqueId(), curKey, nextKey); } catch (Exception ignored) {}
            plugin.getNametag().invalidateTag(killer);
            plugin.getNametag().applyRank(killer);
            String rankMsg = "§6§lRANK UP! §f" + killer.getName() + " §7is now §f[" + TextUtil.legacy(prefixOf(nextKey)) + "§f] §7(" + have + " kills)" + nextRankSuffix(ladder, curIdx + 1, have);
            Bukkit.broadcastMessage(rankMsg);
            try {
                if (plugin.getWebhook().wants("ranks")) plugin.getWebhook().send("\uD83C\uDFC6 " + rankMsg);
            } catch (Exception ignored) {}
            try {
                killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /** "Next: [RANK] at N kills (M more)" suffix for the rank-up broadcast. */
    private String nextRankSuffix(List<String> ladder, int newIdx, int have) {
        try {
            if (newIdx + 1 >= ladder.size()) return " §7(MAX RANK)";
            String nk = findKey(ladder.get(newIdx + 1));
            if (nk == null) return "";
            int need = rankupKills(nk);
            if (need == Integer.MAX_VALUE) return "";
            return " §7Next: §f[" + TextUtil.legacy(prefixOf(nk)) + "§f] §7at §f" + need
                    + " kills §7(§f" + Math.max(0, need - have) + "§7 more)";
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * /tp &lt;rank&gt; support: teleport the sender to an online holder of that rank.
     * Returns true when the command was handled (caller must cancel the event).
     */
    public boolean teleportToRank(Player sender, String rankName) {
        if (!sender.hasPermission("rank.admin")) return false;
        if (Bukkit.getPlayerExact(rankName) != null) return false; // real player: let vanilla /tp handle it
        String key = findKey(rankName);
        if (key == null) return false; // neither player nor rank: let vanilla show its error
        List<Player> holders = new ArrayList<>();
        try {
            if (players.getConfigurationSection("players") != null) {
                for (String uuid : players.getConfigurationSection("players").getKeys(false)) {
                    String r = players.getString("players." + uuid);
                    if (r == null || !TextUtil.norm(r).equals(TextUtil.norm(key))) continue;
                    Player p = null;
                    try {
                        p = Bukkit.getPlayer(UUID.fromString(uuid));
                    } catch (Exception ignored) {}
                    if (p != null && p.isOnline()) holders.add(p);
                }
            }
        } catch (Exception ex) {
            return false;
        }
        if (holders.isEmpty()) {
            sender.sendMessage("§cNo online players hold rank '" + displayName(key) + "'.");
            return true;
        }
        java.util.Collections.sort(holders, new java.util.Comparator<Player>() {
            @Override public int compare(Player a, Player b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        Player target = holders.get(0);
        try {
            sender.teleport(target.getLocation());
            sender.sendMessage("§aTeleported to " + target.getName() + " §7(rank '" + displayName(key) + "'"
                    + (holders.size() > 1 ? ", +" + (holders.size() - 1) + " other(s)" : "") + ").");
        } catch (Exception ex) {
            sender.sendMessage("§cTeleport failed.");
        }
        return true;
    }

    public String autoPrefix(String name) {
        // all 16 legacy colors + hex variants, so auto-created ranks cycle the
        // full color range instead of the old 8.
        String[] colors = {
            "&c", "&6", "&a", "&b", "&d", "&e", "&9", "&5",
            "&f", "&7", "&4", "&2", "&3", "&1", "&8", "&0",
            "&#FF5555", "&#55FF55", "&#5555FF", "&#FFFF55",
            "&#FF55FF", "&#55FFFF", "&#FFAA00", "&#AA00FF"
        };
        int n = rankKeys().size();
        return colors[n % colors.length] + name;
    }
}
