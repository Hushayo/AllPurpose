package com.hush.allpurpose;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;

/**
 * Server changelog behind {@code /changelog}, shown with Minecraft's dialog
 * system (1.21.6+). Two ways it reaches a player:
 *
 *   1. {@code /changelog show [version] [player]} - built live in code with
 *      Paper's dialog API and opened with {@code player.showDialog(...)}. This
 *      is always instant, so edits are visible immediately, no restart.
 *   2. The pause menu - dialogs there must live in a datapack and the dialog
 *      registry freezes at boot, so a small generated datapack
 *      ({@code <world>/datapacks/allpurpose_changelog}) is rewritten from
 *      changelog.yml every boot. That copy therefore refreshes on the NEXT
 *      restart (the /changelog show copy never needs one).
 *
 * A "version" is a box in the list; clicking one opens its lines, one line per
 * body element so the entries read as separate lines. Everything is stored in
 * changelog.yml, so nothing here is hardcoded and no recompile is needed.
 */
public class ChangelogManager {

    /** Generated datapack folder name (our own folder, safe to rewrite). */
    private static final String PACK = "allpurpose_changelog";
    private static final String NS = "allpurpose";
    private static final String ROOT_DIALOG = "changelog";
    private static final String ROOT = "versions.";

    private final AllPurpose plugin;
    private final File file;
    private YamlConfiguration cfg;

