package ac.anycast.mc.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * Addresses the anycast.ac edge connects from. Order on start: cached copy from the
 * last successful download, otherwise the list built into the jar; then a fresh copy
 * from {@code ip-list-url} every {@code ip-list-refresh-minutes}. A failed or odd
 * download (HTML, empty, too many bad lines) never replaces a working list.
 */
public final class IpList {
    private static final int MAX_BYTES = 64 * 1024;

    private final Path cacheFile;
    private final AddonLog log;
    private final HttpClient http;

    private volatile List<Cidr> entries = List.of();
    private volatile String source = "none";
    private volatile long updatedAt;
    private volatile String lastError = "";

    public IpList(Path dir, AddonLog log) {
        this.cacheFile = dir.resolve("ip-cache.txt");
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean contains(InetAddress a) {
        for (Cidr c : entries) {
            if (c.contains(a)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return entries.size();
    }

    public String source() {
        return source;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public String lastError() {
        return lastError;
    }

    /** Loads the cached copy or the built-in list. */
    public void loadLocal() {
        try {
            if (Files.exists(cacheFile)) {
                List<Cidr> l = Cidr.parseList(Files.readString(cacheFile, StandardCharsets.UTF_8), null);
                if (!l.isEmpty()) {
                    set(l, "cache", Files.getLastModifiedTime(cacheFile).toMillis());
                    return;
                }
            }
        } catch (IOException e) {
            log.warn("Cannot read " + cacheFile.getFileName() + ": " + e.getMessage());
        }
        try (InputStream in = IpList.class.getResourceAsStream("/default-ips.txt")) {
            if (in != null) {
                set(Cidr.parseList(new String(in.readAllBytes(), StandardCharsets.UTF_8), null), "built-in", 0);
            }
        } catch (IOException e) {
            log.warn("Cannot read the built-in address list: " + e.getMessage());
        }
    }

    /** Downloads a fresh list. Returns true when it was applied. */
    public boolean refresh(String url, String userAgent) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = res.body()) {
                body = in.readNBytes(MAX_BYTES + 1);
            }
            if (res.statusCode() != 200) {
                return fail("HTTP " + res.statusCode());
            }
            if (body.length > MAX_BYTES) {
                return fail("list is larger than 64 KB");
            }
            String text = new String(body, StandardCharsets.UTF_8);
            if (text.stripLeading().startsWith("<")) {
                return fail("got an HTML page instead of the list");
            }
            int[] bad = {0};
            List<Cidr> l = Cidr.parseList(text, bad);
            if (l.isEmpty() || bad[0] > l.size()) {
                return fail("list has no valid addresses");
            }
            boolean changed = !l.toString().equals(entries.toString());
            set(l, "download", System.currentTimeMillis());
            lastError = "";
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (changed) {
                log.info("anycast.ac address list updated: " + l.size() + " entries");
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted");
        }
    }

    private boolean fail(String why) {
        if (!why.equals(lastError)) {
            log.warn("Cannot update the anycast.ac address list (" + why + "), keeping " + entries.size() + " entries from " + source);
        }
        lastError = why;
        return false;
    }

    private void set(List<Cidr> l, String src, long at) {
        entries = List.copyOf(l);
        source = src;
        updatedAt = at;
    }
}
