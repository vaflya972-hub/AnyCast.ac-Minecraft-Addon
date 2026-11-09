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
    public static final String KICK_BAD_SIGNATURE =
            "AnyCast.ac: подпись входа не прошла проверку. Сообщите администратору сервера.\n"
            + "AnyCast.ac: login signature check failed. Please tell the server administrator.";
    public static final String KICK_NO_ADDRESS =
            "AnyCast.ac: сервер не смог принять ваш адрес. Сообщите администратору сервера.\n"
            + "AnyCast.ac: the server could not apply your address. Please tell the server administrator.";

    /** Forge markers a real client may append after "\0" (the same list as the anycast.ac edge). */
    static final java.util.Set<String> FORGE_MARKERS = java.util.Set.of("FML", "FML2", "FML3", "FORGE");
    /** Most logins we remember for replay checks; above that new logins are refused rather than forgotten. */
    static final int MAX_SEEN = 200_000;
    private static final InetAddress LOOP4 = literal(new byte[] {127, 0, 0, 1});
    private static final InetAddress LOOP6 = literal(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});

    private static InetAddress literal(byte[] b) {
        try {
            return InetAddress.getByAddress(b);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public final String version;
    public final IpList ips;
    public final AtomicLong blockedConnections = new AtomicLong();
    public final AtomicLong verifiedLogins = new AtomicLong();
    public final AtomicLong rejectedLogins = new AtomicLong();
    /** Logins straight to the server, around anycast.ac (only with allow-direct). */
    public final AtomicLong directLogins = new AtomicLong();

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
    private ScheduledFuture<?> sweepTask;

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
        refreshTask = null;
        sweepTask = null;
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
        boolean loopback = false;
        for (String s : c.allow) {
            Cidr x = Cidr.parse(s);
            if (x == null) {
                out.add("config.yml: allow: \"" + s + "\" is not an IP address or network");
            } else if (x.tooBroad(8, 16)) {
                out.add("config.yml: allow: \"" + s + "\" is too wide (wider than /8 or /16 for IPv6), skipped");
            } else {
                al.add(x);
                loopback |= x.contains(LOOP4) || x.contains(LOOP6);
            }
        }
        if (loopback) {
            out.add("config.yml: allow has a local address (127.0.0.1 or ::1). Connections from it skip every check. "
                    + "If a tunnel agent (playit.gg, ngrok, frp, ssh -R) runs on this machine, every player comes from there: remove the local address.");
        }
        if (c.allowDirect) {
            out.add("config.yml: allow-direct is on, players can also join straight to this server, around anycast.ac. "
                    + "They get no anycast.ac protection from DDoS and bots, bans and country blocks from the panel do not apply to them, "
                    + "and anyone who learns the server address can attack it directly. Turn it off unless you know why you need it.");
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
            if (sweepTask == null) {
                sweepTask = exec.scheduleWithFixedDelay(this::sweepSeen, 1, 1, TimeUnit.MINUTES);
            }
        }
        for (String s : out) {
            log.warn(s);
        }
        log.info("AnyCast.ac Minecraft Addon " + version + " on " + platform + ": direct players " + (c.allowDirect ? "ALLOWED" : "blocked")
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

    /** An address from the allow list: a proxy or tool the owner trusts, it skips every check. */
    public boolean trusted(InetAddress a) {
        return a != null && allowed(a);
    }

    /**
     * The key is set: from an anycast.ac address only signed handshakes pass. Without it any
     * anycast.ac account could point its own server at this address and read the MOTD
     * through anycast.ac, which is how a hidden server gets found.
     */
    public boolean signing() {
        return key != null;
    }

    /** Startup checks: things that give the server address away around anycast.ac. */
    public void warnExposure(boolean queryOn, boolean geyser) {
        if (queryOn) {
            log.warn("Query (UDP) is on: anyone can ask this server's own address for its MOTD, players and plugins, around anycast.ac, "
                    + "and that is how a hidden server gets found. Turn it off: enable-query=false in server.properties, "
                    + "[query] enabled = false in velocity.toml, query_enabled: false in BungeeCord config.yml.");
        }
        if (geyser) {
            log.warn("Geyser is installed: Bedrock players connect to this server's own address over UDP, around anycast.ac, "
                    + "and that port answers anyone with the MOTD. Anyone can find this server's address through it.");
        }
    }

    /** An anycast.ac edge address. */
    public boolean edge(InetAddress a) {
        return a != null && ips.contains(a);
    }

    /** Connection-level check, before any byte is read. */
    public boolean acceptsConnection(InetAddress a) {
        if (a == null || cfg.allowDirect) {
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
        boolean login = intent != 1; // 2 login, 3 transfer (1.20.5+); anything else the server refuses itself
        InetAddress ra = remote == null ? null : remote.getAddress();
        boolean fromEdge = ra != null && ips.contains(ra);
        boolean fromAllow = ra != null && allowed(ra);
        byte[] k = key;
        Payload.Split sp = Payload.split(host);
        String plain = sp == null ? host : sp.cleaned();

        // Forwarding data from a player ("name\0IP\0UUID" of BungeeCord, "///" of TCPShield):
        // a server in BungeeCord mode would take it as the real address and UUID.
        // Only a proxy from the allow list may send it.
        // Neither anycast.ac nor the allow list: only when direct players are allowed. On
        // Paper and Velocity such connections are closed before the handshake; here they can
        // only come when the address was changed before the addon (PROXY protocol on BungeeCord).
        if (!fromEdge && !fromAllow && !cfg.allowDirect) {
            if (login) {
                rejectedLogins.incrementAndGet();
            }
            warn("notedge", "Closed a handshake from " + addr(ra) + ", not an anycast.ac address. "
                    + "If PROXY protocol is on in this server or proxy, turn it off: anycast.ac sends the player address in the signature.");
            return Decision.DROP;
        }
        if (!fromAllow) {
            String safe = safeHost(plain);
            if (safe == null) {
                if (login) {
                    rejectedLogins.incrementAndGet();
                    warn("forwarding", "Dropped a login with forwarding data in the server address from " + addr(ra)
                            + ": someone tried to fake a player address or UUID.");
                }
                return Decision.DROP;
            }
            plain = safe;
        }

        if (sp == null) {
            if (fromEdge && !fromAllow) {
                if (k != null) {
                    // Closed without a word, pings too: an answer here would tell another anycast.ac
                    // account that points its server at this address what is running here.
                    if (login) {
                        rejectedLogins.incrementAndGet();
                    }
                    warn("unsigned", "Closed an unsigned " + (login ? "login" : "ping") + " from anycast.ac (" + addr(ra)
                            + "). Set \"Player address: Plugin\" for this server in the anycast.ac panel. "
                            + "If it is set, another anycast.ac account has pointed its server at this address.");
                    return Decision.DROP;
                }
                if (login) {
                    warn("nokey", "Players come through anycast.ac but config.yml has no key: they are seen with the edge address, "
                            + "and any anycast.ac account that points its server at this address gets through too.");
                }
            }
            if (login && !fromEdge && !fromAllow) {
                directLogins.incrementAndGet();
                if (cfg.debug) {
                    log.info("Direct login from " + addr(ra) + " (allow-direct is on)");
                }
            }
            return plain.equals(host) ? Decision.PASS : Decision.rewrite(plain, null);
        }
        // Only the edge (or a trusted local address) may carry a signature.
        if (!fromEdge && !fromAllow) {
            if (login) {
                rejectedLogins.incrementAndGet();
            }
            if (cfg.debug) {
                log.info("Dropped a signed handshake from " + addr(ra) + " (not an anycast.ac address)");
            }
            return Decision.DROP;
        }
        if (k == null) {
            warn("nokey", "Got a signed login but config.yml has no key: the player is seen with the edge address.");
            return Decision.rewrite(plain, null);
        }
        Payload.Signed s;
        try {
            s = Payload.verify(k, sp.signature());
        } catch (Payload.Invalid e) {
            if (fromAllow) {
                return Decision.rewrite(plain, null);
            }
            // Another key: another anycast.ac server. Closed quietly, as an unsigned one.
            if (login) {
                rejectedLogins.incrementAndGet();
            }
            warn("badsig", "Closed a " + (login ? "login" : "ping") + " from anycast.ac: " + e.getMessage()
                    + ". The key in config.yml must be the key of THIS server in the anycast.ac panel. "
                    + "If it is, another anycast.ac account has pointed its server at this address.");
            return Decision.DROP;
        }
        long now = System.currentTimeMillis() / 1000;
        long age = now - s.unixTime();
        if (Math.abs(age) > cfg.signatureMaxAgeSeconds) {
            if (!login) {
                return Decision.rewrite(plain, null);
            }
            rejectedLogins.incrementAndGet();
            warn("clock", "Rejected a login: signature time is " + age + " s off. Check the server clock (NTP).");
            return Decision.kick(KICK_BAD_SIGNATURE);
        }
        if (login) {
            if (seen.size() >= MAX_SEEN) {
                sweepSeen();
                if (seen.size() >= MAX_SEEN) {
                    rejectedLogins.incrementAndGet();
                    warn("seenfull", "Refused a login: " + MAX_SEEN + " signatures remembered in the last minutes, replay checks are full.");
                    return Decision.DROP;
                }
            }
            // A signature is good for one login: the same one again is a replay.
            if (seen.putIfAbsent(s.id(), s.unixTime()) != null) {
                rejectedLogins.incrementAndGet();
                warn("replay", "Dropped a repeated login signature from " + addr(ra));
                return Decision.DROP;
            }
            verifiedLogins.incrementAndGet();
        }
        if (cfg.debug) {
            log.info("Verified " + (login ? "login" : "status") + " from " + s.address().getAddress().getHostAddress() + " via " + addr(ra));
        }
        return Decision.rewrite(plain, s.address());
    }

    /**
     * The server address with at most a Forge marker after "\0". Null when it carries
     * forwarding data (an address, a UUID, profile JSON, "///"); other unknown extra
     * data is cut off and the name stays.
     */
    static String safeHost(String h) {
        int z = h.indexOf('\0');
        String name = z < 0 ? h : h.substring(0, z);
        if (name.contains("///")) {
            return null;
        }
        if (z < 0) {
            return h;
        }
        String[] rest = h.substring(z + 1).split("\0", -1);
        if ((rest.length == 1 || (rest.length == 2 && rest[1].isEmpty())) && FORGE_MARKERS.contains(rest[0])) {
            return h;
        }
        for (String part : rest) {
            String t = part.trim();
            if (t.contains("///") || Cidr.parse(t) != null || t.matches("[0-9a-fA-F]{32}|[0-9a-fA-F-]{36}")
                    || t.startsWith("[") || t.startsWith("{")) {
                return null;
            }
        }
        return name;
    }

    /** Forgets signatures old enough to fail the time check anyway (the longest allowed age plus a minute). */
    void sweepSeen() {
        long cut = System.currentTimeMillis() / 1000 - 600 - 60;
        seen.values().removeIf(t -> t < cut);
    }

    private static String addr(InetAddress a) {
        return a == null ? "?" : a.getHostAddress();
    }

    /** {@link #warn} for the platform code: same message at most once a minute. */
    public void warnLimited(String kind, String msg) {
        warn(kind, msg);
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
        l.add("Key: " + (key != null ? "set" : "NOT set") + ", direct players: " + (c.allowDirect ? "ALLOWED (allow-direct)" : "blocked"));
        String when = ips.updatedAt() > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ips.updatedAt())) : "-";
        l.add("Edge addresses: " + ips.size() + " (" + ips.source() + ", " + when + ")"
                + (ips.lastError().isEmpty() ? "" : ", last error: " + ips.lastError()));
        l.add("Logins verified: " + verifiedLogins.get() + ", rejected: " + rejectedLogins.get()
                + ", direct connections closed: " + blockedConnections.get()
                + (c.allowDirect || directLogins.get() > 0 ? ", direct logins: " + directLogins.get() : ""));
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
