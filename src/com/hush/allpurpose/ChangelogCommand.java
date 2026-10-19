package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /changelog - server changelog as dialogs. Versions are boxes; each holds
 * lines, and the whole thing opens from the pause menu and/or /changelog show.
 * Viewing is public; editing needs rank.admin. Nothing is hardcoded.
 */
public class ChangelogCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "create", "add", "insertline", "setline", "delline", "lines",
            "title", "label", "remove", "list", "show", "screen", "sync", "reload", "help");

    private static final List<String> VIEW_SUBS = Arrays.asList("show", "list", "help");

    /** Subs that change content: the reply then carries the pause-menu reminder. */
    private static final List<String> EDIT_SUBS = Arrays.asList(
            "create", "add", "insertline", "setline", "delline", "title", "label", "remove");

    private static final String PAUSE_NOTE =
            "§8[§7pause-menu copy refreshes on the next restart - §f/changelog show §7is immediate§8]";

    private final AllPurpose plugin;

    public ChangelogCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        ChangelogManager m = plugin.getChangelog();
        if (m == null) { sender.sendMessage("§cChangelog manager is not ready."); return true; }
        if (args.length == 0) { help(sender, label); return true; }

        String sub = TextUtil.norm(args[0]);
        if (sub.equals("help")) { help(sender, label); return true; }

        // Anything that changes data (or forces a rewrite) is admin-only.
        if (!VIEW_SUBS.contains(sub) && !sender.hasPermission("rank.admin")) {
            sender.sendMessage("§cNo permission (rank.admin).");
            return true;
        }

        // An edit is live for /changelog show straight away, but the pause-menu
        // copy is a datapack the server only reads at boot. Say so on every edit,
        // otherwise an edit looks like it vanished (it didn't).
        boolean edits = EDIT_SUBS.contains(sub);
        try {
            return handle(sender, m, label, sub, args);
        } finally {
            if (edits) {
                try { sender.sendMessage(PAUSE_NOTE); } catch (Exception ignored) {}
            }
        }
    }

    private boolean handle(CommandSender sender, ChangelogManager m, String label, String sub, String[] args) {
        if (sub.equals("list")) {
            List<String> ks = m.order();
            if (ks.isEmpty()) { sender.sendMessage("§7No changelog versions yet. §f/" + label + " create v1"); return true; }
            sender.sendMessage("§6§lCHANGELOG §7(" + ks.size() + ") §7| pause screen: " + (m.screen() ? "§aon" : "§coff"));
            for (String k : ks) {
                sender.sendMessage("§e" + m.nameOf(k) + " §8- §7" + m.lines(k).size() + " line(s)"
                        + (k.equalsIgnoreCase(TextUtil.norm(m.nameOf(k))) ? "" : " §8[" + k + "]"));
            }
            return true;
        }

        if (sub.equals("reload")) {
            m.reload();
            sender.sendMessage("§aChangelog reloaded: §f" + m.order().size() + "§a version(s).");
            sender.sendMessage("§7Pause-menu copy is written to disk now; it appears after the next restart (the same change is instant with §f/" + label + " show§7).");
            return true;
        }

        if (sub.equals("sync")) {
            m.writeDatapack();
            sender.sendMessage("§aChangelog datapack rewritten. §7Pause-menu copy applies on the next restart.");
            return true;
        }

        if (sub.equals("screen")) {
            if (args.length < 2) {
                sender.sendMessage("§7Pause-menu changelog is " + (m.screen() ? "§aon" : "§coff") + "§7. §f/" + label + " screen <on|off>");
                return true;
            }
            String v = TextUtil.norm(args[1]);
            boolean on = v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("1");
            m.setScreen(on);
            sender.sendMessage(on
                    ? "§aChangelog will show in the pause menu §7(after the next restart)."
                    : "§eChangelog removed from the pause menu §7(after the next restart).");
            return true;
        }

        if (sub.equals("create")) {
            if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " create <version> [text...]"); return true; }
            if (!ChangelogManager.validName(args[1])) {
                sender.sendMessage("§cVersion must be 1-20 of a-z 0-9 _ - + . (no spaces).");
                return true;
            }
            if (m.exists(args[1])) { sender.sendMessage("§cVersion §f" + args[1] + "§c already exists."); return true; }
            if (!m.create(args[1])) { sender.sendMessage("§cCould not create §f" + args[1] + "§c."); return true; }
            String k = m.key(args[1]);
            if (args.length > 2) for (String ln : splitLines(TextUtil.join(args, 2))) m.addLine(k, ln);
            sender.sendMessage("§aCreated version §f" + m.nameOf(k) + "§a."
                    + (args.length > 2 ? " §7(" + m.lines(k).size() + " line(s))" : ""));
            sender.sendMessage("§7Add text: §f/" + label + " add " + m.nameOf(k) + " <text> §7| preview: §f/" + label + " show " + m.nameOf(k));
            return true;
        }

        if (sub.equals("add")) {
            if (args.length < 3) { sender.sendMessage("§cUsage: /" + label + " add <version> <text...>"); return true; }
            if (!m.exists(args[1])) { noSuch(sender, label, args[1]); return true; }
            String k = m.key(args[1]);
            List<String> add = splitLines(TextUtil.join(args, 2));
            for (String ln : add) m.addLine(k, ln);
            sender.sendMessage("§aAdded §f" + add.size() + "§a line(s) to §f" + m.nameOf(k) + " §7(now " + m.lines(k).size() + ").");
            return true;
        }

        if (sub.equals("insertline") || sub.equals("setline")) {
            if (args.length < 4) { sender.sendMessage("§cUsage: /" + label + " " + sub + " <version> <line#> <text...>"); return true; }
            if (!m.exists(args[1])) { noSuch(sender, label, args[1]); return true; }
            Integer n = parseInt(args[2]);
            if (n == null) { sender.sendMessage("§cLine number must be a whole number."); return true; }
            String k = m.key(args[1]);
            String text = TextUtil.join(args, 3);
            if (sub.equals("setline")) {
                int idx = n - 1;
                if (idx < 0 || idx >= m.lines(k).size()) {
                    sender.sendMessage("§cLine §f" + n + "§c does not exist (1-" + m.lines(k).size() + ").");
                    return true;
                }
                m.setLine(k, idx, text);
                sender.sendMessage("§aSet line §f" + n + "§a of §f" + m.nameOf(k) + "§a.");
            } else {
                m.insertLine(k, n - 1, text);
                sender.sendMessage("§aInserted a line at §f" + n + "§a in §f" + m.nameOf(k) + "§a.");
            }
            return true;
        }

        if (sub.equals("delline")) {
            if (args.length < 3) { sender.sendMessage("§cUsage: /" + label + " delline <version> <line#>"); return true; }
            if (!m.exists(args[1])) { noSuch(sender, label, args[1]); return true; }
            Integer n = parseInt(args[2]);
            if (n == null) { sender.sendMessage("§cLine number must be a whole number."); return true; }
            String k = m.key(args[1]);
            if (!m.removeLine(k, n - 1)) {
                sender.sendMessage("§cLine §f" + n + "§c does not exist (1-" + m.lines(k).size() + ").");
                return true;
            }
            sender.sendMessage("§aRemoved line §f" + n + "§a from §f" + m.nameOf(k) + "§a.");
            return true;
        }

        if (sub.equals("lines")) {
            if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " lines <version>"); return true; }
            if (!m.exists(args[1])) { noSuch(sender, label, args[1]); return true; }
            String k = m.key(args[1]);
            List<String> ls = m.lines(k);
            sender.sendMessage("§6§l" + m.nameOf(k) + " §7(" + ls.size() + " line(s))");
            sender.sendMessage("§7title: §f" + m.titleOf(k) + "  §7button: §f" + m.labelOf(k));
            if (ls.isEmpty()) sender.sendMessage("§7(no lines yet)");
            for (int i = 0; i < ls.size(); i++) sender.sendMessage("§e" + (i + 1) + ". §f" + ls.get(i));
            return true;
        }

        if (sub.equals("title") || sub.equals("label")) {
            if (args.length < 3) { sender.sendMessage("§cUsage: /" + label + " " + sub + " <version> <text...>"); return true; }
            if (!m.exists(args[1])) { noSuch(sender, label, args[1]); return true; }
            String k = m.key(args[1]);
            String text = TextUtil.join(args, 2);
            if (sub.equals("title")) m.setTitle(k, text); else m.setLabel(k, text);
            sender.sendMessage("§aSet the §f" + (sub.equals("title") ? "dialog title" : "button label") + "§a of §f" + m.nameOf(k) + "§a.");
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " remove <version>"); return true; }
            if (!m.exists(args[1])) { noSuch(sender, label, args[1]); return true; }
            String k = m.key(args[1]);
            String name = m.nameOf(k);
            m.removeVersion(k);
            sender.sendMessage("§aRemoved version §f" + name + "§a.");
            return true;
        }

        if (sub.equals("show")) {
            Player target = (sender instanceof Player) ? (Player) sender : null;
            String version = null;
            if (args.length >= 2) {
                if (m.exists(args[1])) {
                    version = args[1];
                    if (args.length >= 3) {
                        target = Bukkit.getPlayerExact(args[2]);
                        if (target == null) { sender.sendMessage("§cPlayer §f" + args[2] + "§c is not online."); return true; }
                    }
                } else {
                    Player p = Bukkit.getPlayerExact(args[1]);
                    if (p != null) target = p;
                    else {
                        sender.sendMessage("§cNo version or online player called §f" + args[1] + "§c.");
                        return true;
                    }
                }
            }
            if (target == null) { sender.sendMessage("§cConsole: /" + label + " show [version] <player>"); return true; }
            m.show(target, version);
            if (target != sender) sender.sendMessage("§aOpened the changelog" + (version == null ? "" : " §7(" + version + ")") + " §afor §f" + target.getName() + "§a.");
            return true;
        }

        help(sender, label);
        return true;
    }

    private void noSuch(CommandSender sender, String label, String version) {
        sender.sendMessage("§cNo changelog version called §f" + version + "§c. §7/" + label + " list");
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    /** Literal \n makes new lines (same convention as /setholotext). */
    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split("\\\\n", -1)) out.add(part);
        return out;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§6§lCHANGELOG §7(server changelog dialogs)");
        sender.sendMessage("§f/" + label + " create <version> [text...] §7- new version box");
        sender.sendMessage("§f/" + label + " add <version> <text...> §7- add a line (use \\n for several)");
        sender.sendMessage("§f/" + label + " insertline|setline <version> <line#> <text...>");
        sender.sendMessage("§f/" + label + " delline <version> <line#> §7| §flines <version>");
        sender.sendMessage("§f/" + label + " title|label <version> <text...> §7- dialog title / button");
        sender.sendMessage("§f/" + label + " remove <version> §7| §flist");
        sender.sendMessage("§f/" + label + " show [version] [player] §7- open the dialog now");
        sender.sendMessage("§f/" + label + " screen <on|off> §7| §fsync §7| §freload");
        sender.sendMessage("§7Colours: &a &c &#RRGGBB <gradient:#AAA:#BBB>. Pause-menu copy refreshes on restart.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        ChangelogManager m = plugin.getChangelog();
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        if (args.length == 1) {
            for (String s : SUBS) {
                if (VIEW_SUBS.contains(s) || sender.hasPermission("rank.admin")) {
                    if (s.startsWith(TextUtil.norm(args[0]))) out.add(s);
                }
            }
            return out;
        }
        String sub = TextUtil.norm(args[0]);
        if (args.length == 2) {
            if (sub.equals("screen")) return startsWith(Arrays.asList("on", "off"), args[1]);
            if (sub.equals("show")) {
                out.addAll(startsWith(m.order().stream().map(m::nameOf).toList(), args[1]));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (TextUtil.norm(p.getName()).startsWith(TextUtil.norm(args[1]))) out.add(p.getName());
                }
                return out;
            }
            if (SUBS.contains(sub) && !sub.equals("list") && !sub.equals("reload") && !sub.equals("sync") && !sub.equals("help")) {
                return startsWith(m.order().stream().map(m::nameOf).toList(), args[1]);
            }
            return out;
        }
        if (args.length == 3) {
            if (sub.equals("show")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (TextUtil.norm(p.getName()).startsWith(TextUtil.norm(args[2]))) out.add(p.getName());
                }
                return out;
            }
            if (sub.equals("delline") || sub.equals("setline") || sub.equals("insertline")) {
                if (m.exists(args[1])) for (int i = 1; i <= m.lines(m.key(args[1])).size(); i++) out.add(String.valueOf(i));
                return out;
            }
        }
        return out;
    }

    private static List<String> startsWith(List<String> list, String prefix) {
        String p = TextUtil.norm(prefix);
        List<String> out = new ArrayList<>();
        for (String s : list) if (TextUtil.norm(s).startsWith(p)) out.add(s);
        return out;
    }
}
