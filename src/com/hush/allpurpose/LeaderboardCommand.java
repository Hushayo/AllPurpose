package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public LeaderboardCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && (TextUtil.norm(args[0]).equals("holo") || TextUtil.norm(args[0]).equals("removeholo") || TextUtil.norm(args[0]).equals("delholo"))) {
            if (!sender.hasPermission("rank.admin")) {
                sender.sendMessage("§cNo permission (rank.admin).");
                return true;
            }
            if (TextUtil.norm(args[0]).equals("holo")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cRun /" + label + " holo in-game, standing where the board should float.");
                    return true;
                }
                Location at = ((Player) sender).getLocation();
                // Text renders centered on the entity: lift by ~half the board
                // height so the bottom edge sits at the placer's feet.
                try {
                    String txt = plugin.getLeaderboard().buildHoloString();
                    int lines = Math.max(1, txt.split("\n", -1).length);
                    at = at.clone().add(0.0, lines * 0.15, 0.0);
                } catch (Exception ignored) {}
                plugin.getLeaderboard().lbConfig().set("holo.world", at.getWorld().getName());
                plugin.getLeaderboard().lbConfig().set("holo.x", at.getX());
                plugin.getLeaderboard().lbConfig().set("holo.y", at.getY());
                plugin.getLeaderboard().lbConfig().set("holo.z", at.getZ());
                plugin.getLeaderboard().saveLb();
                plugin.getLeaderboard().respawnHolo();
                sender.sendMessage("§aLeaderboard hologram placed here.");
                return true;
            }
            plugin.getLeaderboard().removeHolo();
            sender.sendMessage("§aLeaderboard hologram removed.");
            return true;
        }
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cUsage: /" + label + " [page] §7| /" + label + " holo | /" + label + " removeholo");
                return true;
            }
        }
        List<LeaderboardManager.LbRow> rows = plugin.getLeaderboard().lbTop();
        int perPage = 10;
        int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        if (page < 1) page = 1;
        if (page > pages) page = pages;
        sender.sendMessage("§6§l⚔ LEADERBOARD §7(page " + page + "/" + pages + ")");
        if (rows.isEmpty()) {
            sender.sendMessage("§7No kills yet. Go fight.");
        } else {
            for (int i = (page - 1) * perPage; i < Math.min(rows.size(), page * perPage); i++) {
                LeaderboardManager.LbRow r = rows.get(i);
                String rank = null;
                try { rank = plugin.getRanks().rankDisplayName(UUID.fromString(r.uuid)); } catch (Exception ignored) {}
                String tag = (rank == null || rank.isEmpty()) ? "" : " §7[" + rank + "]";
                sender.sendMessage("§e#" + (i + 1) + " §f" + r.name + tag + " §7- §cKills:" + r.kills + " §7Deaths:" + r.deaths
                        + " §bPlaytime:" + plugin.getLeaderboard().formatPlaytime(plugin.getLeaderboard().playtimeMinutes(r.uuid)));
            }
        }
        if (sender instanceof Player) {
            Player p = (Player) sender;
            int k = plugin.getLeaderboard().lbKills(p.getUniqueId());
            int d = plugin.getLeaderboard().lbDeaths(p.getUniqueId());
            String rank = plugin.getRanks().rankDisplayName(p.getUniqueId());
            String tag = (rank == null || rank.isEmpty()) ? "" : " §7[" + rank + "]";
            sender.sendMessage("§7You:" + tag + " §cKills:" + k + " §7Deaths:" + d + " §bPlaytime:" + plugin.getLeaderboard().formatPlaytime(plugin.getLeaderboard().playtimeMinutes(p.getUniqueId().toString())));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> lbOut = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("rank.admin")) {
            for (String s : Arrays.asList("holo", "removeholo"))
                if (s.startsWith(TextUtil.norm(args[0]))) lbOut.add(s);
        }
        return lbOut;
    }
}
