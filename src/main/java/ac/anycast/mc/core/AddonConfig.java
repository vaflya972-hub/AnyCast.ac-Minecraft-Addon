package ac.anycast.mc.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config.yml. The format is a small YAML subset (flat keys, string lists) so that
 * Paper, Velocity and BungeeCord read it the same way without extra libraries.
 */
public final class AddonConfig {
    public String key = "";
    /**
     * Let players also connect straight to the server, around anycast.ac. Off by default:
     * they get none of the anycast.ac protection and the server address has to be public.
     */
    public boolean allowDirect = false;
    public String ipListUrl = "https://anycast.ac/ips/minecraft.txt";
    public int ipListRefreshMinutes = 10;
    /** Addresses that skip every check. Empty by default: a tunnel agent on the same machine would make every player look like 127.0.0.1. */
    public List<String> allow = new ArrayList<>();
    public int signatureMaxAgeSeconds = 120;
    public boolean debug = false;

    /** Problems found while reading (unknown keys, bad values). */
    public final List<String> warnings = new ArrayList<>();

    /** Copies the default config.yml next to the plugin if it is missing, then reads it. */
    public static AddonConfig load(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("config.yml");
        if (!Files.exists(file)) {
            try (InputStream in = AddonConfig.class.getResourceAsStream("/config.yml")) {
                if (in == null) {
                    throw new IOException("config.yml is missing from the jar");
                }
                Files.copy(in, file);
            }
        }
        try {
            // The key is a secret: owner only (where the file system has POSIX permissions).
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Windows, read-only mounts, panels that own the file
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static AddonConfig parse(String text) {
        AddonConfig c = new AddonConfig();
        Map<String, Object> m = yaml(text, c.warnings);
        Boolean direct = null;
        Boolean oldDirect = null;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "key" -> c.key = str(v);
                case "allow-direct" -> direct = bool(v, false, e.getKey(), c.warnings);
                // 1.0.0 name with the opposite meaning: only-anycast: false = allow-direct: true
                case "only-anycast" -> oldDirect = !bool(v, true, e.getKey(), c.warnings);
                case "ip-list-url" -> {
                    String u = str(v);
                    if (IpList.urlAllowed(u)) {
                        c.ipListUrl = u;
                    } else {
                        c.warnings.add("ip-list-url must be an https:// address, using " + c.ipListUrl);
                    }
                }
                case "ip-list-refresh-minutes" -> c.ipListRefreshMinutes = clamp(num(v, c.ipListRefreshMinutes, e.getKey(), c.warnings), 1, 1440);
                case "allow" -> c.allow = list(v);
                case "signature-max-age-seconds" -> c.signatureMaxAgeSeconds = clamp(num(v, c.signatureMaxAgeSeconds, e.getKey(), c.warnings), 30, 600);
                case "debug" -> c.debug = bool(v, c.debug, e.getKey(), c.warnings);
                default -> c.warnings.add("unknown key \"" + e.getKey() + "\"");
            }
        }
        if (direct != null && oldDirect != null && !direct.equals(oldDirect)) {
            c.warnings.add("allow-direct and the old only-anycast disagree, using allow-direct: " + direct
                    + ". Remove only-anycast.");
        }
        c.allowDirect = direct != null ? direct : oldDirect != null && oldDirect;
        return c;
    }

    private static Map<String, Object> yaml(String text, List<String> warnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        String listKey = null;
        int n = 0;
        for (String raw : text.split("\r?\n")) {
            n++;
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            String t = line.trim();
            if (t.startsWith("- ") || t.equals("-")) {
                if (listKey == null) {
                    warnings.add("line " + n + ": list item without a key");
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<String> l = (List<String>) out.computeIfAbsent(listKey, k -> new ArrayList<String>());
                l.add(unquote(t.substring(1).trim()));
                continue;
            }
            int colon = t.indexOf(':');
            if (colon <= 0) {
                warnings.add("line " + n + ": expected \"key: value\"");
                continue;
            }
            String k = t.substring(0, colon).trim();
            String v = t.substring(colon + 1).trim();
            if (v.isEmpty()) {
                listKey = k;
                out.put(k, new ArrayList<String>());
            } else if (v.startsWith("[") && v.endsWith("]")) {
                listKey = null;
                List<String> l = new ArrayList<>();
                for (String p : v.substring(1, v.length() - 1).split(",")) {
                    if (!p.isBlank()) {
                        l.add(unquote(p.trim()));
                    }
                }
                out.put(k, l);
            } else {
                listKey = null;
                out.put(k, unquote(v));
            }
        }
        return out;
    }

    /** Removes "# ..." outside quotes. */
    private static String stripComment(String s) {
        boolean sq = false;
        boolean dq = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '"' && !sq) {
                dq = !dq;
            } else if (c == '#' && !sq && !dq && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String str(Object v) {
        return v instanceof String s ? s.trim() : "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object v) {
        if (v instanceof List<?> l) {
            return new ArrayList<>((List<String>) l);
        }
        String s = str(v);
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(s));
    }

    private static boolean bool(Object v, boolean def, String key, List<String> warnings) {
        String s = str(v).toLowerCase();
        if (s.equals("true") || s.equals("yes") || s.equals("on")) {
            return true;
        }
        if (s.equals("false") || s.equals("no") || s.equals("off")) {
            return false;
        }
        warnings.add(key + ": expected true or false");
        return def;
    }

    private static int num(Object v, int def, String key, List<String> warnings) {
        try {
            return Integer.parseInt(str(v));
        } catch (NumberFormatException e) {
            warnings.add(key + ": expected a number");
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
