package com.hush.allpurpose;

import java.util.Locale;
import net.kyori.adventure.text.Component;

/** Shared string/color helpers. Stateless. */
public final class TextUtil {

    private TextUtil() {}

    // norm() ONLY lowercases - it deliberately preserves '+', '-', '.' and every
    // other character. Rank keys flow through here (findKey, ladder matching,
    // player rank storage), so keeping it a pure lowercase mapping guarantees
    // "FIRE+" -> "fire+" never collapses into "fire". Do not add stripping here.
    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * Storage key for a rank name (used when WRITING ranks.yml entries).
     *
     * Why this exists: Bukkit's YamlConfiguration treats '.' as a path separator,
     * so a literal '.' inside a key would nest - "ranks.a.b.name" would write into
     * section "a" instead of creating a sibling rank "a.b", colliding with rank A
     * (findKey only iterates top-level keys, so the dotted rank would be lost).
     * MemorySection offers no escaping, so we encode '.' as U+00B7 (·) instead.
     *
     * Collision safety: rank names are restricted to [A-Za-z0-9_+-.] and norm()
     * only lowercases - none of those chars lowercases to U+00B7 - so the mapping
     * is injective: "FIRE+" -> "fire+", "FIRE" -> "fire", "A.B" -> "a·b" can never
     * collapse two different names onto one key.
     *
     * Lookups do NOT need the reverse mapping: RankManager.findKey() matches both
     * the stored key AND the raw display name (also normalized), so input like
     * "FIRE.PLUS" / config ladder keys / rankup-kills keys still resolve to the
     * encoded key via the stored display name. Existing lowercase keys (member,
     * ht1, deadliest, boss, fireplus, ...) are unaffected - they contain no dots.
     */
    public static String rankKey(String name) {
        return norm(name).replace('.', '\u00B7');
    }

    private static final String LEGACY_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRr";

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 6 hex digits -> vanilla §x§R§R§G§G§B§B section string. */
    private static String hexToSection(String hex) {
        StringBuilder b = new StringBuilder(13);
        b.append('§').append('x');
        for (char d : hex.toUpperCase(Locale.ROOT).toCharArray()) b.append('§').append(d);
        return b.toString();
    }

    private static final java.util.Map<String, String> NAMED_HEX = new java.util.HashMap<>();
    static {
        NAMED_HEX.put("&0", "000000"); NAMED_HEX.put("&1", "0000AA"); NAMED_HEX.put("&2", "00AA00");
        NAMED_HEX.put("&3", "00AAAA"); NAMED_HEX.put("&4", "AA0000"); NAMED_HEX.put("&5", "AA00AA");
        NAMED_HEX.put("&6", "FFAA00"); NAMED_HEX.put("&7", "AAAAAA"); NAMED_HEX.put("&8", "555555");
        NAMED_HEX.put("&9", "5555FF"); NAMED_HEX.put("&a", "55FF55"); NAMED_HEX.put("&b", "55FFFF");
        NAMED_HEX.put("&c", "FF5555"); NAMED_HEX.put("&d", "FF55FF"); NAMED_HEX.put("&e", "FFFF55");
        NAMED_HEX.put("&f", "FFFFFF");
    }

