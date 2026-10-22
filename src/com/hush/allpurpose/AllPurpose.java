package com.hush.allpurpose;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class AllPurpose extends JavaPlugin {

    private RankManager ranks;
    private LeaderboardManager leaderboard;
    private NametagManager nametag;
    private RankAnimation rankAnimation;
    private LpExpiryNotifier lpExpiry;
    private UpdateManager updater;
    private DiscordWebhook webhook;
    private DiscordStatusBoard statusBoard;
    private DiscordChatLog chatLog;
    private CommandAuditLog auditLog;
    private SpectatorCam spectatorCam;
    private AuthAutoLogin authAutoLogin;
    private SpawnSnapshot spawnSnapshot;
    private CoinFlipManager coinflip;
    private BountyManager bounty;
    private ShopManager shop;
    private ShopLabels shopLabels;
    private HoloTextManager holoText;
    private ChangelogManager changelog;
    private WealthTaxManager wealthTax;
    private StarterGrantManager starterGrant;
    private PrizeDrawManager prizeDraw;

    private AllPurposeAPI api;

    /** Read-only facade for linked plugins. Use this, not getRanks().getX mutators. */
    public AllPurposeAPI getAPI() {
        if (api == null) api = new AllPurposeAPI(this);
        return api;
    }

    /** INTERNAL. Direct RankManager mutators deny external callers by default. */
    public RankManager getRanks() {
        return ranks;
    }

    public LeaderboardManager getLeaderboard() {
        return leaderboard;
    }

    public NametagManager getNametag() {
        return nametag;
    }

    public RankAnimation getRankAnimation() {
        return rankAnimation;
    }

    public LpExpiryNotifier getLpExpiry() {
        return lpExpiry;
    }

    public UpdateManager getUpdater() {
        return updater;
    }

    public DiscordWebhook getWebhook() {
        return webhook;
    }

    public DiscordStatusBoard getStatusBoard() {
        return statusBoard;
    }

    public DiscordChatLog getChatLog() {
        return chatLog;
    }

    /** Command + private-message audit log (Discord channels + local files). */
    public CommandAuditLog getAuditLog() {
        return auditLog;
    }

    public SpectatorCam getSpectatorCam() {
        return spectatorCam;
    }

    public SpawnSnapshot getSpawnSnapshot() {
        return spawnSnapshot;
    }

    public CoinFlipManager getCoinflip() {
        return coinflip;
    }

    public BountyManager getBounty() {
        return bounty;
    }

    public ShopManager getShop() {
        return shop;
    }

    public ShopLabels getShopLabels() {
        return shopLabels;
    }

    public HoloTextManager getHoloText() {
        return holoText;
    }

    public ChangelogManager getChangelog() {
        return changelog;
    }

    public WealthTaxManager getWealthTax() {
        return wealthTax;
    }

    public StarterGrantManager getStarterGrant() {
        return starterGrant;
    }

    public PrizeDrawManager getPrizeDraw() {
        return prizeDraw;
    }

    public String rankDisplayName(UUID id) {
        if (ranks == null) return null;
        return ranks.rankDisplayName(id);
    }

    public String rankPrefix(UUID id) {
        if (ranks == null) return null;
        return ranks.rankPrefix(id);
    }

    /** Survival server: PvP systems were deleted, ranks + utils stay. */
    public boolean isSurvival() {
        try { return getConfig().getBoolean("survival-mode", false); } catch (Exception e) { return false; }
    }

    /** Delete stale config keys so a config read (MCP/file) matches live state (/rank list).
     * Runs on boot + /allpurpose reload. Removes: (1) any top-level key absent
     * from the jar's bundled config.yml (pre-ranks-only leftovers), (2) known-dead
     * PvP-feed / killfx-legacy keys, (3) ladder-scoped entries (rank-ladder,
     * rankup-kills, killfx.*, lp-sync-groups, lp-sync-minimums) naming ranks that
     * no longer exist (e.g. old LT and HT ladder ranks). Saves + logs when anything was removed. */
    public void pruneStaleConfig() {
        try {
            java.util.List<String> removed = new java.util.ArrayList<>();
            // (1) top-level keys missing from the jar defaults.
            try {
                java.util.Set<String> defKeys = null;
                try (java.io.InputStream in = getResource("config.yml")) {
                    if (in != null) {
                        org.bukkit.configuration.file.YamlConfiguration def =
                            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                        defKeys = def.getKeys(false);
                    }
                } catch (Exception ignored) {}
                if (defKeys != null) {
                    for (String k : new java.util.ArrayList<>(getConfig().getKeys(false))) {
                        if (!defKeys.contains(k)) { getConfig().set(k, null); removed.add(k); }
                    }
                }
            } catch (Exception ignored) {}
            // (2) known-dead keys (kept here even if the bundled defaults lag behind).
            // NOTE: death-feed + killfx-lt5 keys are still read by
            // LeaderboardManager, so they must NOT be pruned here.
            String[] dead = {};
            for (String k : dead) {
                try { if (getConfig().contains(k)) { getConfig().set(k, null); removed.add(k); } }
                catch (Exception ignored) {}
            }
            // (3) rank-scoped prune: drop entries naming ranks that no longer exist.
            try {
                if (ranks != null) {
                    java.util.List<String> ladder = null;
                    try { ladder = getConfig().getStringList("rank-ladder"); } catch (Exception ignored) {}
                    if (ladder != null && !ladder.isEmpty()) {
                        java.util.List<String> kept = new java.util.ArrayList<>();
                        for (String e : ladder) {
                            try {
                                if (ranks.findKey(e) != null) kept.add(e);
                                else removed.add("rank-ladder:" + e);
                            } catch (Exception ignored) {}
                        }
                        if (kept.isEmpty()) kept.add("MEMBER");
                        if (kept.size() != ladder.size()) getConfig().set("rank-ladder", kept);
                    }
                    try {
                        if (getConfig().isConfigurationSection("rankup-kills")) {
                            for (String k : new java.util.ArrayList<>(getConfig().getConfigurationSection("rankup-kills").getKeys(false))) {
                                try { if (ranks.findKey(k) == null) { getConfig().set("rankup-kills." + k, null); removed.add("rankup-kills:" + k); } }
                                catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                    try {
                        if (getConfig().isConfigurationSection("killfx")) {
                            for (String k : new java.util.ArrayList<>(getConfig().getConfigurationSection("killfx").getKeys(false))) {
                                try { if (ranks.findKey(k) == null) { getConfig().set("killfx." + k, null); removed.add("killfx:" + k); } }
                                catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                    try {
                        java.util.List<String> groups = getConfig().getStringList("lp-sync-groups");
                        if (groups != null && !groups.isEmpty()) {
                            java.util.List<String> keptG = new java.util.ArrayList<>();
                            for (String g : groups) {
                                try {
                                    if (g != null && !g.trim().isEmpty() && ranks.findKey(g.trim()) != null) keptG.add(g);
                                    else removed.add("lp-sync-groups:" + g);
                                } catch (Exception ignored) {}
                            }
                            if (keptG.size() != groups.size()) getConfig().set("lp-sync-groups", keptG);
                            groups = keptG;
                        }
                        final java.util.Set<String> groupSet = new java.util.HashSet<>();
                        if (groups != null) for (String g : groups) {
                            try { if (g != null) groupSet.add(TextUtil.norm(g.trim())); } catch (Exception ignored) {}
                        }
                        if (getConfig().isConfigurationSection("lp-sync-minimums")) {
                            for (String k : new java.util.ArrayList<>(getConfig().getConfigurationSection("lp-sync-minimums").getKeys(false))) {
                                try {
                                    String v = getConfig().getString("lp-sync-minimums." + k);
                                    if (!groupSet.contains(TextUtil.norm(k)) || ranks.findKey(v) == null) {
                                        getConfig().set("lp-sync-minimums." + k, null);
                                        removed.add("lp-sync-minimums:" + k);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            if (!removed.isEmpty()) {
                try { saveConfig(); } catch (Exception ignored) {}
                try { getLogger().info("[ConfigPrune] removed stale keys: " + String.join(", ", removed)); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            try { getLogger().warning("[ConfigPrune] failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    /** Arena system deleted: TAB region counts always read 0. */
    public int regionCount(String regionId) {
        return 0;
    }

    @Override
    public void onLoad() {
        // Ranks-only: no snapshot staging.
        // Changelog: the pause-menu copy lives in a generated datapack, and
        // datapacks are read LATER in startup than plugin onLoad - so writing it
        // here means a plain restart is enough for the pause menu to pick it up.
        try {
            changelog = new ChangelogManager(this);
            changelog.writeDatapack();
        } catch (Exception e) {
            getLogger().warning("Changelog init failed: " + e.getMessage());
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // RCON / bot-command log spam filter (log-only, RCON stays enabled).
        // Needs no restart after this boot: install reads the toggle live.
        try {
            if (getConfig().getBoolean("rcon-spam-filter-enabled", true)) RconSpamFilter.install(this);
        } catch (Exception ignored) {}
        try { RconSpamFilter.setBotMode(getConfig().getString("bot-command-log-mode", "group")); } catch (Exception ignored) {}
        try { RconSpamFilter.refreshBotNames(this); } catch (Exception ignored) {}
        try {
            long window = Math.max(5L, getConfig().getLong("bot-command-log-window-seconds", 10));
            Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
                @Override public void run() {
                    try { RconSpamFilter.refreshBotNames(AllPurpose.this); } catch (Exception ignored) {}
                    try { RconSpamFilter.flush(AllPurpose.this); } catch (Exception ignored) {}
                }
            }, window * 20L, window * 20L);
        } catch (Exception ignored) {}
        // RANKS-ONLY BUILD: every non-rank PvP system is stripped. Unused
        // manager classes still exist in source but are never constructed.
        // LeaderboardManager stays as the SILENT kill store behind the rank
        // ladder (/lb still present) + NametagManager draws rank tags.
        // LpExpiryNotifier whispers when temp ranks lapse.
        ranks = new RankManager(this);
        leaderboard = new LeaderboardManager(this);
        // Free-form floating text (/setholotext): sweep our tagged displays
        // (crash orphans) then respawn every saved one at its exact spot.
        holoText = new HoloTextManager(this);
        try { holoText.boot(); } catch (Exception e) { getLogger().warning("HoloText init failed: " + e.getMessage()); }
        // Server changelog dialogs (/changelog). Already generated in onLoad;
        // re-generate now that worlds + config are loaded.
        if (changelog == null) { try { changelog = new ChangelogManager(this); } catch (Exception ignored) {} }
        try { if (changelog != null) changelog.boot(); } catch (Exception e) { getLogger().warning("Changelog init failed: " + e.getMessage()); }
        webhook = new DiscordWebhook(this);
        try { leaderboard.refreshBalTop(); } catch (Exception ignored) {}
        try {
            Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
                @Override public void run() {
                    try { leaderboard.refreshBalTop(); } catch (Exception ignored) {}
                    try { leaderboard.updateHolo(); } catch (Exception ignored) {}
                    try { holoText.tick(); } catch (Exception ignored) {}
                }
            }, 1200L, 1200L);
        } catch (Exception ignored) {}
        nametag = new NametagManager(this);
        lpExpiry = new LpExpiryNotifier(this);
        lpExpiry.startPoller();
        statusBoard = new DiscordStatusBoard(this);
        statusBoard.start();
        chatLog = new DiscordChatLog(this);
        // Command + DM audit log (rule 12): everything to a local file, a
        // bounded slice to Discord. Reads its config per call.
        auditLog = new CommandAuditLog(this);
        auditLog.start();
        spawnSnapshot = new SpawnSnapshot(this);
        spectatorCam = new SpectatorCam(this);
        spectatorCam.start();
        authAutoLogin = new AuthAutoLogin(this);
        try { getServer().getPluginManager().registerEvents(authAutoLogin, this); } catch (Exception ignored) {}
        // Money systems (Vault / EssentialsX Economy): coinflip GUI + bounties.
        coinflip = new CoinFlipManager(this);
        try { coinflip.start(); } catch (Exception e) { getLogger().warning("Coinflip init failed: " + e.getMessage()); }
        bounty = new BountyManager(this);
        try { bounty.start(); } catch (Exception e) { getLogger().warning("Bounty init failed: " + e.getMessage()); }
        try { getServer().getPluginManager().registerEvents(bounty, this); } catch (Exception ignored) {}
        // Wealth tax ("prosperity audit"): random, capped, progressive levy on
        // the richest visible balances, funding a public treasury. Ships
        // DISABLED (tax.enabled: false).
        wealthTax = new WealthTaxManager(this);
        try { wealthTax.start(); } catch (Exception e) { getLogger().warning("Tax init failed: " + e.getMessage()); }
        // Community-fund welcome: a one-time, treasury-funded grant once a new
        // player has played long enough. Ships DISABLED (starter-grant.enabled).
        starterGrant = new StarterGrantManager(this);
        try { starterGrant.start(); } catch (Exception e) { getLogger().warning("StarterGrant init failed: " + e.getMessage()); }
        try { getServer().getPluginManager().registerEvents(starterGrant, this); } catch (Exception ignored) {}
        // Weekly prize draw: one ACTIVE player wins the prize bucket each week.
        // Ships DISABLED (prize-draw.enabled: false).
        prizeDraw = new PrizeDrawManager(this);
        try { prizeDraw.start(); } catch (Exception e) { getLogger().warning("PrizeDraw init failed: " + e.getMessage()); }
        // Chest shops: physical player stalls on top of the same Vault economy.
        shop = new ShopManager(this);
        try { shop.start(); } catch (Exception e) { getLogger().warning("Shop init failed: " + e.getMessage()); }
        try { getServer().getPluginManager().registerEvents(new ShopListener(this), this); } catch (Exception ignored) {}
        shopLabels = new ShopLabels(this);
        try { shopLabels.start(); } catch (Exception e) { getLogger().warning("Shop labels init failed: " + e.getMessage()); }
        try { getServer().getPluginManager().registerEvents(new MenuListener(this), this); } catch (Exception ignored) {}
        spawnSnapshot.start();
        updater = new UpdateManager(this);
        try { updater.announcePendingOnBoot(); } catch (Exception ignored) {}
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        // Opped players ignore LP denials for vanilla commands, so those are
        // cancelled here instead (staff: /effect /summon /tick rate /enchant).
        try { getServer().getPluginManager().registerEvents(new CommandGuard(this), this); } catch (Exception ignored) {}
        try { getServer().getPluginManager().registerEvents(webhook, this); } catch (Exception ignored) {}
        try { if (chatLog != null) getServer().getPluginManager().registerEvents(chatLog, this); } catch (Exception ignored) {}
        try { if (auditLog != null) getServer().getPluginManager().registerEvents(auditLog, this); } catch (Exception ignored) {}
        RankCommand rankCmd = new RankCommand(this);
        if (getCommand("rank") != null) {
            getCommand("rank").setExecutor(rankCmd);
            getCommand("rank").setTabCompleter(rankCmd);
        }
        AllPurposeCommand coreCmd = new AllPurposeCommand(this);
        if (getCommand("allpurpose") != null) {
            getCommand("allpurpose").setExecutor(coreCmd);
            getCommand("allpurpose").setTabCompleter(coreCmd);
        }
        LeaderboardCommand lbCmd = new LeaderboardCommand(this);
        if (getCommand("lb") != null) {
            getCommand("lb").setExecutor(lbCmd);
            getCommand("lb").setTabCompleter(lbCmd);
        }
        CoinFlipCommand cfCmd = new CoinFlipCommand(this);
        if (getCommand("coinflip") != null) {
            getCommand("coinflip").setExecutor(cfCmd);
            getCommand("coinflip").setTabCompleter(cfCmd);
        }
        BountyCommand bountyCmd = new BountyCommand(this);
        if (getCommand("bounty") != null) {
            getCommand("bounty").setExecutor(bountyCmd);
            getCommand("bounty").setTabCompleter(bountyCmd);
        }
        TaxCommand taxCmd = new TaxCommand(this);
        if (getCommand("tax") != null) {
            getCommand("tax").setExecutor(taxCmd);
            getCommand("tax").setTabCompleter(taxCmd);
        }
        // NOTE: AllPurpose deliberately does NOT register a /shop command.
        // /shop belongs to EconomyShopGUI; the chest shops are physical only
        // (sneak + right-click a container), so there is no wireless global
        // shop browser to buy from anywhere.
        HoloTextCommand holoTextCmd = new HoloTextCommand(this);
        if (getCommand("setholotext") != null) {
            getCommand("setholotext").setExecutor(holoTextCmd);
            getCommand("setholotext").setTabCompleter(holoTextCmd);
        }
        ChangelogCommand changelogCmd = new ChangelogCommand(this);
        if (getCommand("changelog") != null) {
            getCommand("changelog").setExecutor(changelogCmd);
            getCommand("changelog").setTabCompleter(changelogCmd);
        }
        // Bridge output capture (/apbridge exec): the MCP tool's console /
        // player methods for plugins whose reply the RCON socket drops
        // (LuckPerms, CoreProtect, WorldGuard region commands).
        if (getCommand("apbridge") != null) getCommand("apbridge").setExecutor(new BridgeExecCommand(this));
        // Nametag (rank tags). Clean up tag holograms orphaned by a
        // /reload or a crash, then keep them in sync.
        try { nametag.clearOrphanTagHolo(); } catch (Exception ignored) {}
        if (!"off".equals(nametag.nametagMode())) {
            Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
                @Override public void run() { nametag.tickNametags(); }
            }, 40L, Math.max(2L, getConfig().getLong("nametag.refresh-ticks", 10)));
        }
        rankAnimation = new RankAnimation(this);
        rankAnimation.reload();
        getLogger().info("AllPurpose (ranks-only) enabled. Ranks: " + ranks.rankNames().size());
        getLogger().info("Nametag mode: " + nametag.nametagMode() + " TAB=" + (getServer().getPluginManager().getPlugin("TAB") != null));
        ranks.ensureRank("MEMBER", "&7MEMBER");
        try { pruneStaleConfig(); } catch (Exception ignored) {}
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                boolean ok = new RankExpansion(this).register();
                getLogger().info(ok ? "Registered PlaceholderAPI expansion (hushranks)." : "Could not register PlaceholderAPI expansion.");
            } catch (Exception e) {
                getLogger().warning("PlaceholderAPI hook failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        // Staged repo update swaps here, during a real shutdown/restart only.
        try { if (updater != null) updater.applyPendingOnShutdown(); } catch (Exception ignored) {}
        try { RconSpamFilter.flush(this); } catch (Exception ignored) {}
        try { if (webhook != null) webhook.shutdownFlush(null); } catch (Exception ignored) {}
        try { if (auditLog != null) auditLog.shutdownFlush(); } catch (Exception ignored) {}
        try { RconSpamFilter.uninstall(); } catch (Exception ignored) {}
        try { if (statusBoard != null) statusBoard.markOfflineSync(); } catch (Exception ignored) {}
        try { if (statusBoard != null) statusBoard.shutdown(); } catch (Exception ignored) {}
        try { if (coinflip != null) coinflip.shutdown(); } catch (Exception ignored) {}
        try { if (bounty != null) bounty.shutdown(); } catch (Exception ignored) {}
        try { if (wealthTax != null) wealthTax.shutdown(); } catch (Exception ignored) {}
        try { if (starterGrant != null) starterGrant.shutdown(); } catch (Exception ignored) {}
        try { if (prizeDraw != null) prizeDraw.shutdown(); } catch (Exception ignored) {}
        try { if (shopLabels != null) shopLabels.shutdown(); } catch (Exception ignored) {}
        try { if (shop != null) shop.shutdown(); } catch (Exception ignored) {}
        try { if (spectatorCam != null) spectatorCam.shutdown(); } catch (Exception ignored) {}
        try { if (spawnSnapshot != null) spawnSnapshot.shutdown(); } catch (Exception ignored) {}
        try { if (holoText != null) holoText.shutdown(); } catch (Exception ignored) {}
        try { if (changelog != null) changelog.shutdown(); } catch (Exception ignored) {}
        try { if (leaderboard != null) leaderboard.shutdown(); } catch (Exception ignored) {}
        try { if (lpExpiry != null) lpExpiry.shutdown(); } catch (Exception ignored) {}
        try { if (nametag != null) nametag.clearOrphanTagHolo(); } catch (Exception ignored) {}
        try { if (rankAnimation != null) rankAnimation.stop(); } catch (Exception ignored) {}
    }
}