    public ChangelogManager(AllPurpose plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "changelog.yml");
        load();
    }

    private void load() {
        try {
            if (!file.exists()) {
                cfg = new YamlConfiguration();
                save();
            } else {
                cfg = YamlConfiguration.loadConfiguration(file);
            }
        } catch (Exception e) {
            cfg = new YamlConfiguration();
            plugin.getLogger().warning("Could not read changelog.yml: " + e.getMessage());
        }
    }

    public void save() {
        try { cfg.save(file); } catch (Exception e) {
            plugin.getLogger().warning("Could not save changelog.yml: " + e.getMessage());
        }
    }

    /** Re-read changelog.yml from disk (for hand edits) and refresh the datapack. */
    public void reload() {
        load();
        writeDatapack();
    }

    public void boot() {
        writeDatapack();
        ensurePackEnabled();
    }

    public void shutdown() {
        save();
    }

    // ---------- config-backed defaults ----------

    private boolean enabled() {
        try { return plugin.getConfig().getBoolean("changelog.enabled", true); }
        catch (Exception e) { return true; }
    }

    public boolean screen() {
        try {
            if (cfg.isSet("screen")) return cfg.getBoolean("screen", true);
            return plugin.getConfig().getBoolean("changelog.screen", true);
        } catch (Exception e) { return true; }
    }

    public void setScreen(boolean on) {
        cfg.set("screen", on);
        save();
        writeDatapack();
    }

    public String rootTitle() {
        try { return plugin.getConfig().getString("changelog.root-title", "&6&lMCPVPGG &7- &fChangelog"); }
        catch (Exception e) { return "&6&lMCPVPGG &7- &fChangelog"; }
    }

    private String rootBody() {
        try { return plugin.getConfig().getString("changelog.root-body", "&7Click a version to read its changes."); }
        catch (Exception e) { return "&7Click a version to read its changes."; }
    }

    private int buttonWidth() {
        try {
            int w = plugin.getConfig().getInt("changelog.button-width", 220);
            return w < 1 ? 220 : (w > 1024 ? 1024 : w);
        } catch (Exception e) { return 220; }
    }

    // ---------- versions ----------

    /** Canonical stored key for a version name (lowercase, '.' encoded as '.'). */
    public String key(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String k = TextUtil.rankKey(t);
        if (cfg.isConfigurationSection(ROOT + k)) return k;
        for (String ex : order()) {
            try { if (TextUtil.norm(nameOf(ex)).equals(TextUtil.norm(t))) return ex; }
            catch (Exception ignored) {}
        }
        return k;
    }

    /** A version name the user may type: letters, digits, and _ + - . (max 20). */
    public static boolean validName(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        return t.matches("[A-Za-z0-9_+\\-.]{1,20}");
    }

    public boolean exists(String raw) {
        try {
            String k = key(raw);
            return k != null && cfg.isConfigurationSection(ROOT + k);
        } catch (Exception e) { return false; }
    }

    public String nameOf(String k) {
        try { String n = cfg.getString(ROOT + k + ".name"); return n == null ? k : n; }
        catch (Exception e) { return k == null ? "" : k; }
    }

    public String titleOf(String k) {
        try {
            String v = cfg.getString(ROOT + k + ".title");
            return v == null || v.isEmpty() ? "&6&l" + nameOf(k) : v;
        } catch (Exception e) { return "&6&l" + nameOf(k); }
    }

    public String labelOf(String k) {
        try {
            String v = cfg.getString(ROOT + k + ".label");
            return v == null || v.isEmpty() ? "&e&l" + nameOf(k) : v;
        } catch (Exception e) { return "&e&l" + nameOf(k); }
    }

    public List<String> lines(String k) {
        List<String> out = new ArrayList<>();
        try {
            List<String> l = cfg.getStringList(ROOT + k + ".lines");
            if (l != null) out.addAll(l);
        } catch (Exception ignored) {}
        return out;
    }

    /** Every version key, in display order (then anything not in the order list). */
    public List<String> order() {
        List<String> out = new ArrayList<>();
        try {
            for (String k : cfg.getStringList("order")) {
                if (k != null && !out.contains(k) && cfg.isConfigurationSection(ROOT + k)) out.add(k);
            }
            ConfigurationSection s = cfg.getConfigurationSection("versions");
            if (s != null) for (String k : s.getKeys(false)) if (!out.contains(k)) out.add(k);
        } catch (Exception ignored) {}
        return out;
    }

    public boolean create(String raw) {
        if (!validName(raw)) return false;
        String k = TextUtil.rankKey(raw.trim());
        try {
            if (cfg.isConfigurationSection(ROOT + k)) return false;
            cfg.set(ROOT + k + ".name", raw.trim());
            cfg.set(ROOT + k + ".lines", new ArrayList<String>());
            List<String> o = cfg.getStringList("order");
            if (o == null) o = new ArrayList<>();
            o.add(k);
            cfg.set("order", o);
            save();
            writeDatapack();
            return true;
        } catch (Exception e) { return false; }
    }

    public void setLines(String k, List<String> lines) {
        cfg.set(ROOT + k + ".lines", lines == null ? new ArrayList<String>() : lines);
        save();
        writeDatapack();
    }

    public void addLine(String k, String text) {
        List<String> l = lines(k);
        l.add(text == null ? "" : text);
        setLines(k, l);
    }

    public void insertLine(String k, int idx, String text) {
        List<String> l = lines(k);
        int i = Math.max(0, Math.min(idx, l.size()));
        l.add(i, text == null ? "" : text);
        setLines(k, l);
    }

    public void setLine(String k, int idx, String text) {
        List<String> l = lines(k);
        if (idx < 0 || idx >= l.size()) return;
        l.set(idx, text == null ? "" : text);
        setLines(k, l);
    }

    public boolean removeLine(String k, int idx) {
        List<String> l = lines(k);
        if (idx < 0 || idx >= l.size()) return false;
        l.remove(idx);
        setLines(k, l);
        return true;
    }

    public void setTitle(String k, String text) {
        cfg.set(ROOT + k + ".title", text);
        save();
        writeDatapack();
    }

    public void setLabel(String k, String text) {
        cfg.set(ROOT + k + ".label", text);
        save();
        writeDatapack();
    }

    public void removeVersion(String k) {
        try {
            cfg.set(ROOT + k, null);
            List<String> o = cfg.getStringList("order");
            if (o != null) { o.remove(k); cfg.set("order", o); }
            // drop the version's generated dialog too
            deleteTree(new File(datapackFolder(), "data/" + NS + "/dialog/" + dialogId(k) + ".json"));
            save();
            writeDatapack();
        } catch (Exception ignored) {}
    }

    // ---------- live dialogs (instant, no restart) ----------

    /** Open the changelog (a single version, or the version list) to one player. */
    public void show(Player p, String version) {
        if (p == null) return;
        try {
            if (!enabled()) { p.sendMessage("§7The changelog is disabled."); return; }
            if (version != null && exists(version)) p.showDialog(buildVersionDialog(key(version)));
            else p.showDialog(buildRootDialog());
        } catch (Throwable t) {
            plugin.getLogger().warning("changelog dialog failed: " + t);
            try { p.sendMessage("§cCould not open the changelog dialog: " + t.getMessage()); } catch (Exception ignored) {}
        }
    }

    private Dialog buildVersionDialog(String k) {
        DialogInstancesProvider dp = DialogInstancesProvider.instance();
        DialogBase.Builder b = dp.dialogBaseBuilder(TextUtil.prefixComponent(titleOf(k)))
                .externalTitle(TextUtil.prefixComponent(labelOf(k)))
                .canCloseWithEscape(true)
                .pause(false);
        List<DialogBody> body = new ArrayList<>();
        List<String> lines = lines(k);
        if (lines.isEmpty()) body.add(dp.plainMessageDialogBody(TextUtil.prefixComponent("&7(no entries yet)"), 400));
        else for (String ln : lines) body.add(dp.plainMessageDialogBody(TextUtil.prefixComponent(ln), 400));
        b.body(body);
        ActionButton close = dp.actionButtonBuilder(TextUtil.prefixComponent("&7Close")).width(buttonWidth()).build();
        return Dialog.create(f -> f.empty().base(b.build()).type(DialogType.notice(close)));
    }

    private Dialog buildRootDialog() {
        DialogInstancesProvider dp = DialogInstancesProvider.instance();
        Component title = TextUtil.prefixComponent(rootTitle());
        ActionButton exit = dp.actionButtonBuilder(TextUtil.prefixComponent("&7Close")).width(buttonWidth()).build();
        DialogBase.Builder b = dp.dialogBaseBuilder(title)
                .externalTitle(TextUtil.prefixComponent("&6&lChangelog"))
                .canCloseWithEscape(true)
                .pause(false)
                .body(Collections.singletonList(dp.plainMessageDialogBody(TextUtil.prefixComponent(rootBody()), 400)));
        List<ActionButton> acts = new ArrayList<>();
        for (String k : order()) {
            Dialog vd = buildVersionDialog(k);
            acts.add(dp.actionButtonBuilder(TextUtil.prefixComponent(labelOf(k)))
                    .width(buttonWidth())
                    .action(dp.staticAction(ClickEvent.showDialog(vd)))
                    .build());
        }
        if (acts.isEmpty()) {
            return Dialog.create(f -> f.empty().base(b.build()).type(DialogType.notice(exit)));
        }
        return Dialog.create(f -> f.empty().base(b.build()).type(DialogType.multiAction(acts, exit, 1)));
    }

    // ---------- generated pause-menu datapack ----------

    /** A datapack id for a version key (registry ids are [a-z0-9_.-] only). */
    private static String dialogId(String k) {
        return ("changelog_" + (k == null ? "x" : TextUtil.norm(k))).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** Rewrite the generated datapack. Applies to the pause menu on next boot. */
    public void writeDatapack() {
        try {
            if (!enabled()) return;
            File base = datapackFolder();
            if (base == null) return;

            // wipe our generated tree so removed versions can't linger
            deleteTree(new File(base, "data"));
            debug("datapack folder = " + base);
            write(new File(base, "pack.mcmeta"), packMetaJson());

            List<String> ids = new ArrayList<>();
            for (String k : order()) {
                String id = dialogId(k);
                ids.add(NS + ":" + id);
                write(new File(base, "data/" + NS + "/dialog/" + id + ".json"), versionJson(k));
            }
            write(new File(base, "data/" + NS + "/dialog/" + ROOT_DIALOG + ".json"), rootJson(ids));

            // The pause menu reads minecraft:pause_screen_additions. One dialog in
            // the tag -> that dialog opens on pause. Empty -> no changelog button.
            String tag = (screen() && !ids.isEmpty())
                    ? "{\"replace\":false,\"values\":[\"" + NS + ":" + ROOT_DIALOG + "\"]}"
                    : "{\"replace\":false,\"values\":[]}";
            write(new File(base, "data/minecraft/tags/dialog/pause_screen_additions.json"), tag);
            debug("wrote root + " + ids.size() + " version dialog(s); pause screen " + (screen() ? "on" : "off"));
        } catch (Exception e) {
            plugin.getLogger().warning("changelog datapack write failed: " + e);
        }
    }

    private String versionJson(String k) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n  \"type\": \"minecraft:notice\",\n");
        sb.append("  \"title\": ").append(json(TextUtil.legacy(titleOf(k)))).append(",\n");
        sb.append("  \"external_title\": ").append(json(TextUtil.legacy(labelOf(k)))).append(",\n");
        sb.append("  \"pause\": false,\n  \"can_close_with_escape\": true,\n  \"body\": [\n");
        List<String> lines = lines(k);
        if (lines.isEmpty()) {
            sb.append("    {\"type\":\"minecraft:plain_message\",\"width\":400,\"contents\":\"\\u00a77(no entries yet)\"}");
        } else {
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append(",\n");
                sb.append("    {\"type\":\"minecraft:plain_message\",\"width\":400,\"contents\":")
                        .append(json(TextUtil.legacy(lines.get(i)))).append("}");
            }
        }
        sb.append("\n  ],\n  \"action\": {\"label\": \"Close\"}\n}");
        return sb.toString();
    }

    private String rootJson(List<String> ids) {
        StringBuilder sb = new StringBuilder(512);
        if (ids.isEmpty()) {
            sb.append("{\n  \"type\": \"minecraft:notice\",\n");
            sb.append("  \"title\": ").append(json(TextUtil.legacy(rootTitle()))).append(",\n");
            sb.append("  \"pause\": false,\n  \"body\": [{\"type\":\"minecraft:plain_message\",\"width\":400,\"contents\":\"\\u00a77No changelog yet.\"}],\n");
            sb.append("  \"action\": {\"label\": \"Close\"}\n}");
            return sb.toString();
        }
        sb.append("{\n  \"type\": \"minecraft:dialog_list\",\n");
        sb.append("  \"title\": ").append(json(TextUtil.legacy(rootTitle()))).append(",\n");
        sb.append("  \"external_title\": \"Changelog\",\n");
        sb.append("  \"pause\": false,\n  \"can_close_with_escape\": true,\n");
        sb.append("  \"body\": [{\"type\":\"minecraft:plain_message\",\"width\":400,\"contents\":")
                .append(json(TextUtil.legacy(rootBody()))).append("}],\n");
        sb.append("  \"columns\": 1,\n  \"button_width\": ").append(buttonWidth()).append(",\n");
        sb.append("  \"dialogs\": [");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(json(ids.get(i)));
        }
        sb.append("],\n  \"exit_action\": {\"label\": \"Close\"}\n}");
        return sb.toString();
    }

    /**
     * The LEVEL folder - i.e. where the server actually loads datapacks from.
     *
     * Do NOT use World#getWorldFolder() here: on this Paper build the overworld
     * reports its DIMENSION folder (world/dimensions/minecraft/overworld), and
     * writing our pack there silently does nothing. server.properties'
     * level-name is authoritative; as a fallback we walk the main world folder
     * up until we find the folder holding level.dat.
     */
    private File levelFolder() {
        try {
            File container = Bukkit.getWorldContainer();
            String level = null;
            File props = new File(container, "server.properties");
            if (props.isFile()) {
                Properties p = new Properties();
                try (InputStream in = new FileInputStream(props)) { p.load(in); }
                level = p.getProperty("level-name");
            }
            if (level != null && !level.trim().isEmpty()) {
                File f = new File(container, level.trim());
                if (f.isDirectory()) return f;
            }
        } catch (Exception ignored) {}
        try {
            File f = Bukkit.getWorlds().get(0).getWorldFolder();
            File p = f;
            for (int i = 0; i < 3 && p != null; i++) {
                if (new File(p, "level.dat").isFile()) return p;
                p = p.getParentFile();
            }
            return f;
        } catch (Exception ignored) {}
        return null;
    }

    private File datapackFolder() {
        try {
            File level = levelFolder();
            return level == null ? null : new File(level, "datapacks/" + PACK);
        } catch (Exception e) { return null; }
    }

    /**
     * pack.mcmeta for the CURRENT server version, copied from the pack Bukkit
     * generates for the world - so we never have to hardcode (or guess) the
     * data pack format. Newer versions use min_format/max_format as
     * [major, minor] pairs; older ones use a plain pack_format int.
     */
    private String packMetaJson() {
        String desc = "AllPurpose changelog (generated - do not edit)";
        try {
            File level = levelFolder();
            File ref = level == null ? null : new File(level, "datapacks/bukkit/pack.mcmeta");
            if (ref != null && ref.isFile()) {
                String s = new String(Files.readAllBytes(ref.toPath()), StandardCharsets.UTF_8);
                String min = jsonToken(s, "min_format");
                String max = jsonToken(s, "max_format");
                if (min != null && max != null) {
                    return "{\"pack\":{\"description\":" + json(desc)
                            + ",\"min_format\":" + min + ",\"max_format\":" + max + "}}";
                }
                String pf = jsonToken(s, "pack_format");
                if (pf != null) {
                    return "{\"pack\":{\"pack_format\":" + pf + ",\"description\":" + json(desc) + "}}";
                }
            }
        } catch (Exception ignored) {}
        // Last resort (only when the world has no bukkit pack to copy).
        return "{\"pack\":{\"pack_format\":81,\"description\":" + json(desc) + "}}";
    }

    /** First JSON number or [a, b] array following "key": in s. */
    private static String jsonToken(String s, String key) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(\\[[^\\]]*\\]|[0-9]+)")
                    .matcher(s);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    /**
     * Make sure the pack is enabled for the NEXT boot. A pack written after the
     * boot-time datapack scan is discovered but left disabled, and the dialog
     * registry is only populated from packs loaded at boot - so this cannot help
     * the current boot, but it keeps /changelog screen from needing two restarts.
     */
    private void ensurePackEnabled() {
        try {
            if (!enabled()) return;
            io.papermc.paper.datapack.DatapackManager dm = Bukkit.getDatapackManager();
            if (dm == null) return;
            try { dm.refreshPacks(); } catch (Exception ignored) {}
            for (io.papermc.paper.datapack.Datapack p : dm.getPacks()) {
                String n = p.getName();
                if (n == null || !TextUtil.norm(n).contains(PACK)) continue;
                if (!p.isEnabled()) {
                    p.setEnabled(true);
                    plugin.getLogger().info("[Changelog] enabled datapack '" + n
                            + "' (pause-menu copy appears after the next restart).");
                }
                return;
            }
        } catch (Throwable ignored) {}
    }

    private void debug(String msg) {
        try {
            if (plugin.getConfig().getBoolean("changelog.debug", false)) {
                plugin.getLogger().info("[Changelog] " + msg);
            }
        } catch (Exception ignored) {}
    }

    private static void write(File f, String content) throws Exception {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteTree(File f) {
        try {
            if (f == null || !f.exists()) return;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
            f.delete();
        } catch (Exception ignored) {}
    }

    /** JSON string literal; also escapes legacy § codes so colours survive. */
    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 8);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '\u00a7') b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }
}
