package com.hush.allpurpose;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Read-only facade for linked plugins.
 *
 * <p>Staff can read the full source; linked plugins should use ONLY this
 * class. Privileged RankManager mutators (staffSet, setFrozen, lpSync) deny
 * external callers by default (see {@code allow-external-mutations} in
 * config.yml) and log the attempt, so a rogue companion plugin cannot
 * silently escalate ranks on servers running official builds.
 *
 * <p>Note: this is a guardrail, not a sandbox. Any plugin on the same server
 * already has full Bukkit API (console dispatch, LP commands, file access).
 * The real protection is: run official signed Releases, review linked plugins.
 */
public final class AllPurposeAPI {
    private final AllPurpose plugin;

    public AllPurposeAPI(AllPurpose plugin) {
        this.plugin = plugin;
    }

    public String rankDisplayName(UUID id) {
        try { return plugin.rankDisplayName(id); } catch (Exception e) { return null; }
    }

    public String rankPrefix(UUID id) {
        try { return plugin.rankPrefix(id); } catch (Exception e) { return null; }
    }

    public String playerRankKey(Player p) {
        try {
            if (p == null || plugin.getRanks() == null) return null;
            return plugin.getRanks().playerRankKey(p);
        } catch (Exception e) { return null; }
    }

    public String syncRankKey(UUID id) {
        try {
            if (plugin.getRanks() == null) return null;
            return plugin.getRanks().syncRankKey(id);
        } catch (Exception e) { return null; }
    }

    public Set<String> rankKeys() {
        try {
            if (plugin.getRanks() == null) return java.util.Collections.emptySet();
            return plugin.getRanks().rankKeys();
        } catch (Exception e) { return java.util.Collections.emptySet(); }
    }

    public List<String> rankNames() {
        try {
            if (plugin.getRanks() == null) return java.util.Collections.emptyList();
            return plugin.getRanks().rankNames();
        } catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    public List<String> getLadder() {
        try {
            if (plugin.getRanks() == null) return java.util.Collections.singletonList("MEMBER");
            return plugin.getRanks().getLadder();
        } catch (Exception e) { return java.util.Collections.singletonList("MEMBER"); }
    }

    public int lbKills(UUID id) {
        try {
            if (plugin.getLeaderboard() == null || id == null) return 0;
            return plugin.getLeaderboard().lbKills(id);
        } catch (Exception e) { return 0; }
    }

    public int lbDeaths(UUID id) {
        try {
            if (plugin.getLeaderboard() == null || id == null) return 0;
            return plugin.getLeaderboard().lbDeaths(id);
        } catch (Exception e) { return 0; }
    }
}
