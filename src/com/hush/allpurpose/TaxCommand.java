package com.hush.allpurpose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Controls for the wealth tax ("prosperity audit").
 *
 * Public (no permission): {@code /tax balance} and {@code /tax patrons} - the
 * transparency half of the fund, so the treasury feels like a community thing
 * rather than an admin's spreadsheet.
 *
 * Admin (allpurpose.tax.admin): inspect the treasury, see the rates, read the
 * ledger and the payout evidence trail, trigger an event (preview first!), and
 * record treasury outflows.
 *
 * A manual collect is deliberately two-step: {@code /tax run} only PREVIEWS who
 * would be taxed and how much; nothing moves until {@code /tax run confirm}.
 */
public class TaxCommand implements CommandExecutor, TabCompleter {

    private final AllPurpose plugin;

    public TaxCommand(AllPurpose plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "info" : TextUtil.norm(args[0]);

        // The fund's public face: anyone may see the balance and the patrons.
        boolean publicSub = sub.equals("balance") || sub.equals("patrons") || sub.equals("top");

        // Read-only output is a screen, not a wall of chat. A player gets the
        // GUI for every inspect sub (/tax with no args included, since that is
        // "info"); the console - and every control sub below - keeps the
        // plain-text path, because a chest GUI can only be opened for a player.
        boolean inspect = sub.equals("info") || sub.equals("treasury") || sub.equals("status")
                || sub.equals("rates") || sub.equals("ledger") || sub.equals("spends")
                || sub.equals("grants") || sub.equals("draws");
        if (sender instanceof Player && (publicSub || inspect)) {
            if (!publicSub && !sender.hasPermission("allpurpose.tax.admin")) {
                sender.sendMessage("§cNo permission (allpurpose.tax.admin).");
                return true;
            }
            if (plugin.getWealthTax() == null) {
                sender.sendMessage("§cTax manager not initialized.");
                return true;
            }
            TaxGui.open((Player) sender, plugin);
            return true;
        }

        if (!publicSub && !sender.hasPermission("allpurpose.tax.admin")) {
            sender.sendMessage("§cNo permission (allpurpose.tax.admin).");
            return true;
        }

        WealthTaxManager t = plugin.getWealthTax();
        if (t == null) {
            sender.sendMessage("§cTax manager not initialized.");
            return true;
        }

        if (sub.equals("balance")) {
            sender.sendMessage("§6§l✦ COMMUNITY FUND §r§7(the prosperity audit)");
            sender.sendMessage(t.balanceLine());
            for (String s : t.bucketLines()) sender.sendMessage(s);
            if (t.lastEvent() > 0) {
                sender.sendMessage("§7Last audit: §f" + WealthTaxManager.fmtDate(t.lastEvent())
                        + " §7collected §a" + Menus.plain(t.lastTotal()));
            } else {
                sender.sendMessage("§7No audit has landed yet - the realm is untouched.");
            }
            sender.sendMessage("§7Top contributors: §f/" + label + " patrons");
            sender.sendMessage(drawLine());
            return true;
        }

        if (sub.equals("patrons") || sub.equals("top")) {
            sender.sendMessage("§6§l✦ Patrons §7- the realm's largest taxpayers");
            List<String> p = t.topPatrons(5);
            if (p.isEmpty()) sender.sendMessage("§7No patrons yet - nobody has been audited.");
            else for (String s : p) sender.sendMessage(s);
            return true;
        }

        if (sub.equals("info") || sub.equals("treasury") || sub.equals("status")) {
            sender.sendMessage("§6§lWealth Tax §7(prosperity audit)");
            sender.sendMessage("§7Enabled: §f" + t.enabled()
                    + " §8| §7daily chance: §f" + WealthTaxManager.trim(t.chance()) + "%"
                    + " §8| §7cap/event: §f" + t.capSummary());
            sender.sendMessage("§7Last roll day: §f" + (t.lastRollDay().isEmpty() ? "never" : t.lastRollDay())
                    + " §8| §7events: §f" + t.events());
            sender.sendMessage(t.balanceLine());
            sender.sendMessage("§7Earmarks:");
            for (String s : t.bucketLines()) sender.sendMessage(s);
            sender.sendMessage("§7Payout caps: §fper payout " + Menus.plain(capD("tax.max-payout", 25000.0))
                    + " §8| §7per bucket/day §f" + Menus.plain(capD("tax.daily-payout-cap", 50000.0))
                    + " §8| §7repeat window §f" + WealthTaxManager.trim(t.payRepeatWindowMillis() / 60000.0) + "m");
            String acct = t.treasuryAccount();
            if (!acct.isEmpty()) {
                sender.sendMessage("§7Treasury account §f" + acct + "§7: §a" + Menus.plain(t.treasuryLiveBalance()));
            } else {
                sender.sendMessage("§7Treasury mode: §fvirtual ledger §7(balance = raised - spent; no bank account).");
            }
            if (t.lastEvent() > 0) {
                sender.sendMessage("§7Last event: §f" + WealthTaxManager.fmtDate(t.lastEvent())
                        + " §7collected §a" + Menus.plain(t.lastTotal()));
            }
            sender.sendMessage("§7Rates: §f/" + label + " rates §8| §7Ledger: §f/" + label
                    + " ledger §8| §7Spends: §f/" + label + " spends");
            sender.sendMessage(drawLine());
            return true;
        }

        if (sub.equals("rates")) {
            sender.sendMessage("§6§lTax rates §7(progressive - only the slice in each band is taxed)");
            double lower = 0.0;
            for (WealthTaxManager.Bracket b : t.brackets()) {
                String band = b.upTo < 0
                        ? ("above " + Menus.plain(lower))
                        : (Menus.plain(lower) + " §7- §f" + Menus.plain(b.upTo));
                sender.sendMessage("§7" + band + ": §f" + WealthTaxManager.trim(b.percent) + "%");
                if (b.upTo >= 0) lower = b.upTo;
            }
            sender.sendMessage("§7Cap: §f" + t.capSummary() + " §7per player, per event.");
            if (!t.exemptLower().isEmpty()) {
                sender.sendMessage("§7Exempt: §f" + String.join(", ", t.exemptLower()));
            }
            return true;
        }

        if (sub.equals("ledger")) {
            List<String> ev = t.ledgerEvents();
            if (ev.isEmpty()) {
                sender.sendMessage("§7No tax events recorded yet.");
                return true;
            }
            sender.sendMessage("§6§lRecent tax events");
            for (String s : ev) sender.sendMessage(s);
            return true;
        }

        if (sub.equals("spends")) {
            List<String> sp = t.ledgerSpends();
            if (sp.isEmpty()) {
                sender.sendMessage("§7No treasury payouts recorded yet.");
                return true;
            }
            sender.sendMessage("§6§lRecent treasury payouts §7(amount | bucket | to | by | reason)");
            for (String s : sp) sender.sendMessage(s);
            return true;
        }

        if (sub.equals("run") || sub.equals("trigger")) {
            // Preview-first: money only moves on an explicit confirm.
            String a1 = args.length >= 2 ? TextUtil.norm(args[1]) : "";
            if (a1.equals("confirm")) {
                sender.sendMessage("§c§lConfirmed §r§7- collecting for real...");
                t.runEvent(sender, true);
            } else {
                sender.sendMessage("§7Previewing the audit - §fnothing will be deducted§7.");
                t.previewEvent(sender);
            }
            return true;
        }

        if (sub.equals("spend")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " spend <amount> [note...]");
                return true;
            }
            Double amt = Menus.parseAmount(args[1]);
            if (amt == null) {
                sender.sendMessage("§cBad amount.");
                return true;
            }
            StringBuilder note = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (note.length() > 0) note.append(' ');
                note.append(args[i]);
            }
            t.spend(amt, note.toString());
            sender.sendMessage("§aRecorded a treasury spend of " + Menus.plain(amt)
                    + "§7. This is ledger bookkeeping only - pay it out in-game with §f/eco§7. Ledger balance: §a"
                    + Menus.plain(t.treasuryBalance()));
            return true;
        }

        if (sub.equals("reload")) {
            plugin.reloadConfig();
            try { plugin.pruneStaleConfig(); } catch (Exception ignored) {}
            sender.sendMessage("§aAllPurpose config reloaded (tax settings are re-read live).");
            return true;
        }

        if (sub.equals("grants")) {
            sender.sendMessage("§6§lStarter grants §7(" + t.grantCount() + " paid from the treasury)");
            List<String> g = t.grantList();
            if (g.isEmpty()) sender.sendMessage("§7No starter grants paid yet.");
            else for (String s : g) sender.sendMessage(s);
            return true;
        }

        if (sub.equals("pay") || sub.equals("insurance") || sub.equals("prize")) {
            String bucket = sub.equals("prize") ? "prize" : "insurance";
            String label2 = WealthTaxManager.bucketLabel(bucket);
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /" + label + " " + sub + " <player> <amount> <reason...>");
                sender.sendMessage("§7The reason is REQUIRED - it becomes the permanent evidence line in §f/" + label + " spends§7.");
                sender.sendMessage("§7Pays from the §f" + label2 + "§7 bucket. Verify grief claims with CoreProtect first.");
                return true;
            }
            OfflinePlayer target = resolve(sender, args[1]);
            if (target == null) return true;
            Double amt = Menus.parseAmount(args[2]);
            if (amt == null) {
                sender.sendMessage("§cBad amount.");
                return true;
            }
            StringBuilder note = new StringBuilder();
            for (int i = 3; i < args.length; i++) {
                if (note.length() > 0) note.append(' ');
                note.append(args[i]);
            }
            String pname = target.getName() == null ? args[1] : target.getName();

            // --- evidence-trail guards -------------------------------------------------
            if (sender instanceof Player) {
                Player staff = (Player) sender;
                if (staff.getUniqueId().equals(target.getUniqueId())
                        || staff.getName().equalsIgnoreCase(pname)) {
                    sender.sendMessage("§cYou cannot pay yourself from the community fund.");
                    return true;
                }
            }
            long window = t.payRepeatWindowMillis();
            if (window > 0 && t.recentPayout(sender.getName(), target.getUniqueId(), window)) {
                sender.sendMessage("§cYou already paid §f" + pname + "§c from the fund within the last "
                        + WealthTaxManager.trim(window / 60000.0) + "m.");
                sender.sendMessage("§7If it is a genuine second claim, get a second staff member to authorise it.");
                return true;
            }

            // --- caps -----------------------------------------------------------------
            double maxPayout = capD("tax.max-payout", 25000.0);
            double dailyCap = capD("tax.daily-payout-cap", 50000.0);
            if (maxPayout > 0 && amt > maxPayout) {
                sender.sendMessage("§cPer-payout cap is " + Menus.plain(maxPayout) + "§c.");
                return true;
            }
            double paidToday = t.payoutToday(bucket);
            if (dailyCap > 0 && paidToday + amt > dailyCap) {
                sender.sendMessage("§cThe " + label2 + " bucket's daily payout cap is " + Menus.plain(dailyCap)
                        + "§c - already paid " + Menus.plain(paidToday) + "§c today.");
                return true;
            }
            if (amt > t.available(bucket)) {
                sender.sendMessage("§cThe " + label2 + " bucket can't cover that (available "
                        + Menus.plain(t.available(bucket)) + "§c). A tax event tops it up.");
                return true;
            }

            if (t.payFromTreasury(target.getUniqueId(), pname, amt, note.toString(),
                    "payout", bucket, sender.getName())) {
                sender.sendMessage("§aPaid " + Menus.plain(amt) + " to §f" + pname + "§a from the "
                        + label2 + " bucket. Ledger balance: §a" + Menus.plain(t.treasuryBalance()));
                try {
                    if (target.isOnline() && target.getPlayer() != null) {
                        target.getPlayer().sendMessage("§6§l✦ COMMUNITY FUND §r§7- you received §a"
                                + Menus.plain(amt) + "§7 from the community fund.");
                    }
                } catch (Exception ignored) {}
            } else {
                sender.sendMessage("§cPayout failed (no economy, or the bucket cannot cover it).");
            }
            return true;
        }

        if (sub.equals("draw") || sub.equals("prizedraw")) {
            PrizeDrawManager pd = plugin.getPrizeDraw();
            if (pd == null) {
                sender.sendMessage("§cPrize draw manager not initialized.");
                return true;
            }
            String a1 = args.length >= 2 ? TextUtil.norm(args[1]) : "";
            if (a1.equals("confirm")) {
                sender.sendMessage("§c§lConfirmed §r§7- drawing a winner now...");
                pd.runNow(sender);
            } else {
                sender.sendMessage("§7Previewing the prize draw - §fnothing will be paid§7.");
                pd.preview(sender);
            }
            return true;
        }

        if (sub.equals("draws")) {
            sender.sendMessage("§6§lPrize draws");
            List<String> d = t.prizeDraws(10);
            if (d.isEmpty()) sender.sendMessage("§7No prize draws recorded yet.");
            else for (String s : d) sender.sendMessage(s);
            return true;
        }

        sender.sendMessage("§cUsage: /" + label
                + " <balance|patrons|info|rates|ledger|spends|grants|run [confirm]|draw [confirm]|draws|pay|prize|spend|reload>");
        return true;
    }

    /** One line describing the weekly prize draw, for /tax info and /tax balance. */
    private String drawLine() {
        try {
            PrizeDrawManager pd = plugin.getPrizeDraw();
            if (pd == null) return "§7Prize draw: §cnot available";
            if (!pd.enabled()) {
                return "§7Prize draw: §cdisabled §8(weekly " + Menus.plain(pd.amount()) + ")";
            }
            String s = "§7Prize draw: §a" + Menus.plain(pd.amount()) + " §7every §f"
                    + pd.weekdayLabel() + " " + pd.hourUtc() + ":00 UTC §8(next " + pd.nextLabel() + ")";
            if (!pd.lastWinner().isEmpty()) {
                s += " §8| last: §f" + pd.lastWinner() + " §8" + WealthTaxManager.fmtDate(pd.lastDraw());
            }
            return s;
        } catch (Exception e) {
            return "§7Prize draw: §cnot available";
        }
    }

    private double capD(String k, double d) {
        try { return plugin.getConfig().getDouble(k, d); } catch (Exception e) { return d; }
    }

    private OfflinePlayer resolve(CommandSender sender, String name) {
        try {
            org.bukkit.entity.Player on = Bukkit.getPlayerExact(name);
            if (on != null) return on;
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op == null || (!op.hasPlayedBefore() && !op.isOnline())) {
                sender.sendMessage("§cUnknown player: " + name);
                return null;
            }
            return op;
        } catch (Exception e) {
            sender.sendMessage("§cCould not resolve " + name + ".");
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        boolean admin = sender.hasPermission("allpurpose.tax.admin");
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("balance", "patrons"));
            if (admin) subs.addAll(Arrays.asList("info", "rates", "ledger", "spends", "grants",
                    "run", "draw", "draws", "pay", "prize", "spend", "reload"));
            return subs;
        }
        if (!admin) return new ArrayList<>();
        String sub = TextUtil.norm(args[0]);
        if (args.length == 2 && (sub.equals("run") || sub.equals("trigger") || sub.equals("draw"))) {
            return Arrays.asList("preview", "confirm");
        }
        if (args.length == 2 && sub.equals("spend")) return Arrays.asList("1000");
        if (args.length == 2 && (sub.equals("pay") || sub.equals("insurance") || sub.equals("prize"))) {
            return null; // player names
        }
        return new ArrayList<>();
    }
}
