package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Admin surface for the combat-log shame ceremony. */
public class ShameCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public ShameCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("allpurpose.shame.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        CombatManager combat = plugin.getCombat();
        if (combat == null) {
            sender.sendMessage(ChatColor.RED + "Combat system is not loaded.");
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload": {
                combat.reloadSettings();
                sender.sendMessage(ChatColor.GREEN + "Combat-log config reloaded.");
                return true;
            }
            case "test":
            case "start": {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " test <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player is not online: " + args[1]);
                    return true;
                }
                combat.getCeremony().start(target, 1, true);
                sender.sendMessage(ChatColor.GREEN + "Shame ceremony started for " + target.getName()
                        + " (test mode - no offence recorded).");
                return true;
            }
            case "status": {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " status <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player is not online: " + args[1]);
                    return true;
                }
                CombatManager.State state = combat.state(target.getUniqueId());
                String opponent = state == null ? "-" : state.opponentName;
                int left = state == null ? 0 : state.remainingSeconds();
                sender.sendMessage(ChatColor.YELLOW + target.getName() + ChatColor.GRAY
                        + ": tagged=" + (state != null)
                        + ", opponent=" + opponent
                        + ", tag-left=" + left + "s"
                        + ", offences=" + combat.offence(target.getUniqueId())
                        + ", ceremony=" + combat.getCeremony().isInCeremony(target.getUniqueId())
                        + ", grace=" + combat.isInGrace(target.getUniqueId()));
                return true;
            }
            case "clear": {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " clear <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player is not online: " + args[1]);
                    return true;
                }
                combat.getCeremony().stop(target.getUniqueId());
                combat.clear(target.getUniqueId());
                sender.sendMessage(ChatColor.GREEN + "Cleared combat state, pending log and offences for "
                        + target.getName() + ".");
                return true;
            }
            default:
                usage(sender, label);
                return true;
        }
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " test <player> " + ChatColor.GRAY
                + "- preview the fish ceremony");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status <player> " + ChatColor.GRAY
                + "- combat state + offences");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " clear <player> " + ChatColor.GRAY
                + "- wipe pending log/offences");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload " + ChatColor.GRAY
                + "- re-read the combat-log config");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("allpurpose.shame.admin")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : new String[]{"test", "status", "clear", "reload"}) {
                if (s.startsWith(prefix)) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && !"reload".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
            return out;
        }
        return List.of();
    }
}
