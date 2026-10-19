package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class AllPurposeCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public AllPurposeCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("anchorfix.admin")) {
            sender.sendMessage("§cNo permission (anchorfix.admin).");
            return true;
        }
        if (args.length == 0 || TextUtil.norm(args[0]).equals("info")) {
            sender.sendMessage("§eAllPurpose §7(ranks-only build)");
            sender.sendMessage("§7Ranks: §f" + plugin.getRanks().rankNames().size()
                    + " §8| §7Ladder: §f" + String.join(" ", plugin.getRanks().getLadder()));
            sender.sendMessage("§7Nametag mode: §f" + plugin.getNametag().nametagMode());
            sender.sendMessage("§7Version: §f" + plugin.getDescription().getVersion());
            sender.sendMessage("§7Usage: /" + label + " reload | /" + label + " info | /" + label + " update <check|download|status>");
            return true;
        }
        if (TextUtil.norm(args[0]).equals("update")) {
            if (plugin.getUpdater() == null) {
                sender.sendMessage("§cUpdater not initialized.");
                return true;
            }
            String sub = args.length >= 2 ? TextUtil.norm(args[1]) : "status";
            if (sub.equals("check")) {
                plugin.getUpdater().checkAsync(sender, sender.getName());
                return true;
            }
            if (sub.equals("download")) {
                String tag = args.length >= 3 ? args[2].trim() : null;
                if (tag != null && tag.isEmpty()) tag = null;
                sender.sendMessage("§7Starting download" + (tag == null ? " (latest)" : " " + tag) + "...");
                plugin.getUpdater().downloadAsync(sender, tag, sender.getName());
                return true;
            }
            if (sub.equals("status")) {
                plugin.getUpdater().sendStatus(sender);
                return true;
            }
            sender.sendMessage("§cUnknown. Use: /" + label + " update <check|download [tag]|status>");
            return true;
        }
        if (TextUtil.norm(args[0]).equals("reload")) {
            plugin.reloadConfig();
            try { plugin.pruneStaleConfig(); } catch (Exception ignored) {}
            try { if (plugin.getStatusBoard() != null) plugin.getStatusBoard().reload(); } catch (Exception ignored) {}
            try { if (plugin.getSpawnSnapshot() != null) plugin.getSpawnSnapshot().reload(); } catch (Exception ignored) {}
            try { if (plugin.getSpectatorCam() != null) plugin.getSpectatorCam().reload(); } catch (Exception ignored) {}
            try { if (plugin.getCropMarket() != null) plugin.getCropMarket().reload(); } catch (Exception ignored) {}
            try { if (plugin.getCropMarketBoard() != null) plugin.getCropMarketBoard().reload(); } catch (Exception ignored) {}
            try {
                if (plugin.getConfig().getBoolean("rcon-spam-filter-enabled", true)) RconSpamFilter.install(plugin);
                else RconSpamFilter.uninstall();
            } catch (Exception ignored) {}
            try { RconSpamFilter.setBotMode(plugin.getConfig().getString("bot-command-log-mode", "group")); } catch (Exception ignored) {}
            try { RconSpamFilter.refreshBotNames(plugin); } catch (Exception ignored) {}
            sender.sendMessage("§aAllPurpose reloaded. Ranks: " + plugin.getRanks().rankNames().size());
            return true;
        }
        sender.sendMessage("§cUnknown. Use: /" + label + " reload | info | update <check|download|status>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("anchorfix.admin")) return new ArrayList<>();
        if (args.length == 1) return Arrays.asList("info", "reload", "update");
        if (args.length == 2 && TextUtil.norm(args[0]).equals("update")) return Arrays.asList("check", "download", "status");
        return new ArrayList<>();
    }
}
