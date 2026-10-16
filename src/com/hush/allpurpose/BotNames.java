package com.hush.allpurpose;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Minimal bot-name registry reader. The old BotManager (fighter bots,
 * deleted) owned this; kill counting, log grouping, the Discord board and
 * the Discord ad only ever needed names, so they read bots.yml directly now.
 */
public final class BotNames {

    private BotNames() {}

    /** Lowercase names under bots.* in plugins/AllPurpose/bots.yml. */
    public static Set<String> names(AllPurpose plugin) {
        try {
            File f = new File(plugin.getDataFolder(), "bots.yml");
            if (!f.exists()) return Collections.emptySet();
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (y.getConfigurationSection("bots") == null) return Collections.emptySet();
            Set<String> out = new HashSet<>();
            for (String key : y.getConfigurationSection("bots").getKeys(false)) {
                try {
                    String n = y.getString("bots." + key + ".name", key);
                    if (n != null && !n.trim().isEmpty()) {
                        out.add(n.trim().toLowerCase(Locale.ROOT));
                    }
                } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    public static boolean isBot(AllPurpose plugin, String name) {
        try {
            if (name == null || plugin == null) return false;
            return names(plugin).contains(name.trim().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }
}
