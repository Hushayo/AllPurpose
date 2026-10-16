package com.hush.allpurpose;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Repo-based self-updater.
 *
 * <p>Source of truth: GitHub Releases on {@code update-repo}
 * (built by {@code .github/workflows/release.yml}, which attaches
 * {@code AllPurpose.jar} + {@code checksums.txt} to every {@code v*} tag).
 *
 * <p>Safety model (respects the standing no-reload / next-restart-only rule):
 * the running jar is NEVER overwritten while loaded (that is what caused the
 * {@code RankManager$1} NoClassDefFoundError poison before). Downloads stage to
 * {@code plugins/AllPurpose/update/} + an {@code update.properties} marker,
 * and the staged jar only replaces {@code plugins/AllPurpose.jar} inside
 * {@link AllPurpose#onDisable()}, i.e. during a real restart/shutdown. The old
 * jar is kept as a timestamped backup first. Every staged jar is SHA256
 * verified (against the release {@code checksums.txt} when present) and
 * zip-validated ({@code plugin.yml} with {@code name: AllPurpose} inside).
 */
public class UpdateManager {

    private static final String API_LATEST = "https://api.github.com/repos/%s/releases/latest";
    private static final String UA = "AllPurpose-Updater";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECTS = 5;

    private final AllPurpose plugin;
    private volatile boolean busy;

    public UpdateManager(AllPurpose plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        try {
            return plugin.getConfig().getBoolean("update-enabled", true);
        } catch (Exception e) {
            return true;
        }
    }

    private String repo() {
        try {
            String r = plugin.getConfig().getString("update-repo", "Hushayo/AllPurpose");
            return (r == null || r.trim().isEmpty()) ? "Hushayo/AllPurpose" : r.trim();
        } catch (Exception e) {
            return "Hushayo/AllPurpose";
        }
    }

    private String assetName() {
        try {
            String a = plugin.getConfig().getString("update-asset-name", "AllPurpose.jar");
            return (a == null || a.trim().isEmpty()) ? "AllPurpose.jar" : a.trim();
        } catch (Exception e) {
            return "AllPurpose.jar";
        }
    }

    public String currentVersion() {
        try {
            return plugin.getDescription().getVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private File updateDir() {
        File d = new File(plugin.getDataFolder(), "update");
        d.mkdirs();
        return d;
    }

    private File stateFile() {
        return new File(updateDir(), "update.properties");
    }

    /** Live jar we will replace on shutdown. Standard layout: plugins/AllPurpose.jar. */
    private File liveJar() {
        try {
            File parent = plugin.getDataFolder().getParentFile();
            return new File(parent, "AllPurpose.jar");
        } catch (Exception e) {
            return new File("plugins/AllPurpose.jar");
        }
    }

    // ---------- public entry points (called from /allpurpose update ...) ----------

    public void checkAsync(CommandSender sender, String who) {
        if (!enabled()) {
            sender.sendMessage("§cUpdater is disabled (update-enabled: false).");
            return;
        }
        if (!takeBusy(sender)) return;
        final String actor = who;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final List<String> out = new ArrayList<>();
                try {
                    ReleaseInfo rel = fetchLatest();
                    String cur = currentVersion();
                    int cmp = compareVersions(stripV(rel.tag), stripV(cur));
                    out.add("§eAllPurpose update check §7(repo " + repo() + ")");
                    out.add("§7Current: §f" + cur + " §8| §7Latest: §f" + rel.tag);
                    if (cmp > 0) {
                        out.add("§aUpdate available. §7Run §f/allpurpose update download §7then restart to apply.");
                        if (rel.body != null && !rel.body.isEmpty()) {
                            String note = rel.body.length() > 220 ? rel.body.substring(0, 220) + "..." : rel.body;
                            out.add("§7Notes: §f" + note.replaceAll("\\s+", " "));
                        }
                    } else if (cmp == 0) {
                        out.add("§aYou are up to date.");
                    } else {
                        out.add("§7You run a NEWER build than the latest release (" + cur + " > " + rel.tag + ").");
                    }
                    plugin.getLogger().info("[update] check by " + actor + ": current=" + cur + " latest=" + rel.tag);
                } catch (Exception e) {
                    out.add("§cUpdate check failed: " + e.getMessage());
                    plugin.getLogger().warning("[update] check failed: " + e);
                } finally {
                    releaseBusy();
                }
                final List<String> send = out;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        for (String s : send) {
                            try { sender.sendMessage(s); } catch (Exception ignored) {}
                        }
                    }
                });
            }
        });
    }

    public void downloadAsync(CommandSender sender, String tagOrNull, String who) {
        if (!enabled()) {
            sender.sendMessage("§cUpdater is disabled (update-enabled: false).");
            return;
        }
        if (!takeBusy(sender)) return;
        final String tagArg = tagOrNull;
        final String actor = who;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() {
                final List<String> out = new ArrayList<>();
                try {
                    senderSendSync(sender, "§7Resolving release" + (tagArg == null ? " (latest)" : " " + tagArg) + "...");
                    ReleaseInfo rel = (tagArg == null) ? fetchLatest() : fetchReleaseByTag(tagArg);
                    String assetUrl = rel.assetUrl(assetName());
                    if (assetUrl == null) {
                        throw new Exception("release " + rel.tag + " has no asset named " + assetName());
                    }
                    senderSendSync(sender, "§7Downloading §f" + rel.tag + " §7(" + assetName() + ")...");
                    File dir = updateDir();
                    File tmp = new File(dir, assetName() + ".part");
                    File staged = new File(dir, "AllPurpose-" + sanitize(rel.tag) + ".jar");
                    downloadToFile(assetUrl, tmp);
                    String sha = sha256Hex(tmp);
                    // Verify against checksums.txt when the release ships one.
                    String expected = tryFetchChecksum(rel, assetName());
                    if (expected != null && !expected.equalsIgnoreCase(sha)) {
                        tmp.delete();
                        throw new Exception("SHA256 mismatch (got " + shortSha(sha)
                                + ", expected " + shortSha(expected) + "). Aborted.");
                    }
                    validatePluginJar(tmp);
                    Files.move(tmp.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    writeState(rel.tag, staged.getName(), sha, actor);
                    String cur = currentVersion();
                    out.add("§aDownloaded §f" + rel.tag + " §7(sha " + shortSha(sha) + ")"
                            + (expected != null ? " §8[checksum verified]" : " §e[no checksums.txt — jar-content verified only]"));
                    out.add("§7Current: §f" + cur + " §8| §7Staged: §f" + staged.getName());
                    out.add("§eApplies on the NEXT restart (never a reload). §7Old jar is backed up first.");
                    plugin.getLogger().info("[update] downloaded " + rel.tag + " sha=" + sha + " by " + actor);
                } catch (Exception e) {
                    out.add("§cDownload failed: " + e.getMessage());
                    plugin.getLogger().warning("[update] download failed: " + e);
                } finally {
                    releaseBusy();
                }
                final List<String> send = out;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override public void run() {
                        for (String s : send) {
                            try { sender.sendMessage(s); } catch (Exception ignored) {}
                        }
                    }
                });
            }
        });
    }

    public void sendStatus(CommandSender sender) {
        String cur = currentVersion();
        sender.sendMessage("§eAllPurpose updater §7(repo " + repo() + ")");
        sender.sendMessage("§7Current: §f" + cur + " §8| §7Live file: §f" + liveJar().getPath()
                + " §8(" + (liveJar().exists() ? liveJar().length() + " bytes" : "missing?") + ")");
        Properties st = readState();
        if (st == null) {
            sender.sendMessage("§7Staged: §8(none — /allpurpose update download to stage one)");
            return;
        }
        File staged = new File(updateDir(), st.getProperty("file", ""));
        sender.sendMessage("§7Staged: §f" + st.getProperty("tag", "?")
                + " §8(" + st.getProperty("file", "?") + ", sha " + shortSha(st.getProperty("sha256", "?"))
                + ", by " + st.getProperty("by", "?") + ")");
        sender.sendMessage(staged.exists()
                ? "§ePending restart to apply. §7Old jar will be backed up first."
                : "§cStaged file is MISSING on disk — re-run download.");
    }

    /**
     * Called from {@link AllPurpose#onDisable()}. Swaps the staged jar over the
     * live jar during a real shutdown/restart. Never called on reload paths.
     */
    public boolean applyPendingOnShutdown() {
        try {
            Properties st = readState();
            if (st == null) return false;
            String file = st.getProperty("file", "");
            String tag = st.getProperty("tag", "?");
            String sha = st.getProperty("sha256", "");
            File staged = new File(updateDir(), file);
            if (!staged.exists()) {
                plugin.getLogger().warning("[update] staged file missing, cannot apply: " + staged);
                return false;
            }
            String actual = sha256Hex(staged);
            if (sha != null && !sha.isEmpty() && !sha.equalsIgnoreCase(actual)) {
                plugin.getLogger().warning("[update] staged jar hash changed since download, refusing to apply.");
                return false;
            }
            validatePluginJar(staged);
            File live = liveJar();
            if (live.exists()) {
                File bakDir = new File(plugin.getDataFolder(), "update-backup");
                bakDir.mkdirs();
                String bakName = "AllPurpose-backup-" + sanitize(currentVersion())
                        + "-" + System.currentTimeMillis() + ".jar";
                Files.copy(live.toPath(), new File(bakDir, bakName).toPath());
                plugin.getLogger().info("[update] backed up live jar to update-backup/" + bakName);
            }
            Files.move(staged.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try { stateFile().delete(); } catch (Exception ignored) {}
            plugin.getLogger().info("[update] APPLIED " + tag + " -> " + live.getPath()
                    + " (takes effect next boot)");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[update] apply-on-shutdown failed: " + e);
            return false;
        }
    }

    /** Log a reminder on boot when a staged update never got applied. */
    public void announcePendingOnBoot() {
        try {
            Properties st = readState();
            if (st == null) return;
            plugin.getLogger().info("[update] staged " + st.getProperty("tag", "?")
                    + " is downloaded but NOT active — this boot still runs " + currentVersion()
                    + ". The shutdown swap did not run last time (crash/kill -9, or no restart yet)."
                    + " It will apply on the next clean shutdown.");
        } catch (Exception ignored) {}
    }

    // ---------- release API ----------

    private static class ReleaseInfo {
        String tag;
        String body;
        List<String[]> assets = new ArrayList<>(); // [name, url]
        String assetUrl(String name) {
            for (String[] a : assets) if (a[0].equals(name)) return a[1];
            // case-insensitive fallback
            for (String[] a : assets) if (a[0].equalsIgnoreCase(name)) return a[1];
            return null;
        }
    }

    private ReleaseInfo fetchLatest() throws Exception {
        String url = String.format(API_LATEST, repo());
        String json = httpGetText(url);
        return parseRelease(json);
    }

    private ReleaseInfo fetchReleaseByTag(String tag) throws Exception {
        String t = tag.trim();
        String url = "https://api.github.com/repos/" + repo() + "/releases/tags/" + t;
        String json = httpGetText(url);
        return parseRelease(json);
    }

    private static final Pattern TAG_P = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BODY_P = Pattern.compile("\"body\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern ASSET_P = Pattern.compile("\\{[^{}]*?\"name\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\\}");

    private ReleaseInfo parseRelease(String json) throws Exception {
        Matcher m = TAG_P.matcher(json);
        if (!m.find()) throw new Exception("no tag_name in release JSON (rate-limited? repo=" + repo() + ")");
        ReleaseInfo r = new ReleaseInfo();
        r.tag = m.group(1);
        Matcher b = BODY_P.matcher(json);
        if (b.find()) r.body = unescapeJson(b.group(1));
        else r.body = "";
        Matcher a = ASSET_P.matcher(json);
        while (a.find()) r.assets.add(new String[]{a.group(1), a.group(2)});
        // assets sometimes list url before name; cover the reversed order too
        if (r.assets.isEmpty()) {
            Pattern rev = Pattern.compile("\\{[^{}]*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"name\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\\}");
            Matcher a2 = rev.matcher(json);
            while (a2.find()) r.assets.add(new String[]{a2.group(2), a2.group(1)});
        }
        return r;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\n", " ").replace("\\r", "").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Best-effort: download checksums.txt from the same release and find our asset's hash. */
    private String tryFetchChecksum(ReleaseInfo rel, String asset) {
        try {
            String url = rel.assetUrl("checksums.txt");
            if (url == null) url = rel.assetUrl("CHECKSUMS.txt");
            if (url == null) return null;
            File tmp = new File(updateDir(), "checksums.txt.tmp");
            downloadToFile(url, tmp);
            String text = new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
            try { tmp.delete(); } catch (Exception ignored) {}
            for (String line : text.split("[\\r\\n]+")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length >= 2 && parts[1].contains(asset)) return parts[0].trim();
                if (parts.length == 1 && parts[0].length() == 64) return parts[0].trim(); // single-hash file
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- http / io ----------

    private static String httpGetText(String url) throws Exception {
        HttpURLConnection c = openFollowing(url);
        int code = c.getResponseCode();
        if (code == 403 || code == 429) throw new Exception("GitHub API rate-limited (HTTP " + code + ") — try again later.");
        if (code != 200) throw new Exception("GitHub API HTTP " + code + " for " + url);
        try (InputStream in = c.getInputStream()) {
            return readAll(in, 2_000_000);
        }
    }

    private static void downloadToFile(String url, File dest) throws Exception {
        HttpURLConnection c = openFollowing(url);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("download HTTP " + code + " for " + url);
        long max = 120_000_000L; // 120MB sanity cap
        try (InputStream in = c.getInputStream()) {
            java.io.OutputStream fos = new java.io.FileOutputStream(dest);
            try {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > max) throw new Exception("download exceeds 120MB cap, aborting.");
                    fos.write(buf, 0, n);
                }
            } finally {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
        if (!dest.exists() || dest.length() < 10_000) {
            throw new Exception("downloaded file suspiciously small (" + dest.length() + " bytes).");
        }
    }

    private static HttpURLConnection openFollowing(String url) throws Exception {
        String u = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                if (loc == null) throw new Exception("redirect without Location.");
                u = loc;
                continue;
            }
            return c;
        }
        throw new Exception("too many redirects.");
    }

    private static String readAll(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        int n;
        int total = 0;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > cap) throw new Exception("response too large.");
            b.write(buf, 0, n);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) d.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : d.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** Refuse anything that is not really our plugin jar. */
    private static void validatePluginJar(File jar) throws Exception {
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar);
        try {
            java.util.zip.ZipEntry e = z.getEntry("plugin.yml");
            if (e == null) throw new Exception("jar has no plugin.yml — not an AllPurpose build.");
            InputStream in = z.getInputStream(e);
            String desc;
            try { desc = readAll(in, 100_000); } finally { try { in.close(); } catch (Exception ignored) {} }
            Matcher m = Pattern.compile("(?m)^name:\\s*(.+?)\\s*$").matcher(desc);
            if (!m.find() || !m.group(1).trim().equals("AllPurpose")) {
                throw new Exception("jar plugin.yml name is not AllPurpose — refusing.");
            }
        } finally {
            try { z.close(); } catch (Exception ignored) {}
        }
    }

    // ---------- state ----------

    private void writeState(String tag, String file, String sha, String by) {
        try {
            Properties p = new Properties();
            p.setProperty("tag", tag);
            p.setProperty("file", file);
            p.setProperty("sha256", sha);
            p.setProperty("by", by == null ? "?" : by);
            p.setProperty("at", String.valueOf(System.currentTimeMillis()));
            try (java.io.OutputStream o = new java.io.FileOutputStream(stateFile())) {
                p.store(o, "AllPurpose staged update — applied on next shutdown swap");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[update] could not write state: " + e);
        }
    }

    private Properties readState() {
        try {
            File s = stateFile();
            if (!s.exists()) return null;
            Properties p = new Properties();
            try (InputStream in = new java.io.FileInputStream(s)) { p.load(in); }
            if (p.getProperty("file", "").isEmpty()) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- misc ----------

    private boolean takeBusy(CommandSender s) {
        if (busy) {
            s.sendMessage("§cUpdater is already working — try again in a few seconds.");
            return false;
        }
        busy = true;
        return true;
    }

    private void releaseBusy() { busy = false; }

    private void senderSendSync(final CommandSender s, final String msg) {
        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() {
                    try { s.sendMessage(msg); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    private static String stripV(String v) {
        if (v == null) return "";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) return v.substring(1);
        return v;
    }

    private static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String shortSha(String sha) {
        if (sha == null) return "?";
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    /**
     * Compare dotted/dashed versions with a possible {@code -bN} build suffix.
     * Numeric components compare numerically, others lexicographically.
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            int c = comparePart(x, y);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int comparePart(String x, String y) {
        // split leading digits from the rest ("b85" -> 85 + "b")
        long nx = leadingNum(x);
        long ny = leadingNum(y);
        if (nx != ny) return Long.compare(nx, ny);
        String sx = x.replaceFirst("^\\d+", "");
        String sy = y.replaceFirst("^\\d+", "");
        if (sx.isEmpty() && sy.isEmpty()) {
            // both pure numbers handled above; fall through
            try {
                return Long.compare(Long.parseLong(x), Long.parseLong(y));
            } catch (Exception e) {
                return x.compareTo(y);
            }
        }
        return sx.compareTo(sy);
    }

    private static long leadingNum(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return -1;
        try { return Long.parseLong(s.substring(0, i)); } catch (Exception e) { return -1; }
    }
}