    /**
     * Any color token -> 6-digit uppercase hex, or null:
     *   "#FF5555", "FF5555", "&#FF5555", "&c", "red", "§c" ...
     */
    public static String resolveHex(String token) {
        if (token == null || token.isEmpty()) return null;
        String t = token.trim();
        if (t.startsWith("§") && t.length() == 2) t = "&" + t.substring(1);
        if (t.length() == 2 && t.charAt(0) == '&') {
            String h = NAMED_HEX.get(t.toLowerCase(Locale.ROOT));
            if (h != null) return h;
            return null;
        }
        if (t.startsWith("&#") && t.length() == 8) t = t.substring(1);
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 6) {
            boolean ok = true;
            for (int k = 0; ok && k < 6; k++) if (!isHexDigit(t.charAt(k))) ok = false;
            if (ok) return t.toUpperCase(Locale.ROOT);
            return null;
        }
        String cc = colorCode(t);
        if (cc != null) return NAMED_HEX.get(cc.toLowerCase(Locale.ROOT));
        return null;
    }

    /** Format word -> legacy format code char (bold/italic/underline/strike/magic), else 0. */
    public static char formatChar(String word) {
        if (word == null) return 0;
        String w = word.toLowerCase(Locale.ROOT);
        if (w.equals("bold") || w.equals("b") || w.equals("&l")) return 'l';
        if (w.equals("italic") || w.equals("i") || w.equals("&o")) return 'o';
        if (w.equals("underline") || w.equals("u") || w.equals("&n")) return 'n';
        if (w.equals("strike") || w.equals("strikethrough") || w.equals("s") || w.equals("&m")) return 'm';
        if (w.equals("magic") || w.equals("obfuscated") || w.equals("obf") || w.equals("&k")) return 'k';
        if (w.length() == 2 && w.charAt(0) == '&' && "klmno".indexOf(w.charAt(1)) >= 0) return w.charAt(1);
        return 0;
    }

    private static boolean isFormatCode(char c) {
        return "KkLlMmNnOo".indexOf(c) >= 0;
    }

    private static boolean isColorCode(char c) {
        return LEGACY_CODES.indexOf(c) >= 0 && !isFormatCode(c) && c != 'r' && c != 'R';
    }

    /**
     * Renders text as a per-character hex gradient (&#RRGGBB per char).
     * Existing &l/&o/&n/&m/&k codes inside are kept and re-applied per char;
     * &r clears them. Manual color codes inside pass through untouched.
     */
    public static String gradient(String text, String hex1, String hex2) {
        if (text == null) return "";
        if (hex1 == null || hex2 == null || hex1.length() != 6 || hex2.length() != 6) return text;
        int r1 = Integer.parseInt(hex1.substring(0, 2), 16);
        int g1 = Integer.parseInt(hex1.substring(2, 4), 16);
        int b1 = Integer.parseInt(hex1.substring(4, 6), 16);
        int r2 = Integer.parseInt(hex2.substring(0, 2), 16);
        int g2 = Integer.parseInt(hex2.substring(2, 4), 16);
        int b2 = Integer.parseInt(hex2.substring(4, 6), 16);
        // count visible chars (skip &-code pairs) for an even spread
        int vis = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) { i++; continue; }
            vis++;
        }
        if (vis == 0) return text;
        StringBuilder out = new StringBuilder(text.length() * 10);
        StringBuilder fmts = new StringBuilder();
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                char nxt = text.charAt(i + 1);
                if (nxt == 'r' || nxt == 'R') { fmts.setLength(0); out.append(c).append(nxt); i++; continue; }
                if (isFormatCode(nxt)) { fmts.append('&').append(Character.toLowerCase(nxt)); i++; continue; }
                if (isColorCode(nxt)) { out.append(c).append(nxt); i++; continue; }
                // unknown &-sequence: fall through as literal below
            }
            double t = vis == 1 ? 1.0 : (double) seen / (double) (vis - 1);
            int r = (int) Math.round(r1 + (r2 - r1) * t);
            int g = (int) Math.round(g1 + (g2 - g1) * t);
            int b = (int) Math.round(b1 + (b2 - b1) * t);
            out.append("&#").append(String.format(Locale.ROOT, "%02X%02X%02X", r, g, b));
            out.append(fmts);
            out.append(c);
            seen++;
        }
        return out.toString();
    }

    /**
     * Rotates a 6-char hex colour around the hue wheel by {@code degrees}.
     * The rank animator shifts both gradient ends by the same amount, so the
     * gradient at phase 0 and phase 1 is identical and the loop is seamless.
     */
    public static String rotateHue(String hex, double degrees) {
        if (hex == null || hex.length() != 6) return hex;
        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            double rr = r / 255.0, gg = g / 255.0, bb = b / 255.0;
            double max = Math.max(rr, Math.max(gg, bb));
            double min = Math.min(rr, Math.min(gg, bb));
            double d = max - min;
            double h;
            if (d == 0) h = 0;
            else if (max == rr) h = 60 * (((gg - bb) / d) % 6);
            else if (max == gg) h = 60 * (((bb - rr) / d) + 2);
            else h = 60 * (((rr - gg) / d) + 4);
            h = (h + degrees) % 360;
            if (h < 0) h += 360;
            double s = max == 0 ? 0 : d / max;
            double v = max;
            double c = v * s;
            double x = c * (1 - Math.abs((h / 60) % 2 - 1));
            double m = v - c;
            double nr, ng, nb;
            if (h < 60) { nr = c; ng = x; nb = 0; }
            else if (h < 120) { nr = x; ng = c; nb = 0; }
            else if (h < 180) { nr = 0; ng = c; nb = x; }
            else if (h < 240) { nr = 0; ng = x; nb = c; }
            else if (h < 300) { nr = x; ng = 0; nb = c; }
            else { nr = c; ng = 0; nb = x; }
            int outR = (int) Math.round((nr + m) * 255);
            int outG = (int) Math.round((ng + m) * 255);
            int outB = (int) Math.round((nb + m) * 255);
            return String.format(Locale.ROOT, "%02X%02X%02X", outR, outG, outB);
        } catch (Exception e) {
            return hex;
        }
    }

    /** gradient() with both ends hue-rotated by {@code phase} (0..1) — no frame stepping. */
    public static String gradientAnimated(String text, String hex1, String hex2, double phase) {
        if (text == null) return "";
        double deg = (phase - Math.floor(phase)) * 360.0;
        return gradient(text, rotateHue(hex1, deg), rotateHue(hex2, deg));
    }

    /** Expands <gradient:#AAA:#BBB>text</gradient> tags (named/legacy colors also accepted). */
    private static String expandGradients(String s) {
        if (s == null || s.indexOf("<gradient") < 0) return s;
        StringBuilder out = new StringBuilder(s.length() + 32);
        int i = 0;
        while (i < s.length()) {
            int g = s.indexOf("<gradient:", i);
            if (g < 0) { out.append(s.substring(i)); break; }
            int tagEnd = s.indexOf('>', g);
            if (tagEnd < 0) { out.append(s.substring(i)); break; }
            String spec = s.substring(g + 10, tagEnd);
            int close = s.indexOf("</gradient>", tagEnd);
            if (close < 0) { out.append(s.substring(i, tagEnd + 1)); i = tagEnd + 1; continue; }
            int colon = spec.indexOf(':');
            String h1 = colon < 0 ? null : resolveHex(spec.substring(0, colon));
            String h2 = colon < 0 ? null : resolveHex(spec.substring(colon + 1));
            String inner = s.substring(tagEnd + 1, close);
            out.append(s.substring(i, g));
            if (h1 != null && h2 != null) out.append(gradient(inner, h1, h2));
            else out.append(inner);
            i = close + 11;
        }
        return out.toString();
    }

    /**
     * Converts &-codes to §-codes INCLUDING hex:
     *   &#RRGGBB -> §x§R§R§G§G§B§B
     *   &x&R&R&G&G&B&B -> §x§R§R§G§G§B§B
     *   §#RRGGBB -> §x§R§R§G§G§B§B
     *   &c / &l etc -> §c / §l (unchanged behaviour)
     * Anything unrecognized passes through as literal text.
     */
    public static String legacy(String s) {
        if (s == null) return "";
        s = expandGradients(s);
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < n) {
                char nxt = s.charAt(i + 1);
                // &#RRGGBB (8 chars)
                if (nxt == '#' && i + 8 <= n) {
                    String hex = s.substring(i + 2, i + 8);
                    boolean ok = hex.length() == 6;
                    for (int k = 0; ok && k < 6; k++) if (!isHexDigit(hex.charAt(k))) ok = false;
                    if (ok) {
                        sb.append(hexToSection(hex));
                        i += 7;
                        continue;
                    }
                }
                // &x&R&R&G&G&B&B (14 chars)
                if ((nxt == 'x' || nxt == 'X') && i + 14 <= n && s.charAt(i + 2) == '&') {
                    StringBuilder hex = new StringBuilder(6);
                    boolean ok = true;
                    for (int k = 3; k <= 13 && ok; k += 2) {
                        char d = s.charAt(i + k);
                        if (isHexDigit(d)) hex.append(d); else ok = false;
                        if (k < 13 && s.charAt(i + k + 1) != '&') ok = false;
                    }
                    if (ok && hex.length() == 6) {
                        sb.append(hexToSection(hex.toString()));
                        i += 13;
                        continue;
                    }
                }
                // plain 1-char legacy code
                if (c == '&' && LEGACY_CODES.indexOf(nxt) >= 0) {
                    sb.append('§').append(nxt);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Normalizes a single color/format token to &-form, or null if it isn't one:
     *   "&c", "§c", "&l"       -> "&c", "&l"
     *   "#FF5555", "FF5555"    -> "&#FF5555"
     *   "&#ff5555"             -> "&#FF5555"
     *   "&x&f&f&5&5&5&5"       -> "&#FF5555"
     * Lets /rank create accept hex instead of snapping it to the 16-color palette.
     */
    public static String explicitColor(String token) {
        if (token == null || token.isEmpty()) return null;
        String t = token.startsWith("§") ? "&" + token.substring(1) : token;
        // &x&R&R&G&G&B&B
        if (t.length() == 14 && (t.charAt(1) == 'x' || t.charAt(1) == 'X') && t.charAt(2) == '&') {
            StringBuilder hex = new StringBuilder(6);
            for (int k = 3; k <= 13; k += 2) {
                char d = t.charAt(k);
                if (!isHexDigit(d)) return null;
                hex.append(d);
                if (k < 13 && t.charAt(k + 1) != '&') return null;
            }
            return "&#" + hex.toString().toUpperCase(Locale.ROOT);
        }
        // &#RRGGBB
        if (t.length() == 8 && t.startsWith("&#")) {
            String hex = t.substring(2);
            return hex.matches("[0-9A-Fa-f]{6}") ? "&#" + hex.toUpperCase(Locale.ROOT) : null;
        }
        // bare FF5555 / #FF5555
        if (t.length() == 6 || (t.length() == 7 && t.startsWith("#"))) {
            String hex = t.startsWith("#") ? t.substring(1) : t;
            if (hex.matches("[0-9A-Fa-f]{6}")) return "&#" + hex.toUpperCase(Locale.ROOT);
        }
        // single legacy code
        if (t.length() == 2 && t.charAt(0) == '&' && LEGACY_CODES.indexOf(t.charAt(1)) >= 0) {
            return "&" + Character.toLowerCase(t.charAt(1));
        }
        return null;
    }

    public static Component prefixComponent(String prefix) {
        try {
            if (prefix == null) return Component.empty();
            // legacy() first: expands <gradient> tags plus &-codes and §-codes
            // (hex included) into a pure § string; legacySection() then parses
            // § correctly, including §x hex sections. (legacyAmpersand() left
            // <gradient> tags as literal text in chat.)
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize(legacy(prefix));
        } catch (Exception e) {
            return Component.text(prefix == null ? "" : prefix);
        }
    }

    public static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    public static String colorCode(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        switch (n) {
            case "black": case "negro": return "&0";
            case "dark_blue": case "darkblue": case "navy": return "&1";
            case "dark_green": case "darkgreen": return "&2";
            case "dark_aqua": case "darkaqua": case "teal": return "&3";
            case "dark_red": case "darkred": case "maroon": return "&4";
            case "purple": case "dark_purple": case "darkpurple": return "&5";
            case "gold": case "orange": case "brown": return "&6";
            case "gray": case "grey": case "silver": return "&7";
            case "dark_gray": case "darkgray": case "darkgrey": return "&8";
            case "blue": case "light_blue": return "&9";
            case "green": case "lime": return "&a";
            case "aqua": case "cyan": return "&b";
            case "red": return "&c";
            case "pink": case "light_purple": case "lightpurple": case "magenta": return "&d";
            case "yellow": return "&e";
            case "white": return "&f";
            default: return null;
        }
    }

    public static String closestLegacy(String hex) {
        // hex like #FF5555 or FF5555 -> nearest of the 16 legacy colors
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!h.matches("[0-9A-Fa-f]{6}")) return null;
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        int[][] pal = {
            {0,0,0}, {0,0,170}, {0,170,0}, {0,170,170}, {170,0,0}, {170,0,170},
            {255,170,0}, {170,170,170}, {85,85,85}, {85,85,255}, {85,255,85},
            {85,255,255}, {255,85,85}, {255,85,255}, {255,255,85}, {255,255,255}
        };
        String[] codes = {"0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"};
        int best = 0; int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < pal.length; i++) {
            int d = (pal[i][0]-r)*(pal[i][0]-r) + (pal[i][1]-g)*(pal[i][1]-g) + (pal[i][2]-b)*(pal[i][2]-b);
            if (d < bestD) { bestD = d; best = i; }
        }
        return "&" + codes[best];
    }
}
