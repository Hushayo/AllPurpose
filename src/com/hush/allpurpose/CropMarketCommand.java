package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Controls for the crops market (the supply-driven crop shop sell prices).
 *
 * Admin only (allpurpose.cropsmarket.admin): {@code /cropsmarket info} prints
 * every crop with its base price, current multiplier and the volume that drove
 * it; {@code review} forces a pricing pass now instead of waiting for the
 * window; {@code reset} is the "undo the automation" switch - every multiplier
 * back to 1x and every price we moved written back to its base.
 */
public class CropMarketCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public CropMarketCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "info" : TextUtil.norm(args[0]);
        if (sub.equals("help") || sub.equals("?")) {
            usage(sender, label);
            return true;
        }
        if (!sender.hasPermission("allpurpose.cropsmarket.admin")) {
            sender.sendMessage("§cNo permission (allpurpose.cropsmarket.admin).");
            return true;
        }
        CropMarket m = plugin.getCropMarket();
        if (m == null) {
            sender.sendMessage("§cCrops market not initialized.");
            return true;
        }

        if (sub.equals("info") || sub.equals("rates")) {
            List<String> lines = m.infoLines();
            sender.sendMessage("§6§l✦ Crop market §r§7(sell prices follow supply)");
            for (String l : lines) sender.sendMessage("§7" + l);
            return true;
        }

        if (sub.equals("review")) {
            m.reviewNow();
            sender.sendMessage("§aReview queued. §7Prices are written through /editshop + /sreload; see the console.");
            return true;
        }

        if (sub.equals("reset")) {
            m.reset();
            sender.sendMessage("§aEvery multiplier is back to §f1x§a, and moved prices are being restored to base via §f/editshop§a + §f/sreload§a.");
            return true;
        }

        usage(sender, label);
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage("§6/cropsmarket §7info §f- crops, multipliers and the volume behind them");
        sender.sendMessage("§6/cropsmarket §7review §f- run a pricing pass now");
        sender.sendMessage("§6/cropsmarket §7reset §f- multipliers back to 1x (re-base on the shop file)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 1) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList("info", "review", "reset", "help")) {
            if (s.startsWith(args[0].toLowerCase())) out.add(s);
        }
        return out;
    }
}
