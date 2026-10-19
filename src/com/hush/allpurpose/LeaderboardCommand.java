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
            String sub = TextUtil.norm(args[0]);
            String arg = args.length >= 2 ? TextUtil.norm(args[1]) : "";
            boolean all = arg.isEmpty() || arg.equals("all");
            String type = LeaderboardManager.holoType(arg);
            if (!all && type == null) {
                sender.sendMessage("§cUnknown board '§f" + args[1] + "§c'. Boards: §f"
                        + String.join("§7, §f", LeaderboardManager.HOLO_TYPES) + "§c, §fall§c.");
                return true;
            }
            if (sub.equals("holo")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cRun /" + label + " holo <board> in-game, standing where the board should float.");
                    return true;
                }
                Player p = (Player) sender;
                Location eye = p.getLocation();
                // Orientation is baked at placement (billboard FIXED), so the
                // board faces exactly the way the placer was looking.
                float yaw = eye.getYaw();
                java.util.List<String> placing = all
                        ? Arrays.asList(LeaderboardManager.HOLO_TYPES)
                        : Arrays.asList(type);
                double cursor = eye.getY();
                for (String t : placing) {
                    // Lift by half the board height so its bottom edge sits at
                    // the placer's feet; each board then stacks above the last.
                    int lines = plugin.getLeaderboard().holoLineCount(t);
                    double lift = Math.max(1, lines) * 0.15;
                    Location at = new Location(eye.getWorld(), eye.getX(), cursor + lift, eye.getZ(), yaw, 0f);
                    plugin.getLeaderboard().setHoloLoc(t, at);
                    plugin.getLeaderboard().saveLb();
                    plugin.getLeaderboard().respawnHolo(t);
                    cursor += lift * 2.0 + 0.5;
                }
                if (all) sender.sendMessage("§aPlaced §f" + placing.size() + " §aleaderboard holograms here, stacked (§ffixed position, facing you§a).");
                else sender.sendMessage("§aPlaced the §f" + type + " §ahologram here (§ffixed position, facing you§a).");
                return true;
            }
            plugin.getLeaderboard().removeHolo(all ? null : type);
            sender.sendMessage(all ? "§aAll leaderboard holograms removed."
                    : "§aRemoved the §f" + type + " §ahologram.");
            return true;
        }
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cUsage: /" + label + " [page] §7| /" + label + " holo [killtop|baltop|playtimetop|deathtop|all] | /" + label + " removeholo [board|all]");
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
        if (!sender.hasPermission("rank.admin")) return lbOut;
        String sub = args.length >= 1 ? TextUtil.norm(args[0]) : "";
        if (args.length == 1) {
            for (String s : Arrays.asList("holo", "removeholo"))
                if (s.startsWith(TextUtil.norm(args[0]))) lbOut.add(s);
        } else if (args.length == 2
                && (sub.equals("holo") || sub.equals("removeholo") || sub.equals("delholo"))) {
            for (String s : Arrays.asList("killtop", "baltop", "playtimetop", "deathtop", "all"))
                if (s.startsWith(TextUtil.norm(args[1]))) lbOut.add(s);
        }
        return lbOut;
    }
}
