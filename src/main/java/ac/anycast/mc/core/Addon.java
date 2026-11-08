package ac.anycast.mc.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Platform-independent part: config, address lists, handshake decisions, counters. */
public final class Addon {
    public static final String KICK_UNSIGNED =
            "AnyCast.ac: вход пришёл без подписи. Сообщите администратору сервера.\n"
            + "AnyCast.ac: this login was not signed. Please tell the server administrator.";
    public static final String KICK_BAD_SIGNATURE =
            "AnyCast.ac: подпись входа не прошла проверку. Сообщите администратору сервера.\n"
            + "AnyCast.ac: login signature check failed. Please tell the server administrator.";

    public final String version;
    public final IpList ips;
    public final AtomicLong blockedConnections = new AtomicLong();
    public final AtomicLong verifiedLogins = new AtomicLong();
    public final AtomicLong rejectedLogins = new AtomicLong();

    private final Path dir;
    private final AddonLog log;
    private final String platform;
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> warned = new ConcurrentHashMap<>();

    private volatile AddonConfig cfg = new AddonConfig();
    private volatile byte[] key;
    private volatile List<Cidr> allow = List.of();
    private ScheduledExecutorService exec;
    private ScheduledFuture<?> refreshTask;

    public Addon(Path dir, AddonLog log, String platform) {
        this.dir = dir;
        this.log = log;
        this.platform = platform;
        this.version = readVersion();
        this.ips = new IpList(dir, log);
    }

