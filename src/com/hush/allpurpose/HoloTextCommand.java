package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /setholotext - free-form floating text. Text + look are set entirely with
 * commands (nothing is hardcoded), the display is billboard FIXED so it does
 * not sway with the camera, and it is oriented to the placer's view.
 */
public class HoloTextCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "new", "add", "text", "addline", "insertline", "setline", "delline", "lines",
            "move", "rotate", "tilt", "style", "list", "info", "tp", "remove", "reload", "help");

    private final AllPurpose plugin;

    public HoloTextCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("rank.admin")) {
            sender.sendMessage("§cNo permission (rank.admin).");
            return true;
        }
        HoloTextManager m = plugin.getHoloText();
        if (m == null) {
            sender.sendMessage("§cHoloText manager is not ready.");
            return true;
        }
        if (args.length == 0) { help(sender, label); return true; }

        String sub = TextUtil.norm(args[0]);

        if (sub.equals("help")) { help(sender, label); return true; }

        if (sub.equals("reload")) {
            m.reload();
            sender.sendMessage("§aHoloText reloaded from disk: §f" + m.ids().size() + "§a hologram(s) restored.");
            return true;
        }

        if (sub.equals("list")) {
            List<String> ids = m.ids();
            if (ids.isEmpty()) { sender.sendMessage("§7No holograms yet. §f/" + label + " add <text>"); return true; }
            sender.sendMessage("§6§lHOLOTEXT §7(" + ids.size() + ")");
            for (String id : ids) {
                Location l = m.loc(id);
                String where = l == null ? "§c(unloaded world)" :
                        "§7" + l.getWorld().getName() + " §f" + round(l.getX()) + " " + round(l.getY()) + " " + round(l.getZ());
                sender.sendMessage("§e" + id + " §8- " + where + " §8| §7" + m.lines(id).size() + " line(s)");
            }
            return true;
        }

        if (sub.equals("info")) {
            if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " info <id>"); return true; }
            String id = TextUtil.norm(args[1]);
            if (!m.exists(id)) { noSuch(sender, id); return true; }
            Location l = m.loc(id);
            sender.sendMessage("§6§lHOLOTEXT §f" + id);
            if (l != null) {
                sender.sendMessage("§7world: §f" + l.getWorld().getName());
                sender.sendMessage("§7pos: §f" + round(l.getX()) + " " + round(l.getY()) + " " + round(l.getZ())
                        + " §7yaw: §f" + round(l.getYaw()) + " §7pitch: §f" + round(l.getPitch()));
            }
            sender.sendMessage("§7lines: §f" + m.lines(id).size());
            java.util.Map<String, String> st = m.styles(id);
            if (st.isEmpty()) sender.sendMessage("§7style: §f(all defaults)");
            else for (java.util.Map.Entry<String, String> e : st.entrySet())
                sender.sendMessage("§7  " + e.getKey() + ": §f" + e.getValue());
            return true;
        }

        if (sub.equals("new") || sub.equals("add")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cRun /" + label + " " + sub + " in-game, standing where the text should float.");
                return true;
            }
            Player p = (Player) sender;
            String id;
            int textFrom;
            if (sub.equals("new")) {
                if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " new <id> <text...>"); return true; }
                id = HoloTextManager.validId(args[1]);
                if (id == null) {
                    sender.sendMessage("§cId must be 1-24 of a-z 0-9 _ - + (no spaces).");
                    return true;
                }
                if (m.exists(id)) { sender.sendMessage("§cA hologram called §f" + id + " §calready exists. Edit it or pick another id."); return true; }
                textFrom = 2;
            } else {
                id = m.autoId();
                textFrom = 1;
            }
            List<String> lines = args.length > textFrom
                    ? splitLines(TextUtil.join(args, textFrom))
                    : new ArrayList<>(Arrays.asList("&f" + id));
            Location at = placeAt(p, lines.size());
            m.create(id, at, lines);
            if (m.entity(id) == null && !chunkLoaded(at)) {
                sender.sendMessage("§ePlaced, but its chunk is not loaded - it will appear as soon as the chunk loads.");
            }
            sender.sendMessage("§aPlaced holotext §f" + id + "§a here (§ffixed, facing you§a).");
            sender.sendMessage("§7Edit text: §f/" + label + " text " + id + " <text> §7(add §f\\n§7 for new lines) §7| §flines " + id);
            return true;
        }

        if (sub.equals("text")) {
            String id = idArg(sender, label, "text", args, 2, m);
            if (id == null) return true;
            List<String> lines = args.length > 2 ? splitLines(TextUtil.join(args, 2))
                    : new ArrayList<>(Arrays.asList("&f" + id));
            m.setLines(id, lines);
            m.refresh(id);
            sender.sendMessage("§aText of §f" + id + " §aset to §f" + lines.size() + "§a line(s).");
            return true;
        }

        if (sub.equals("addline")) {
            String id = idArg(sender, label, "addline", args, 3, m);
            if (id == null) return true;
            List<String> lines = m.lines(id);
            lines.addAll(splitLines(TextUtil.join(args, 2)));
            m.setLines(id, lines);
            m.refresh(id);
            sender.sendMessage("§aAdded line(s) - §f" + id + " §anow has " + lines.size() + " line(s).");
            return true;
        }

        if (sub.equals("insertline")) {
            String id = idArg(sender, label, "insertline", args, 4, m);
            if (id == null) return true;
            int n = parseIndex(sender, args[2], m.lines(id).size() + 1);
            if (n < 0) return true;
            List<String> lines = m.lines(id);
            lines.addAll(n - 1, splitLines(TextUtil.join(args, 3)));
            m.setLines(id, lines);
            m.refresh(id);
            sender.sendMessage("§aInserted line(s) at §f" + n + " §a- §f" + id + " §anow has " + lines.size() + " line(s).");
            return true;
        }

        if (sub.equals("setline")) {
            String id = idArg(sender, label, "setline", args, 4, m);
            if (id == null) return true;
            List<String> lines = m.lines(id);
            int n = parseIndex(sender, args[2], lines.size());
            if (n < 0) return true;
            lines.set(n - 1, TextUtil.join(args, 3));
            m.setLines(id, lines);
            m.refresh(id);
            sender.sendMessage("§aLine §f" + n + " §aof §f" + id + " §aupdated.");
            return true;
        }

        if (sub.equals("delline")) {
            String id = idArg(sender, label, "delline", args, 3, m);
            if (id == null) return true;
            List<String> lines = m.lines(id);
            int n = parseIndex(sender, args[2], lines.size());
            if (n < 0) return true;
            lines.remove(n - 1);
            m.setLines(id, lines);
            m.refresh(id);
            sender.sendMessage("§aRemoved line §f" + n + " §a- §f" + id + " §anow has " + lines.size() + " line(s).");
            return true;
        }

        if (sub.equals("lines")) {
            String id = idArg(sender, label, "lines", args, 2, m);
            if (id == null) return true;
            List<String> lines = m.lines(id);
            sender.sendMessage("§6§lLINES §f" + id + " §7(" + lines.size() + ")");
            for (int i = 0; i < lines.size(); i++)
                sender.sendMessage("§7" + (i + 1) + ". §f§r" + lines.get(i));
            if (lines.isEmpty()) sender.sendMessage("§7(empty - add one with §f/" + label + " addline " + id + " <text>§7)");
            return true;
        }

        if (sub.equals("move")) {
            String id = idArg(sender, label, "move", args, 2, m);
            if (id == null) return true;
            if (!(sender instanceof Player)) { sender.sendMessage("§cIn-game only."); return true; }
            Player p = (Player) sender;
            m.move(id, placeAt(p, m.lines(id).size()));
            m.spawn(id);
            sender.sendMessage("§aMoved §f" + id + " §ato your position (§ffixed, facing you§a).");
            return true;
        }

        if (sub.equals("rotate")) {
            String id = idArg(sender, label, "rotate", args, 3, m);
            if (id == null) return true;
            Float yaw = resolveAngle(sender, args[2], m.yaw(id));
            if (yaw == null) return true;
            m.setYaw(id, yaw);
            m.spawn(id);
            sender.sendMessage("§aSet yaw of §f" + id + " §ato §f" + round(yaw) + "°§a.");
            return true;
        }

        if (sub.equals("tilt")) {
            String id = idArg(sender, label, "tilt", args, 3, m);
            if (id == null) return true;
            Float pitch = resolveAngle(sender, args[2], m.pitch(id));
            if (pitch == null) return true;
            m.setPitch(id, pitch);
            m.spawn(id);
            sender.sendMessage("§aSet pitch of §f" + id + " §ato §f" + round(pitch) + "°§a.");
            return true;
        }

        if (sub.equals("style")) {
            String id = idArg(sender, label, "style", args, 2, m);
            if (id == null) return true;
            if (args.length < 3) { styleHelp(sender, label, id); return true; }
            String key = TextUtil.norm(args[2]);
            if (!HoloTextManager.STYLE_KEYS.contains(key)) {
                sender.sendMessage("§cUnknown style key '§f" + args[2] + "§c'.");
                styleHelp(sender, label, id);
                return true;
            }
            if (args.length < 4) {
                String cur = m.style(id, key);
                sender.sendMessage("§7" + id + " §8| §f" + key + " §7= §f" + (cur == null ? "(default)" : cur));
                return true;
            }
            String value = TextUtil.join(args, 3);
            String err = validateStyle(key, value);
            if (err != null) { sender.sendMessage("§c" + err); return true; }
            m.setStyle(id, key, value);
            m.refresh(id);
            sender.sendMessage("§a§f" + id + " §a-> §f" + key + " §7= §f" + value);
            return true;
        }

        if (sub.equals("tp")) {
            String id = idArg(sender, label, "tp", args, 2, m);
            if (id == null) return true;
            if (!(sender instanceof Player)) { sender.sendMessage("§cIn-game only."); return true; }
            Location l = m.loc(id);
            if (l == null || l.getWorld() == null) { sender.sendMessage("§cThat hologram's world is not loaded."); return true; }
            ((Player) sender).teleport(l);
            sender.sendMessage("§aTeleported to §f" + id + "§a.");
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length < 2) { sender.sendMessage("§cUsage: /" + label + " remove <id|all>"); return true; }
            String id = TextUtil.norm(args[1]);
            if (id.equals("all") || id.equals("*")) {
                m.removeAll();
                sender.sendMessage("§aAll holograms removed.");
                return true;
            }
            if (!m.exists(id)) { noSuch(sender, id); return true; }
            m.remove(id);
            sender.sendMessage("§aRemoved holotext §f" + id + "§a.");
            return true;
        }

        help(sender, label);
        return true;
    }

    // ---------- helpers ----------

    private static String idArg(CommandSender sender, String label, String sub, String[] args, int minArgs, HoloTextManager m) {
        if (args.length < minArgs) {
            sender.sendMessage("§cUsage: /" + label + " " + sub + " <id>" + (sub.equals("style") ? " <key> [value]" : " ..."));
            return null;
        }
        String id = TextUtil.norm(args[1]);
        if (!m.exists(id)) { noSuch(sender, id); return null; }
        return id;
    }

    private static void noSuch(CommandSender sender, String id) {
        sender.sendMessage("§cNo hologram called '§f" + id + "§c'.");
    }

    private static int parseIndex(CommandSender sender, String raw, int max) {
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 1 || n > max) {
                sender.sendMessage("§cLine must be between 1 and " + max + ".");
                return -1;
            }
            return n;
        } catch (Exception e) {
            sender.sendMessage("§c'" + raw + "' is not a line number (1-" + max + ").");
            return -1;
        }
    }

    /** "here", "left", "right", "flip", or an absolute number. */
    private static Float resolveAngle(CommandSender sender, String raw, float current) {
        String v = TextUtil.norm(raw);
        if (v.equals("here")) {
            if (!(sender instanceof Player)) { sender.sendMessage("§c'here' only works in-game."); return null; }
            return ((Player) sender).getLocation().getYaw();
        }
        if (v.equals("left")) return wrap(current - 15f);
        if (v.equals("right")) return wrap(current + 15f);
        if (v.equals("flip") || v.equals("around")) return wrap(current + 180f);
        if (v.equals("north")) return 180f;
        if (v.equals("south")) return 0f;
        if (v.equals("west")) return 90f;
        if (v.equals("east")) return -90f;
        try { return wrap(Float.parseFloat(v)); }
        catch (Exception e) {
            sender.sendMessage("§cUse a number of degrees, or: here | left | right | flip | north | south | east | west");
            return null;
        }
    }

    private static float wrap(float yaw) {
        float y = yaw % 360f;
        if (y >= 180f) y -= 360f;
        if (y < -180f) y += 360f;
        return y;
    }

    /** Splits a raw argument on the literal "\n" so one command can make lines. */
    private static List<String> splitLines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) { out.add(""); return out; }
        for (String part : raw.replace("\\n", "\n").split("\n", -1)) out.add(part);
        return out;
    }

    /** Where a new hologram goes: at the placer's eye, lifted by half its blocks. */
    private static Location placeAt(Player p, int lineCount) {
        Location eye = p.getLocation();
        double lift = Math.max(1, lineCount) * 0.15;
        return new Location(eye.getWorld(), eye.getX(), eye.getY() + lift, eye.getZ(), eye.getYaw(), 0f);
    }

    private static boolean chunkLoaded(Location l) {
        try { return l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4); }
        catch (Exception e) { return false; }
    }

    private static String validateStyle(String key, String value) {
        String v = TextUtil.norm(value);
        switch (key) {
            case "billboard":
                if (!Arrays.asList("fixed", "center", "centre", "vertical", "horizontal").contains(v))
                    return "billboard must be: fixed | center | vertical | horizontal";
                return null;
            case "align":
                if (!Arrays.asList("center", "left", "right").contains(v))
                    return "align must be: center | left | right";
                return null;
            case "background":
                if (!HoloTextManager.isColorValue(v))
                    return "background must be #AARRGGBB / #RRGGBB / none / default";
                return null;
            case "seethrough": case "shadow": case "glow":
                if (!Arrays.asList("true", "false", "on", "off", "yes", "no").contains(v))
                    return key + " must be true or false";
                return null;
            case "linewidth": case "opacity": {
                try { Integer.parseInt(v); return null; }
                catch (Exception e) { return key + " must be a whole number"; }
            }
            case "viewrange": case "scale": {
                try { Double.parseDouble(v); return null; }
                catch (Exception e) { return key + " must be a number"; }
            }
            default: return null;
        }
    }

    private static String round(double d) { return String.format(java.util.Locale.ROOT, "%.1f", d); }

    private static void styleHelp(CommandSender sender, String label, String id) {
        sender.sendMessage("§6§lHOLOTEXT STYLE §8- §f" + id);
        sender.sendMessage("§7/" + label + " style " + id + " billboard §f<fixed|center|vertical|horizontal>");
        sender.sendMessage("§7/" + label + " style " + id + " align §f<center|left|right>");
        sender.sendMessage("§7/" + label + " style " + id + " background §f<#AARRGGBB|#RRGGBB|none|default>");
        sender.sendMessage("§7/" + label + " style " + id + " linewidth §f<1-1000>");
        sender.sendMessage("§7/" + label + " style " + id + " opacity §f<-1|0-127>");
        sender.sendMessage("§7/" + label + " style " + id + " seethrough|shadow|glow §f<true|false>");
        sender.sendMessage("§7/" + label + " style " + id + " viewrange §f<0.01-2.0>");
        sender.sendMessage("§7/" + label + " style " + id + " scale §f<0.05-10>");
    }

    private static void help(CommandSender sender, String label) {
        sender.sendMessage("§6§lHOLOTEXT §7- free-form floating text (fixed, facing you)");
        sender.sendMessage("§e/" + label + " new <id> <text...> §7- place here; §f\\n§7 makes new lines");
        sender.sendMessage("§e/" + label + " add <text...> §7- place here with an auto id");
        sender.sendMessage("§e/" + label + " text <id> <text...> §7- replace the whole text");
        sender.sendMessage("§e/" + label + " addline|insertline|setline|delline|lines <id> ... §7- edit line by line");
        sender.sendMessage("§e/" + label + " move|rotate|tilt <id> ... §7- reposition / turn");
        sender.sendMessage("§e/" + label + " style <id> <key> <value> §7- look (background, scale, ...)");
        sender.sendMessage("§e/" + label + " list|info|tp <id>|remove <id|all>|reload");
        sender.sendMessage("§7Colours: §f&6&l §7/ §f#FFAA00 §7/ §f<gradient:#FFAA00:#FF5555>text</gradient>");
    }

    // ---------- tab complete ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("rank.admin")) return out;
        HoloTextManager m = plugin.getHoloText();
        if (m == null) return out;
        String cur = TextUtil.norm(args[args.length - 1]);

        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(cur)) out.add(s);
            return out;
        }

        String sub = TextUtil.norm(args[0]);
        boolean wantsId = Arrays.asList("text", "addline", "insertline", "setline", "delline",
                "lines", "move", "rotate", "tilt", "style", "info", "tp").contains(sub);
        boolean removeSub = sub.equals("remove");

        if (args.length == 2) {
            if (wantsId) {
                for (String cand : m.ids()) if (cand.startsWith(cur)) out.add(cand);
            } else if (removeSub) {
                for (String cand : m.ids()) if (cand.startsWith(cur)) out.add(cand);
                if ("all".startsWith(cur)) out.add("all");
            }
            return out;
        }

        if (sub.equals("style") && args.length == 3) {
            for (String k : HoloTextManager.STYLE_KEYS) if (k.startsWith(cur)) out.add(k);
            return out;
        }
        if (sub.equals("style") && args.length == 4) {
            String key = TextUtil.norm(args[2]);
            switch (key) {
                case "billboard": return filter(Arrays.asList("fixed", "center", "vertical", "horizontal"), cur);
                case "align": return filter(Arrays.asList("center", "left", "right"), cur);
                case "background": return filter(Arrays.asList("#80000000", "#00000000", "default", "none"), cur);
                case "seethrough": case "shadow": case "glow": return filter(Arrays.asList("true", "false"), cur);
                case "linewidth": return filter(Arrays.asList("300", "200"), cur);
                case "opacity": return filter(Arrays.asList("-1", "127", "64"), cur);
                case "viewrange": return filter(Arrays.asList("0.6", "1.0", "0.3"), cur);
                case "scale": return filter(Arrays.asList("1.0", "1.5", "2.0", "3.0"), cur);
                default: return out;
            }
        }
        if ((sub.equals("rotate") || sub.equals("tilt")) && args.length == 3) {
            return filter(Arrays.asList("here", "left", "right", "flip", "0", "90", "180", "-90"), cur);
        }
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : options) if (s.startsWith(prefix)) out.add(s);
        return out;
    }
}
