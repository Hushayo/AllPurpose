package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /bounty
 *   /bounty                       -> GUI board (biggest bounty first)
 *   /bounty <player>              -> GUI, adding to that player's bounty
 *   /bounty <player> <amount>     -> place a bounty straight away
 *   /bounty top                   -> text list (works from console)
 *   /bounty clear <player>        -> admin: refund + remove
 *   /bounty check                 -> admin: economy hook + totals
 */
public class BountyCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public BountyCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        BountyManager m = plugin.getBounty();
        boolean admin = sender.hasPermission("allpurpose.bounty.admin");

        if (args.length >= 1 && TextUtil.norm(args[0]).equals("check")) {
            if (!admin) { sender.sendMessage("§cNo permission (allpurpose.bounty.admin)."); return true; }
            Economy e = m.economy();
            sender.sendMessage("§6[Bounty] §7economy: " + (e == null ? "§cNOT HOOKED" : "§a" + e.getName())
                    + "§7, hunted players: §f" + m.top().size()
                    + "§7, min: §f" + Menus.plain(m.minBounty()) + "§7, max: §f" + Menus.plain(m.maxBounty())
                    + "§7, tax: §f" + m.taxPercent() + "%");
            for (BountyManager.Bounty b : m.top()) {
                sender.sendMessage("§7- §f" + b.targetName + " §7total §f" + Menus.plain(b.total())
                        + "§7 from §f" + b.contributors.size() + "§7 contributor(s)");
            }
            return true;
        }

        if (args.length >= 1 && TextUtil.norm(args[0]).equals("clear")) {
            if (!admin) { sender.sendMessage("§cNo permission (allpurpose.bounty.admin)."); return true; }
            if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " clear <player>"); return true; }
            OfflinePlayer target = null;
            try { target = Bukkit.getOfflinePlayer(args[1]); } catch (Exception ignored) {}
            if (target == null) { sender.sendMessage("§cUnknown player."); return true; }
            String name = target.getName() == null ? args[1] : target.getName();
            if (!m.clear(sender instanceof Player ? (Player) sender : null, name, target.getUniqueId())) {
                sender.sendMessage("§7No bounty on " + name + ".");
            }
            return true;
        }

        if (args.length >= 1 && (TextUtil.norm(args[0]).equals("top") || TextUtil.norm(args[0]).equals("list"))) {
            List<BountyManager.Bounty> list = m.top();
            sender.sendMessage("§6§l☠ BOUNTIES §7(" + list.size() + ")");
            if (list.isEmpty()) { sender.sendMessage("§7No active bounties."); return true; }
            int i = 0;
            for (BountyManager.Bounty b : list) {
                if (i++ >= 10) break;
                sender.sendMessage("§e#" + i + " §f" + b.targetName + " §7- §a" + Menus.plain(b.total())
                        + " §7(" + b.contributors.size() + " backer(s))");
            }
            double total = 0;
            for (BountyManager.Bounty b : list) total += b.total();
            sender.sendMessage("§7Total on offer: §a" + Menus.plain(total));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cPlayers only here. Try /" + label + " top or /" + label + " check.");
            return true;
        }
        final Player p = (Player) sender;
        if (!p.hasPermission("allpurpose.bounty")) {
            p.sendMessage("§cNo permission (allpurpose.bounty).");
            return true;
        }
        if (!m.enabled()) {
            p.sendMessage("§cBounties are disabled.");
            return true;
        }

        if (args.length >= 2) {
            OfflinePlayer target = null;
            try { target = Bukkit.getOfflinePlayer(args[0]); } catch (Exception ignored) {}
            if (target == null) { p.sendMessage("§cUnknown player: " + args[0]); return true; }
            Double amount = Menus.parseAmount(args[1]);
            if (amount == null) { p.sendMessage("§cUsage: /" + label + " <player> <amount>"); return true; }
            String name = target.getName() == null ? args[0] : target.getName();
            m.place(p, target.getUniqueId(), name, amount);
            return true;
        }

        if (args.length == 1) {
            OfflinePlayer target = null;
            try { target = Bukkit.getOfflinePlayer(args[0]); } catch (Exception ignored) {}
            if (target == null) { p.sendMessage("§cUnknown player: " + args[0]); return true; }
            if (target.getUniqueId().equals(p.getUniqueId())) {
                p.sendMessage("§cYou cannot put a bounty on yourself.");
                return true;
            }
            String name = target.getName() == null ? args[0] : target.getName();
            BountyGui.openAdd(p, plugin, target.getUniqueId(), name);
            return true;
        }

        BountyGui.openMain(p, plugin);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pfx = TextUtil.norm(args[0]);
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (TextUtil.norm(pl.getName()).startsWith(pfx)) out.add(pl.getName());
            }
            for (String s : new String[]{"top", "list"}) if (s.startsWith(pfx)) out.add(s);
            if (sender.hasPermission("allpurpose.bounty.admin")) {
                for (String s : new String[]{"clear", "check"}) if (s.startsWith(pfx)) out.add(s);
            }
        } else if (args.length == 2 && TextUtil.norm(args[0]).equals("clear") && sender.hasPermission("allpurpose.bounty.admin")) {
            String pfx = TextUtil.norm(args[1]);
            for (BountyManager.Bounty b : plugin.getBounty().top()) {
                if (b.targetName != null && TextUtil.norm(b.targetName).startsWith(pfx)) out.add(b.targetName);
            }
        } else if (args.length == 2) {
            for (String s : new String[]{"100", "1000", "10000"}) if (s.startsWith(args[1])) out.add(s);
        }
        return out;
    }
}