    /** Reads config, loads the cached list and starts the background refresh. */
    public synchronized void start() throws IOException {
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AnyCast-Addon");
            t.setDaemon(true);
            return t;
        });
        ips.loadLocal();
        reload();
    }

    public synchronized void stop() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
    }

    /** Re-reads config.yml and downloads the list again. Returns lines for the command sender. */
    public synchronized List<String> reload() throws IOException {
        AddonConfig c = AddonConfig.load(dir);
        List<String> out = new ArrayList<>();
        for (String w : c.warnings) {
            out.add("config.yml: " + w);
        }
        byte[] k = Payload.parseKey(c.key);
        if (!c.key.isBlank() && k == null) {
            out.add("config.yml: key must be the 43-character key from the anycast.ac panel");
        }
        List<Cidr> al = new ArrayList<>();
        for (String s : c.allow) {
            Cidr x = Cidr.parse(s);
            if (x == null) {
                out.add("config.yml: allow: \"" + s + "\" is not an IP address or network");
            } else {
                al.add(x);
            }
        }
        cfg = c;
        key = k;
        allow = List.copyOf(al);
        if (refreshTask != null) {
            refreshTask.cancel(false);
        }
        if (exec != null) {
            refreshTask = exec.scheduleWithFixedDelay(
                    () -> ips.refresh(cfg.ipListUrl, userAgent()), 0, c.ipListRefreshMinutes, TimeUnit.MINUTES);
        }
        for (String s : out) {
            log.warn(s);
        }
        log.info("AnyCast.ac Minecraft Addon " + version + " on " + platform + ": only-anycast " + (c.onlyAnycast ? "on" : "off")
                + ", key " + (k != null ? "set" : "NOT set") + ", " + "edge addresses: " + ips.size() + " (" + ips.source() + ")");
        if (k == null) {
            log.warn("No key yet: copy it from anycast.ac → Minecraft → your server → Server → Plugin into config.yml, then run /anycast reload");
        }
        return out;
    }

    public String userAgent() {
        return "AnyCast-Minecraft-Addon/" + version + " (" + platform + ")";
    }

    public boolean debug() {
        return cfg.debug;
    }

    public AddonLog log() {
        return log;
    }

    /** Connection-level check, before any byte is read. */
    public boolean acceptsConnection(InetAddress a) {
        if (a == null || !cfg.onlyAnycast) {
            return true;
        }
        if (allowed(a) || ips.contains(a)) {
            return true;
        }
        blockedConnections.incrementAndGet();
        if (cfg.debug) {
            log.info("Closed a direct connection from " + a.getHostAddress() + " (not an anycast.ac address)");
        }
        return false;
    }

    private boolean allowed(InetAddress a) {
        for (Cidr c : allow) {
            if (c.contains(a)) {
                return true;
            }
        }
        return false;
    }

    public enum Action {
        /** Leave the handshake as it is. */
        PASS,
        /** Replace the server address (and the player address when {@link Decision#address()} is set). */
        REWRITE,
        /** Close without a word. */
        DROP,
        /** Send a login disconnect with {@link Decision#kick()} and close. */
        KICK
    }

    public record Decision(Action action, String host, InetSocketAddress address, String kick) {
        static final Decision PASS = new Decision(Action.PASS, null, null, null);
        static final Decision DROP = new Decision(Action.DROP, null, null, null);

        static Decision kick(String text) {
            return new Decision(Action.KICK, null, null, text);
        }

        static Decision rewrite(String host, InetSocketAddress address) {
            return new Decision(Action.REWRITE, host, address, null);
        }
    }

    /**
     * What to do with a handshake.
     *
     * @param remote TCP peer (an anycast.ac edge when the player came through us)
     * @param host   server address field as received
     * @param intent 1 status, 2 login, 3 transfer
     */
    public Decision decide(InetSocketAddress remote, String host, int intent) {
        boolean login = intent == 2 || intent == 3;
        InetAddress ra = remote == null ? null : remote.getAddress();
        boolean fromEdge = ra != null && ips.contains(ra);
        boolean fromAllow = ra != null && allowed(ra);
        byte[] k = key;
        Payload.Split sp = Payload.split(host);

        if (sp == null) {
            if (login && fromEdge && !fromAllow) {
                if (k != null) {
                    rejectedLogins.incrementAndGet();
                    warn("unsigned", "Rejected a login through anycast.ac without a signature (" + ra.getHostAddress()
                            + "). Set \"Player address: Plugin\" for this server in the anycast.ac panel.");
                    return Decision.kick(KICK_UNSIGNED);
                }
                warn("nokey", "Players come through anycast.ac but config.yml has no key, so they are seen with the edge address.");
            }
            return Decision.PASS;
        }
        // Only the edge (or a trusted local address) may carry a signature.
        if (!fromEdge && !fromAllow) {
            if (login) {
                rejectedLogins.incrementAndGet();
            }
            if (cfg.debug) {
                log.info("Dropped a signed handshake from " + (ra == null ? "?" : ra.getHostAddress()) + " (not an anycast.ac address)");
            }
            return Decision.DROP;
        }
        if (k == null) {
            warn("nokey", "Got a signed login but config.yml has no key: the player is seen with the edge address.");
            return Decision.rewrite(sp.cleaned(), null);
        }
        Payload.Signed s;
        try {
            s = Payload.verify(k, sp.signature());
        } catch (Payload.Invalid e) {
            if (!login || fromAllow) {
                return Decision.rewrite(sp.cleaned(), null);
            }
            rejectedLogins.incrementAndGet();
            warn("badsig", "Rejected a login: " + e.getMessage()
                    + ". The key in config.yml must be the key of THIS server in the anycast.ac panel.");
            return Decision.kick(KICK_BAD_SIGNATURE);
        }
        long now = System.currentTimeMillis() / 1000;
        long age = now - s.unixTime();
        if (Math.abs(age) > cfg.signatureMaxAgeSeconds) {
            if (!login) {
                return Decision.rewrite(sp.cleaned(), null);
            }
            rejectedLogins.incrementAndGet();
            warn("clock", "Rejected a login: signature time is " + age + " s off. Check the server clock (NTP).");
            return Decision.kick(KICK_BAD_SIGNATURE);
        }
        if (login) {
            if (seen.putIfAbsent(s.id(), now) != null) {
                rejectedLogins.incrementAndGet();
                warn("replay", "Dropped a repeated login signature from " + ra.getHostAddress());
                return Decision.DROP;
            }
            if (seen.size() > 20000) {
                long cut = now - 2L * cfg.signatureMaxAgeSeconds;
                seen.values().removeIf(t -> t < cut);
            }
            verifiedLogins.incrementAndGet();
        }
        if (cfg.debug) {
            log.info("Verified " + (login ? "login" : "status") + " from " + s.address().getAddress().getHostAddress() + " via " + ra.getHostAddress());
        }
        return Decision.rewrite(sp.cleaned(), s.address());
    }

    /** Same warning at most once a minute, with the number of repeats. */
    private void warn(String kind, String msg) {
        long now = System.currentTimeMillis();
        long[] st = warned.computeIfAbsent(kind, x -> new long[] {0, 0});
        synchronized (st) {
            if (now - st[0] < 60_000) {
                st[1]++;
                return;
            }
            long rep = st[1];
            st[0] = now;
            st[1] = 0;
            log.warn(msg + (rep > 0 ? " (" + rep + " more in the last minute)" : ""));
        }
    }

    /** Lines for /anycast. */
    public List<String> status() {
        AddonConfig c = cfg;
        List<String> l = new ArrayList<>();
        l.add("AnyCast.ac Minecraft Addon " + version + " (" + platform + ")");
        l.add("Key: " + (key != null ? "set" : "NOT set") + ", only-anycast: " + (c.onlyAnycast ? "on" : "off"));
        String when = ips.updatedAt() > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ips.updatedAt())) : "-";
        l.add("Edge addresses: " + ips.size() + " (" + ips.source() + ", " + when + ")"
                + (ips.lastError().isEmpty() ? "" : ", last error: " + ips.lastError()));
        l.add("Logins verified: " + verifiedLogins.get() + ", rejected: " + rejectedLogins.get()
                + ", direct connections closed: " + blockedConnections.get());
        return l;
    }

    private static String readVersion() {
        try (InputStream in = Addon.class.getResourceAsStream("/addon.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }
}
