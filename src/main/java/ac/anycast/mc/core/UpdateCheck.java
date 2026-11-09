package ac.anycast.mc.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Which addon versions are current. anycast.ac publishes two lines:
 * <pre>
 * latest 1.2.0     below it the console is reminded to update
 * required 1.1.1   below it the server does not start
 * </pre>
 * A version is only treated as required when its release exists on GitHub too: a
 * broken or tampered file on one side cannot stop every server on its own.
 */
public final class UpdateCheck {
    public static final String RELEASES = "https://github.com/vaflya972-hub/AnyCast.ac-Minecraft-Addon/releases";
    /** Overridable for a local test rig only (http allowed just for 127.0.0.1). */
    static final String URL = System.getProperty("anycast.updateUrl", "https://anycast.ac/ips/minecraft-addon.txt");
    static final String TAG_URL = System.getProperty("anycast.releaseTagUrl", RELEASES + "/tag/v");
    static final int MAX_BYTES = 4096;
    static final Pattern VERSION = Pattern.compile("\\d{1,4}(\\.\\d{1,4}){1,3}");
    /** A missing release is asked again after this long. */
    static final long NO_RELEASE_RETRY_MS = 60 * 60_000;

    public enum Level { OK, OUTDATED, CRITICAL, UNKNOWN }

    public record Result(Level level, String current, String latest, String required) {
        static Result unknown(String current) {
            return new Result(Level.UNKNOWN, current, null, null);
        }
    }

    record Info(String latest, String required) {
    }

    /** The release check: true when release v{version} exists. */
    interface ReleaseCheck {
        boolean exists(String version);
    }

    private final Path cacheFile;
    private final HttpClient http;
    private final Map<String, Long> confirmed = new ConcurrentHashMap<>();
    private final Map<String, Long> missing = new ConcurrentHashMap<>();
    private volatile String lastError = "";

    public UpdateCheck(Path dir) {
        this.cacheFile = dir.resolve("version-cache.txt");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String lastError() {
        return lastError;
    }

    /** Downloads the versions (or takes the last good copy) and says where {@code current} stands. Never throws. */
    public Result check(String current, String userAgent) {
        if (!isRelease(current)) {
            return Result.unknown(current); // a dev build
        }
        Info info = null;
        String text = download(userAgent);
        if (text != null) {
            info = parse(text);
            if (info != null) {
                try {
                    Files.writeString(cacheFile, text, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // read-only folder: works without the cache
                }
            } else {
                lastError = "no version lines in the answer";
            }
        }
        if (info == null) {
            try {
                info = parse(Files.readString(cacheFile, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // no cache yet
            }
        }
        return evaluate(info, current, this::releaseExists);
    }

    static Result evaluate(Info info, String current, ReleaseCheck releases) {
        if (info == null) {
            return Result.unknown(current);
        }
        if (info.required() != null && compare(current, info.required()) < 0 && releases.exists(info.required())) {
            return new Result(Level.CRITICAL, current, info.latest(), info.required());
        }
        if (compare(current, info.latest()) < 0) {
            return new Result(Level.OUTDATED, current, info.latest(), info.required());
        }
        return new Result(Level.OK, current, info.latest(), info.required());
    }

    private String download(String userAgent) {
        if (!IpList.urlAllowed(URL)) {
            lastError = "bad update url";
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = res.body()) {
                body = in.readNBytes(MAX_BYTES + 1);
            }
            if (res.statusCode() != 200 || body.length > MAX_BYTES) {
                lastError = "HTTP " + res.statusCode();
                return null;
            }
            lastError = "";
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            lastError = e.getClass().getSimpleName();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            lastError = e.getClass().getSimpleName();
            return null;
        }
    }

    /** Release v{version} on GitHub. Remembered once found; a missing one is asked again in an hour. */
    boolean releaseExists(String version) {
        if (confirmed.containsKey(version)) {
            return true;
        }
        Long miss = missing.get(version);
        if (miss != null && System.currentTimeMillis() - miss < NO_RELEASE_RETRY_MS) {
            return false;
        }
        String url = TAG_URL + version;
        if (!IpList.urlAllowed(url)) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code == 200) {
                confirmed.put(version, System.currentTimeMillis());
                return true;
            }
        } catch (IOException e) {
            // GitHub not reachable: not confirmed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // bad url
        }
        missing.put(version, System.currentTimeMillis());
        return false;
    }

    /** "latest X" and "required Y" lines; anything else (a captcha page, HTML) gives null. */
    static Info parse(String text) {
        String latest = null;
        String required = null;
        for (String raw : text.split("\r?\n")) {
            String l = raw.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] p = l.split("\\s+");
            if (p.length != 2 || !isRelease(p[1])) {
                continue;
            }
            if (p[0].equals("latest")) {
                latest = p[1];
            } else if (p[0].equals("required")) {
                required = p[1];
            }
        }
        if (latest == null) {
            return null;
        }
        if (required != null && compare(required, latest) > 0) {
            required = null; // required above latest is a broken file, not a reason to stop servers
        }
        return new Info(latest, required);
    }

    static boolean isRelease(String v) {
        return v != null && VERSION.matcher(v).matches();
    }

    /** Dotted numbers: 1.10.0 is above 1.9.3, 1.2 equals 1.2.0. */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? Integer.parseInt(x[i]) : 0;
            int v = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (u != v) {
                return Integer.compare(u, v);
            }
        }
        return 0;
    }

    /** Console banner for a result (empty when there is nothing to say). */
    public static List<String> banner(Result r) {
        List<String> l = new ArrayList<>();
        String bar = "=".repeat(72);
        switch (r.level()) {
            case OUTDATED -> {
                l.add(bar);
                l.add("AnyCast.ac Minecraft Addon " + r.current() + " устарел, вышла " + r.latest() + ". Обновите плагин: в новых версиях закрыты уязвимости.");
                l.add("AnyCast.ac Minecraft Addon " + r.current() + " is out of date, " + r.latest() + " is out. Update it: new versions fix security issues.");
                l.add(RELEASES + "/latest");
                l.add(bar);
            }
            case CRITICAL -> {
                l.add(bar);
                l.add("КРИТИЧНОЕ ОБНОВЛЕНИЕ AnyCast.ac Minecraft Addon: версия " + r.current() + " небезопасна, нужна " + r.required() + " или новее.");
                l.add("Сервер не запустится, пока плагин не обновлён.");
                l.add("CRITICAL UPDATE of AnyCast.ac Minecraft Addon: " + r.current() + " is unsafe, " + r.required() + " or newer is required.");
                l.add("The server will not start until the addon is updated.");
                l.add(RELEASES + "/latest");
                l.add(bar);
            }
            default -> {
            }
        }
        return l;
    }
}
