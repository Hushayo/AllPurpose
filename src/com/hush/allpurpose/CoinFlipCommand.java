package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /coinflip (alias /cf)
 *   /coinflip              -> GUI lobby
 *   /coinflip <amount>     -> open a flip straight away (999, 1.5k, 2m)
 *   /coinflip cancel       -> refund your open flip
 *   /coinflip check        -> admin: economy hook + open flip count
 */
public class CoinFlipCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public CoinFlipCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        CoinFlipManager m = plugin.getCoinflip();
        if (args.length >= 1 && TextUtil.norm(args[0]).equals("check")) {
            if (!sender.hasPermission("allpurpose.coinflip.admin")) {
                sender.sendMessage("§cNo permission (allpurpose.coinflip.admin).");
                return true;
            }
            Economy e = m.economy();
            sender.sendMessage("§6[Coinflip] §7economy: " + (e == null ? "§cNOT HOOKED" : "§a" + e.getName())
                    + "§7, open flips: §f" + m.open().size()
                    + "§7, min: §f" + Menus.plain(m.minWager()) + "§7, max: §f" + Menus.plain(m.maxWager())
                    + "§7, tax: §f" + m.taxPercent() + "%");
            for (CoinFlipManager.Flip f : m.open()) {
                sender.sendMessage("§7- §f" + f.creatorName + " §7wagers §f" + Menus.plain(f.wager)
                        + "§7 (id " + f.id.toString().substring(0, 8) + ")");
            }
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cPlayers only. Try /" + label + " check.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("allpurpose.coinflip")) {
            p.sendMessage("§cNo permission (allpurpose.coinflip).");
            return true;
        }
        if (!m.enabled()) {
            p.sendMessage("§cCoinflips are disabled.");
            return true;
        }
        if (args.length >= 1) {
            String a = TextUtil.norm(args[0]);
            if (a.equals("cancel") || a.equals("c")) {
                m.cancel(p);
                return true;
            }
            Double amount = Menus.parseAmount(args[0]);
            if (amount == null) {
                p.sendMessage("§cUsage: §f/" + label + " [amount] §7| §f/" + label + " cancel");
                return true;
            }
            m.create(p, amount);
            return true;
        }
        CoinFlipGui.openMain(p, plugin);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"cancel", "100", "1000", "10000"}) {
                if (s.startsWith(TextUtil.norm(args[0]))) out.add(s);
            }
            if (sender.hasPermission("allpurpose.coinflip.admin") && "check".startsWith(TextUtil.norm(args[0]))) out.add("check");
        }
        return out;
    }
}
