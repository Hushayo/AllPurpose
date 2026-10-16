package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class RankCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public RankCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (sender.hasPermission("rank.admin")) help(sender, label);
            else sendLadder(sender);
            return true;
        }
        String sub = TextUtil.norm(args[0]);
        if (sub.equals("ladder")) {
            sendLadder(sender);
            return true;
        }
        if (!sender.hasPermission("rank.admin")) {
            sender.sendMessage("§cNo permission (rank.admin).");
            return true;
        }
        if (sub.equals("create")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " create <name> [prefix...]");
                return true;
            }
            String name = args[1];
            // Allow the server owner's "unique chars" (+ - .) in rank names.
            // '.' is legal here because it gets encoded in the STORAGE KEY by
            // TextUtil.rankKey() - see that method for why (YAML path separator)
            // and why no two names can collide on one key (FIRE+ -> fire+,
            // FIRE -> fire, A.B -> a·b are all distinct).
            if (!name.matches("[A-Za-z0-9_+.-]{2,32}")) {
                sender.sendMessage("§cRank name must be 2-32 chars: letters, numbers, _ + - .");
                sender.sendMessage("§7For long titles: §f/rank create promoter §7then §f/rank setprefix promoter the guy that promotes smp...");
                sender.sendMessage("§7Color goes AFTER the name, e.g. §f/rank create Owner red §7| §f#FF5555 §7| §f&c");
                return true;
            }
            if (plugin.getRanks().findKey(name) != null) {
                sender.sendMessage("§cRank '" + name + "' already exists.");
                return true;
            }
            String prefix;
            if (args.length == 2) {
                prefix = plugin.getRanks().autoPrefix(name);
            } else if (args.length == 3) {
                // named color (red), raw code (&c), or hex (#FF5555) all work;
                // hex is stored as hex instead of snapping to the 16-color palette.
                String cc = TextUtil.colorCode(args[2]);
                if (cc == null) cc = TextUtil.explicitColor(args[2]);
                if (cc == null) cc = TextUtil.closestLegacy(args[2]);
                prefix = cc != null ? cc + name : TextUtil.join(args, 2);
            } else {
                prefix = TextUtil.join(args, 2);
            }
            // rankKey (not norm) so '.' can't split the YAML path - see TextUtil.rankKey.
            String key = TextUtil.rankKey(name);
            plugin.getRanks().ranksConfig().set("ranks." + key + ".name", name);
            plugin.getRanks().ranksConfig().set("ranks." + key + ".prefix", prefix);
            plugin.getRanks().saveRanks();
            sender.sendMessage("§aRank '" + name + "' created with prefix '[" + prefix + "]'.");
            sender.sendMessage("§7Give it: /" + label + " give <player> " + name);
            return true;
        }
        if (sub.equals("delete") || sub.equals("remove-rank") || sub.equals("del")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " delete <rank>");
                return true;
            }
            String key = plugin.getRanks().findKey(args[1]);
            if (key == null) {
                sender.sendMessage("§cUnknown rank: " + args[1]);
                return true;
            }
            plugin.getRanks().ranksConfig().set("ranks." + key, null);
            // unassign players holding it
            java.util.List<String> freed = new ArrayList<>();
            if (plugin.getRanks().playersConfig().getConfigurationSection("players") != null) {
                for (String uuid : plugin.getRanks().playersConfig().getConfigurationSection("players").getKeys(false)) {
                    String r = plugin.getRanks().playersConfig().getString("players." + uuid);
                    if (r != null && TextUtil.norm(r).equals(TextUtil.norm(key))) {
                        plugin.getRanks().playersConfig().set("players." + uuid, null);
                        freed.add(uuid);
                        try { plugin.getRanks().setFrozen(UUID.fromString(uuid), false); } catch (Exception ignored) {}
                        try {
                            plugin.getRanks().lpSync(UUID.fromString(uuid), key, null);
                        } catch (Exception ignored) {}
                    }
                }
            }
            // clear staff slots pointing at the deleted key so no dangling tags remain
            java.util.List<String> staffFreed = new ArrayList<>();
            try {
                if (plugin.getRanks().playersConfig().getConfigurationSection("staff") != null) {
                    for (String uuid : plugin.getRanks().playersConfig().getConfigurationSection("staff").getKeys(false)) {
                        String r = plugin.getRanks().playersConfig().getString("staff." + uuid);
                        if (r != null && TextUtil.norm(r).equals(TextUtil.norm(key))) {
                            plugin.getRanks().playersConfig().set("staff." + uuid, null);
                            staffFreed.add(uuid);
                        }
                    }
                }
            } catch (Exception ignored) {}
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    if (!freed.contains(p.getUniqueId().toString()) && !staffFreed.contains(p.getUniqueId().toString())) continue;
                    String tag = "";
                    try { tag = plugin.getRanks().fullTag(p); } catch (Exception ignored) {}
                    plugin.getNametag().invalidateTag(p);
                    if (tag == null || tag.isEmpty()) plugin.getNametag().clearLook(p);
                    else plugin.getNametag().applyRank(p);
                } catch (Exception ignored) {}
            }
            plugin.getRanks().saveRanks(); plugin.getRanks().savePlayers();
            sender.sendMessage("§aRank deleted.");
            return true;
        }
        if (sub.equals("list")) {
            List<String> names = plugin.getRanks().rankNames();
            if (names.isEmpty()) {
                sender.sendMessage("§7No ranks yet. Create one: /" + label + " create <name>");
            } else {
                // Each rank renders in its OWN color (prefix carries the word;
                // legacy() also expands <gradient> tags). §r stops color bleed.
                sender.sendMessage("§eRanks (" + names.size() + "):");
                List<String> shown = new ArrayList<>();
                for (String n : names) {
                    String k = plugin.getRanks().findKey(n);
                    String pre = k == null ? n : TextUtil.legacy(plugin.getRanks().prefixOf(k));
                    shown.add(pre + "§r");
                }
                sender.sendMessage(String.join("§7, ", shown));
            }
            return true;
        }
        if (sub.equals("give") || sub.equals("set")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " give <player> <rank> §7(or /" + label + " give <rank> for yourself)");
                return true;
            }
            String rankArg;
            Player target;
            if (args.length == 2) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cConsole must name a player: /" + label + " give <player> <rank>");
                    return true;
                }
                target = (Player) sender;
                rankArg = args[1];
            } else {
                rankArg = args[2];
                String rkey = plugin.getRanks().findKey(rankArg);
                if (rkey == null) {
                    sender.sendMessage("§cUnknown rank: " + rankArg + ". See /" + label + " list");
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    UUID id = plugin.getRanks().resolveUuid(args[1]);
                    if (id == null) {
                        sender.sendMessage("§cNo such player (never joined): " + args[1]);
                        return true;
                    }
                    String oldR = null;
                    try { oldR = plugin.getRanks().playersConfig().getString("players." + id); } catch (Exception ignored) {}
                    plugin.getRanks().playersConfig().set("players." + id, rkey);
                    plugin.getRanks().savePlayers();
                    try { plugin.getRanks().lpSync(id, oldR == null ? null : plugin.getRanks().findKey(oldR), rkey); } catch (Exception ignored) {}
                    sender.sendMessage("§aGave rank '" + plugin.getRanks().displayName(rkey) + "' to " + plugin.getRanks().lastKnownName(id) + " §7(offline — applies on next join).");
                    return true;
                }
            }
            String key = plugin.getRanks().findKey(rankArg);
            if (key == null) {
                sender.sendMessage("§cUnknown rank: " + rankArg + ". See /" + label + " list");
                return true;
            }
            String oldKey = null;
            try {
                String ro = plugin.getRanks().playersConfig().getString("players." + target.getUniqueId());
                oldKey = ro == null ? null : plugin.getRanks().findKey(ro);
            } catch (Exception ignored) {}
            plugin.getRanks().playersConfig().set("players." + target.getUniqueId(), key);
            plugin.getRanks().savePlayers();
            try { plugin.getRanks().lpSync(target.getUniqueId(), oldKey, key); } catch (Exception ignored) {}
            // Re-applying the SAME rank produces an identical tag string, so drop the
            // cache first or applyRank would skip the push and leave the tag missing.
            plugin.getNametag().invalidateTag(target);
            plugin.getNametag().applyRank(target);
            sender.sendMessage("§aGave rank '" + plugin.getRanks().displayName(key) + "' to " + target.getName() + ".");
            target.sendMessage("§aYou received rank '[" + TextUtil.legacy(plugin.getRanks().prefixOf(key)) + "§a]'.");
            return true;
        }
        if (sub.equals("upgrade")) {
            // Manual promotion the auto-ladder can never touch again: sets the
            // rank AND freezes the player (skips checkRankup + recalc).
            // Undo with /rank unfreeze <player>.
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /" + label + " upgrade <player> <rank> §7- recalc-proof promotion");
                return true;
            }
            String key = plugin.getRanks().findKey(args[2]);
            if (key == null) {
                sender.sendMessage("§cUnknown rank: " + args[2] + ". See /" + label + " list");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            UUID tid = target == null ? plugin.getRanks().resolveUuid(args[1]) : target.getUniqueId();
            if (tid == null) {
                sender.sendMessage("§cNo such player (never joined): " + args[1]);
                return true;
            }
            String oldU = null;
            try { oldU = plugin.getRanks().playersConfig().getString("players." + tid); } catch (Exception ignored) {}
            plugin.getRanks().playersConfig().set("players." + tid, key);
            plugin.getRanks().setFrozen(tid, true);
            plugin.getRanks().savePlayers();
            try { plugin.getRanks().lpSync(tid, oldU == null ? null : plugin.getRanks().findKey(oldU), key); } catch (Exception ignored) {}
            if (target == null) {
                sender.sendMessage("§aUpgraded " + plugin.getRanks().lastKnownName(tid) + " to '" + plugin.getRanks().displayName(key) + "' §7(frozen — recalc-proof, offline).");
                return true;
            }
            plugin.getNametag().invalidateTag(target);
            plugin.getNametag().applyRank(target);
            sender.sendMessage("§aUpgraded " + target.getName() + " to '" + plugin.getRanks().displayName(key) + "' §7(frozen — recalc-proof).");
            target.sendMessage("§aYou were upgraded to '[" + TextUtil.legacy(plugin.getRanks().prefixOf(key)) + "§a]' §7(recalc-proof).");
            return true;
        }
        if (sub.equals("unfreeze")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " unfreeze <player> §7- let recalc touch them again");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            UUID tid = target == null ? plugin.getRanks().resolveUuid(args[1]) : target.getUniqueId();
            if (tid == null) {
                sender.sendMessage("§cNo such player (never joined): " + args[1]);
                return true;
            }
            boolean was = false;
            try { was = plugin.getRanks().isFrozen(tid); } catch (Exception ignored) {}
            plugin.getRanks().setFrozen(tid, false);
            sender.sendMessage(was ? "§a" + args[1] + " unfrozen — recalc applies again." : "§7" + args[1] + " wasn't frozen.");
            return true;
        }
        if (sub.equals("staff")) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /" + label + " staff <player> <rank|none>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            UUID tid = target == null ? plugin.getRanks().resolveUuid(args[1]) : target.getUniqueId();
            if (tid == null) {
                sender.sendMessage("§cNo such player (never joined): " + args[1]);
                return true;
            }
            String v = TextUtil.norm(args[2]);
            if (v.equals("none") || v.equals("off") || v.equals("clear")) {
                try { plugin.getRanks().staffSet(tid, null); } catch (Exception ignored) {}
                if (target == null) {
                    sender.sendMessage("§aStaff tag for " + plugin.getRanks().lastKnownName(tid) + " cleared §7(offline).");
                    return true;
                }
                String tag = "";
                try { tag = plugin.getRanks().fullTag(target); } catch (Exception ignored) {}
                plugin.getNametag().invalidateTag(target);
                if (tag == null || tag.isEmpty()) plugin.getNametag().clearLook(target);
                else plugin.getNametag().applyRank(target);
                sender.sendMessage("§aStaff tag for " + target.getName() + " cleared.");
                return true;
            }
            String key = plugin.getRanks().findKey(args[2]);
            if (key == null) {
                sender.sendMessage("§cUnknown rank: " + args[2] + ". See /" + label + " list");
                return true;
            }
            try { plugin.getRanks().staffSet(tid, key); } catch (Exception ignored) {}
            if (target == null) {
                sender.sendMessage("§aStaff tag for " + plugin.getRanks().lastKnownName(tid) + " set to [" + TextUtil.legacy(plugin.getRanks().prefixOf(key)) + "§a] §7(offline — applies on next join).");
                return true;
            }
            plugin.getNametag().invalidateTag(target);
            plugin.getNametag().applyRank(target);
            sender.sendMessage("§aStaff tag for " + target.getName() + " set to [" + TextUtil.legacy(plugin.getRanks().prefixOf(key)) + "§a].");
            target.sendMessage("§aYour staff tag is now '[" + TextUtil.legacy(plugin.getRanks().prefixOf(key)) + "§a]'.");
            return true;
        }
        if (sub.equals("lpsync") || sub.equals("syncgroups")) {
            if (!sender.hasPermission("rank.admin")) {
                sender.sendMessage("§cNo permission (rank.admin).");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " lpsync <player|all> §7- mirror hushrank to LP group");
                return true;
            }
            // Backfill: (re)assert LP parents for current ranks. Additive only;
            // removals happen automatically on future rank changes.
            if (TextUtil.norm(args[1]).equals("all")) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                int n = 0;
                try {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        try {
                            String rk = plugin.getRanks().playersConfig().getString("players." + p.getUniqueId());
                            String k = rk == null ? null : plugin.getRanks().findKey(rk);
                            if (k != null) { plugin.getRanks().lpSync(p.getUniqueId(), null, k); n++; }
                            seen.add(p.getUniqueId().toString());
                        } catch (Exception ignored) {}
                    }
                    if (plugin.getRanks().playersConfig().getConfigurationSection("players") != null) {
                        for (String u : plugin.getRanks().playersConfig().getConfigurationSection("players").getKeys(false)) {
                            try {
                                if (u == null || !seen.add(u)) continue;
                                UUID id = UUID.fromString(u);
                                String rk = plugin.getRanks().playersConfig().getString("players." + u);
                                String k = rk == null ? null : plugin.getRanks().findKey(rk);
                                if (k != null) { plugin.getRanks().lpSync(id, null, k); n++; }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
                sender.sendMessage("§aLP sync queued for " + n + " player(s). Confirm: /lp user <name> info");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            UUID tid = target == null ? plugin.getRanks().resolveUuid(args[1]) : target.getUniqueId();
            if (tid == null) {
                sender.sendMessage("§cNo such player (never joined): " + args[1]);
                return true;
            }
            String rk = null;
            try { rk = plugin.getRanks().playersConfig().getString("players." + tid); } catch (Exception ignored) {}
            String k = rk == null ? null : plugin.getRanks().findKey(rk);
            if (k == null) {
                sender.sendMessage("§7" + args[1] + " has no rank to sync.");
                return true;
            }
            try { plugin.getRanks().lpSync(tid, null, k); } catch (Exception ignored) {}
            sender.sendMessage("§aLP sync queued: " + args[1] + " -> group " + k + ". Confirm: /lp user " + args[1] + " info");
            return true;
        }
        if (sub.equals("remove") || sub.equals("unassign") || sub.equals("take")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " remove <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            UUID tid = target == null ? plugin.getRanks().resolveUuid(args[1]) : target.getUniqueId();
            if (tid == null) {
                sender.sendMessage("§cNo such player (never joined): " + args[1]);
                return true;
            }
            String oldRm = null;
            try {
                String ro = plugin.getRanks().playersConfig().getString("players." + tid);
                oldRm = ro == null ? null : plugin.getRanks().findKey(ro);
            } catch (Exception ignored) {}
            plugin.getRanks().playersConfig().set("players." + tid, null);
            plugin.getRanks().setFrozen(tid, false); // rank gone: nothing left to protect
            plugin.getRanks().savePlayers();
            try { plugin.getRanks().lpSync(tid, oldRm, null); } catch (Exception ignored) {}
            if (target == null) {
                sender.sendMessage("§aRemoved rank from " + plugin.getRanks().lastKnownName(tid) + " §7(offline).");
                return true;
            }
            // Staff slot is independent: leave it alone, keep showing it if one remains.
            String tag = "";
            try { tag = plugin.getRanks().fullTag(target); } catch (Exception ignored) {}
            plugin.getNametag().invalidateTag(target);
            if (tag == null || tag.isEmpty()) plugin.getNametag().clearLook(target);
            else plugin.getNametag().applyRank(target);
            sender.sendMessage("§aRemoved rank from " + target.getName() + ".");
            return true;
        }
        if (sub.equals("setprefix")) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /" + label + " setprefix <rank> <prefix...>");
                return true;
            }
            String key = plugin.getRanks().findKey(args[1]);
            if (key == null) {
                sender.sendMessage("§cUnknown rank: " + args[1]);
                return true;
            }
            String prefix = TextUtil.join(args, 2);
            plugin.getRanks().ranksConfig().set("ranks." + key + ".prefix", prefix);
            plugin.getRanks().saveRanks();
            for (Player p : Bukkit.getOnlinePlayers()) {
                String rk = null;
                try { rk = plugin.getRanks().playersConfig().getString("players." + p.getUniqueId()); } catch (Exception ignored) {}
                if (rk != null && TextUtil.norm(rk).equals(TextUtil.norm(key))) { plugin.getNametag().invalidateTag(p); plugin.getNametag().applyRank(p); }
            }
            sender.sendMessage("§aPrefix of '" + plugin.getRanks().displayName(key) + "' set to '[" + prefix + "]'.");
            return true;
        }
        if (sub.equals("color") || sub.equals("colour")) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /" + label + " color <rank> <color...> | gradient <c1> <c2> [bold/italic/...]");
                sender.sendMessage("§7Colors: red, blue, #FF5555, &c ... e.g. §f/rank color Boss red bold");
                sender.sendMessage("§7Gradient e.g. §f/rank color FIREPLUS gradient #FF5500 #FFCC00 bold");
                return true;
            }
            String key = plugin.getRanks().findKey(args[1]);
            if (key == null) {
                sender.sendMessage("§cUnknown rank: " + args[1]);
                return true;
            }
            String word = plugin.getRanks().displayName(key);
            String prefix;
            if (TextUtil.norm(args[2]).equals("gradient")) {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: /" + label + " color <rank> gradient <color1> <color2> [formats...]");
                    return true;
                }
                String h1 = TextUtil.resolveHex(args[3]);
                String h2 = TextUtil.resolveHex(args[4]);
                if (h1 == null || h2 == null) {
                    sender.sendMessage("§cBad gradient color. Use names (red), &c codes, or hex (#FF5555).");
                    return true;
                }
                StringBuilder fmts = new StringBuilder();
                for (int i = 5; i < args.length; i++) {
                    char fc = TextUtil.formatChar(args[i]);
                    if (fc != 0) fmts.append('&').append(fc);
                }
                prefix = "<gradient:#" + h1 + ":#" + h2 + ">" + fmts + word + "</gradient>";
            } else {
                String cc = TextUtil.colorCode(args[2]);
                if (cc == null) cc = TextUtil.explicitColor(args[2]);
                if (cc == null) cc = TextUtil.closestLegacy(args[2]);
                if (cc == null) {
                    sender.sendMessage("§cUnknown color: " + args[2] + " §7(red, &c, #FF5555 ...)");
                    return true;
                }
                StringBuilder fmts = new StringBuilder();
                for (int i = 3; i < args.length; i++) {
                    char fc = TextUtil.formatChar(args[i]);
                    if (fc != 0) fmts.append('&').append(fc);
                }
                prefix = cc + fmts + word;
            }
            plugin.getRanks().ranksConfig().set("ranks." + key + ".prefix", prefix);
            plugin.getRanks().saveRanks();
            for (Player p : Bukkit.getOnlinePlayers()) {
                String rk = null;
                try { rk = plugin.getRanks().playersConfig().getString("players." + p.getUniqueId()); } catch (Exception ignored) {}
                if (rk != null && TextUtil.norm(rk).equals(TextUtil.norm(key))) { plugin.getNametag().invalidateTag(p); plugin.getNametag().applyRank(p); }
            }
            sender.sendMessage("§aColor of '" + word + "' set. Preview: [" + TextUtil.legacy(prefix) + "§r]");
            return true;
        }
        if (sub.equals("info")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " info <player|rank>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target != null) {
                String key = null;
                try {
                    String r = plugin.getRanks().playersConfig().getString("players." + target.getUniqueId());
                    key = r == null ? null : plugin.getRanks().findKey(r);
                } catch (Exception ignored) {}
                if (key == null) sender.sendMessage("§7" + target.getName() + " has no rank.");
                else sender.sendMessage("§e" + target.getName() + ": §f[" + plugin.getRanks().prefixOf(key) + "] " + plugin.getRanks().displayName(key));
                return true;
            }
            String key = plugin.getRanks().findKey(args[1]);
            if (key == null) {
                sender.sendMessage("§cNo player or rank: " + args[1]);
                return true;
            }
            sender.sendMessage("§eRank " + plugin.getRanks().displayName(key) + ": prefix '[" + plugin.getRanks().prefixOf(key) + "]'");
            return true;
        }
        if (sub.equals("recalc")) {
            try {
                List<UUID> ids = new ArrayList<>();
                if (args.length < 2 || TextUtil.norm(args[1]).equals("all")) {
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    try {
                        if (plugin.getRanks().playersConfig().getConfigurationSection("players") != null) {
                            for (String u : plugin.getRanks().playersConfig().getConfigurationSection("players").getKeys(false)) {
                                if (u != null && seen.add(u)) {
                                    try { ids.add(UUID.fromString(u)); } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    try {
                        if (plugin.getLeaderboard().lbConfig().getConfigurationSection("stats") != null) {
                            for (String u : plugin.getLeaderboard().lbConfig().getConfigurationSection("stats").getKeys(false)) {
                                if (u != null && seen.add(u)) {
                                    try { ids.add(UUID.fromString(u)); } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                } else {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("§cPlayer not online: " + args[1]);
                        return true;
                    }
                    ids.add(target.getUniqueId());
                }
                List<String> lines = new ArrayList<>();
                List<UUID> changed = new ArrayList<>();
                for (UUID id : ids) {
                    try {
                        String line = plugin.getRanks().recalcOne(id);
                        if (line != null) { lines.add(line); changed.add(id); }
                    } catch (Exception ignored) {}
                }
                plugin.getRanks().savePlayers();
                for (UUID id : changed) {
                    try {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) { plugin.getNametag().invalidateTag(p); plugin.getNametag().applyRank(p); }
                    } catch (Exception ignored) {}
                }
                sender.sendMessage("§aRecalculated " + ids.size() + " player(s), " + lines.size() + " corrected.");
                for (int i = 0; i < lines.size() && i < 10; i++) sender.sendMessage("§7" + lines.get(i));
                if (lines.size() > 10) sender.sendMessage("§7...and " + (lines.size() - 10) + " more");
                return true;
            } catch (Exception ignored) {}
            return true;
        }
        if (sub.equals("rename")) {
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /" + label + " rename <old> <new>");
                return true;
            }
            String key = plugin.getRanks().findKey(args[1]);
            if (key == null) {
                sender.sendMessage("§cUnknown rank: " + args[1]);
                return true;
            }
            String newName = args[2];
            // Same relaxed rule as create (+ - . allowed; '.' safe via rankKey encoding).
            if (!newName.matches("[A-Za-z0-9_+.-]{2,32}")) {
                sender.sendMessage("§cRank name must be 2-32 chars: letters, numbers, _ + - .");
                return true;
            }
            String clash = plugin.getRanks().findKey(newName);
            if (clash != null && !TextUtil.norm(clash).equals(TextUtil.norm(key))) {
                sender.sendMessage("§cRank '" + newName + "' already exists.");
                return true;
            }
            String oldDisplay = plugin.getRanks().displayName(key);
            String newKey = TextUtil.rankKey(newName);
            String holderKey = key;
            try {
                if (newKey.equals(TextUtil.norm(key))) {
                    plugin.getRanks().ranksConfig().set("ranks." + key + ".name", newName);
                    plugin.getRanks().saveRanks();
                } else {
                    String prefix = plugin.getRanks().prefixOf(key);
                    plugin.getRanks().ranksConfig().set("ranks." + newKey + ".name", newName);
                    plugin.getRanks().ranksConfig().set("ranks." + newKey + ".prefix", prefix);
                    plugin.getRanks().ranksConfig().set("ranks." + key, null);
                    if (plugin.getRanks().playersConfig().getConfigurationSection("players") != null) {
                        for (String uuid : plugin.getRanks().playersConfig().getConfigurationSection("players").getKeys(false)) {
                            String r = plugin.getRanks().playersConfig().getString("players." + uuid);
                            if (r != null && TextUtil.norm(r).equals(TextUtil.norm(key))) {
                                plugin.getRanks().playersConfig().set("players." + uuid, newKey);
                                try {
                                    plugin.getRanks().lpSync(UUID.fromString(uuid), key, newKey);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    plugin.getRanks().saveRanks();
                    plugin.getRanks().savePlayers();
                    holderKey = newKey;
                }
            } catch (Exception ignored) {}
            for (Player p : Bukkit.getOnlinePlayers()) {
                String rk = null;
                try { rk = plugin.getRanks().playersConfig().getString("players." + p.getUniqueId()); } catch (Exception ignored) {}
                if (rk != null && TextUtil.norm(rk).equals(TextUtil.norm(holderKey))) { plugin.getNametag().invalidateTag(p); plugin.getNametag().applyRank(p); }
            }
            sender.sendMessage("§aRank '" + oldDisplay + "' renamed to '" + newName + "'.");
            return true;
        }
        help(sender, label);
        return true;
    }

    private void sendLadder(CommandSender sender) {
        // Player-facing: how to level up the auto-rank ladder.
        try {
            sender.sendMessage("§6§lRANK LADDER §7- get kills to climb:");
            List<String> ladder = plugin.getRanks().getLadder();
            String curKey = null;
            int have = 0;
            UUID id = null;
            if (sender instanceof Player) {
                Player p = (Player) sender;
                id = p.getUniqueId();
                try {
                    String r = plugin.getRanks().playersConfig().getString("players." + id);
                    curKey = r == null ? null : plugin.getRanks().findKey(r);
                } catch (Exception ignored) {}
                try { have = plugin.getLeaderboard().lbKills(id); } catch (Exception ignored) {}
            }
            for (String entry : ladder) {
                String key = plugin.getRanks().findKey(entry);
                String name = key == null ? entry : plugin.getRanks().displayName(key);
                boolean you = key != null && curKey != null && TextUtil.norm(key).equals(TextUtil.norm(curKey));
                int need = key == null ? Integer.MAX_VALUE : plugin.getRanks().rankupKills(key);
                String needStr = need == Integer.MAX_VALUE ? "start" : need + " kills";
                sender.sendMessage((you ? "§a➤ " : "§7▪ ") + "§f" + name + " §8- §7" + needStr);
            }
            if (id != null) {
                String nextName = null;
                int nextNeed = Integer.MAX_VALUE;
                boolean pastCur = curKey == null;
                for (String entry : ladder) {
                    String key = plugin.getRanks().findKey(entry);
                    if (key == null) continue;
                    if (!pastCur) {
                        if (TextUtil.norm(key).equals(TextUtil.norm(curKey))) pastCur = true;
                        continue;
                    }
                    int need = plugin.getRanks().rankupKills(key);
                    if (need != Integer.MAX_VALUE) {
                        nextName = plugin.getRanks().displayName(key);
                        nextNeed = need;
                        break;
                    }
                }
                if (nextName == null) {
                    sender.sendMessage("§7You: §f" + have + " kills §7(MAX RANK)");
                } else {
                    sender.sendMessage("§7You: §f" + have + " kills §8- §7next: §f" + nextName + " §7at §f" + nextNeed + " kills");
                }
            }
        } catch (Exception ignored) {}
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§eRanks — 3 steps:");
        sender.sendMessage("§71. /" + label + " create <name> [color] §8- e.g. /rank create Owner red");
        sender.sendMessage("§72. /" + label + " give <player> <rank> §8(or /" + label + " give <rank> for yourself)");
        sender.sendMessage("§73. /" + label + " list §8- show all ranks");
        sender.sendMessage("§74. /" + label + " ladder §8- kills needed per rank");
        sender.sendMessage("§8Extra: remove, setprefix, color, lpsync, delete, info, recalc, rename, staff, upgrade, unfreeze");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("rank.admin")) {
            if (args.length == 1 && "ladder".startsWith(TextUtil.norm(args[0]))) out.add("ladder");
            return out;
        }
        if (args.length == 1) {
            for (String s : Arrays.asList("create", "give", "remove", "setprefix", "color", "lpsync", "delete", "list", "info", "recalc", "rename", "staff", "upgrade", "unfreeze", "ground", "mace", "spear"))
                if (s.startsWith(TextUtil.norm(args[0]))) out.add(s);
            return out;
        }
        if (args.length == 3 && TextUtil.norm(args[0]).equals("create")) {
            String[] colors = {"red", "dark_red", "gold", "yellow", "green", "dark_green", "aqua", "dark_aqua", "blue", "dark_blue", "pink", "purple", "gray", "dark_gray", "black", "white"};
            for (String c : colors)
                if (c.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(c);
            return out;
        }
        String sub = TextUtil.norm(args[0]);
        if (args.length == 2 && (sub.equals("give") || sub.equals("remove") || sub.equals("recalc") || sub.equals("staff") || sub.equals("lpsync") || sub.equals("upgrade") || sub.equals("unfreeze"))) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            for (String n : plugin.getRanks().knownPlayerNames())
                if (n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)) && !out.contains(n)) out.add(n);
            return out;
        }
        if (args.length == 3 && sub.equals("staff")) {
            String part = args[2].toLowerCase(Locale.ROOT);
            for (String n : plugin.getRanks().rankNames())
                if (n.toLowerCase(Locale.ROOT).startsWith(part)) out.add(n);
            if ("none".startsWith(part)) out.add("none");
            return out;
        }
        if ((args.length == 3 && (sub.equals("give") || sub.equals("set") || sub.equals("upgrade")))
                || (args.length == 2 && (sub.equals("delete") || sub.equals("del") || sub.equals("setprefix") || sub.equals("color") || sub.equals("info") || sub.equals("rename")))) {
            String part = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (String n : plugin.getRanks().rankNames())
                if (n.toLowerCase(Locale.ROOT).startsWith(part)) out.add(n);
            return out;
        }
        return out;
    }
}
