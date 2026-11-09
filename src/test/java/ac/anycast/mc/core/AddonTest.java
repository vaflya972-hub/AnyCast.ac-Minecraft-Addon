package ac.anycast.mc.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.anycast.mc.paper.PaperAddon;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddonTest {
    static final String EDGE = "10.1.1.1";
    static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) i;
        }
    }

    @TempDir
    Path dir;

    Addon addon;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "key: \"" + Base64.getUrlEncoder().withoutPadding().encodeToString(KEY) + "\"\n"
                + "ip-list-url: \"http://127.0.0.1:1/none\"\nallow:\n  - 127.0.0.1\n");
        Files.writeString(dir.resolve("ip-cache.txt"), "# test\n" + EDGE + "\n2001:db8::/32\n");
        addon = new Addon(dir, new AddonLog() {
            @Override
            public void info(String msg) {}

            @Override
            public void warn(String msg) {}
        }, "test");
        addon.start();
    }

    // Same vector as TestPluginPayloadVector in mcfront (Go).
    @Test
    void goVector() throws Exception {
        Payload.Signed s = Payload.verify(KEY, "AQDLAHEH1DFpD6FAaiRW4wYrje8W6qT5AMWjAA");
        assertEquals("203.0.113.7", s.address().getAddress().getHostAddress());
        assertEquals(54321, s.address().getPort());
        assertEquals(1762632000L, s.unixTime());
        assertEquals("AQDLAHEH1DFpD6FAaiRW4wYrje8W6qT5AMWjAA",
                Payload.sign(KEY, new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 54321), 1762632000L));
        byte[] other = KEY.clone();
        other[0] = 9;
        assertThrows(Payload.Invalid.class, () -> Payload.verify(other, "AQDLAHEH1DFpD6FAaiRW4wYrje8W6qT5AMWjAA"));
    }

    @Test
    void cidr() throws Exception {
        assertTrue(Cidr.parse("10.0.0.0/8").contains(InetAddress.getByName("10.200.1.1")));
        assertFalse(Cidr.parse("10.0.0.0/8").contains(InetAddress.getByName("11.0.0.1")));
        assertTrue(Cidr.parse("185.219.84.169").contains(InetAddress.getByName("::ffff:185.219.84.169")));
        assertTrue(Cidr.parse("2a0d:5940:79:123::2").contains(InetAddress.getByName("2a0d:5940:79:123::2")));
        assertNull(Cidr.parse("example.com"));
        assertNull(Cidr.parse("1.2.3.4/33"));
        int[] bad = {0};
        assertEquals(2, Cidr.parseList("1.2.3.4\n# c\nnope\n::1 # loop\n", bad).size());
        assertEquals(1, bad[0]);
    }

    @Test
    void configParse() {
        AddonConfig c = AddonConfig.parse("key: 'abc' # comment\nonly-anycast: false\nallow: [1.2.3.4, \"::1\"]\nfoo: 1\n");
        assertEquals("abc", c.key);
        assertTrue(c.allowDirect, "old name only-anycast: false still allows direct players");
        assertEquals(List.of("1.2.3.4", "::1"), c.allow);
        assertEquals(1, c.warnings.size());
    }

    @Test
    void allowDirectIsOffByDefault() throws Exception {
        assertFalse(new AddonConfig().allowDirect);
        AddonConfig shipped = AddonConfig.load(dir.resolve("fresh"));
        assertFalse(shipped.allowDirect, "the config.yml in the jar must keep direct players out");
        assertEquals(List.of(), shipped.warnings);
        assertTrue(AddonConfig.parse("allow-direct: true\n").allowDirect);
        assertFalse(AddonConfig.parse("only-anycast: true\n").allowDirect);
        AddonConfig bad = AddonConfig.parse("allow-direct: maybe\n");
        assertFalse(bad.allowDirect, "a typo must not open the server");
        assertEquals(1, bad.warnings.size());
        AddonConfig both = AddonConfig.parse("allow-direct: false\nonly-anycast: false\n");
        assertFalse(both.allowDirect, "the new name wins");
        assertEquals(1, both.warnings.size(), both.warnings.toString());
    }

    @Test
    void mixedModeLetsBothIn() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, Files.readString(f) + "allow-direct: true\n");
        List<String> warns = addon.reload();
        assertTrue(warns.stream().anyMatch(w -> w.contains("allow-direct is on")), warns.toString());
        assertTrue(String.join("\n", addon.status()).contains("ALLOWED"));

        // Through anycast.ac: the signature is checked and the real address is set.
        Conn viaEdge = new Conn(addon, EDGE);
        viaEdge.send(concat(handshake("mc.example.com///" + sig("203.0.113.7", 50000, now()), 2), LOGIN_START));
        assertEquals(new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 50000), viaEdge.server.address);
        // Unsigned through anycast.ac is still refused.
        Conn unsigned = new Conn(addon, EDGE);
        unsigned.send(concat(handshake("mc.example.com", 2), LOGIN_START));
        assertFalse(unsigned.ch.isActive());

        // Straight to the server: passes with its own address, nothing rewritten.
        Conn direct = new Conn(addon, "8.8.8.8");
        direct.send(concat(handshake("mc.example.com", 2), LOGIN_START));
        assertTrue(direct.ch.isActive());
        assertEquals(new InetSocketAddress("8.8.8.8", 40000), direct.server.address);
        assertArrayEquals(concat(handshake("mc.example.com", 2), LOGIN_START), direct.server.got.toByteArray());
        assertEquals(1, addon.directLogins.get());
        assertEquals(0, addon.blockedConnections.get());

        // A direct player cannot bring a signature (someone else's address) along.
        Conn fake = new Conn(addon, "8.8.4.4");
        fake.send(concat(handshake("mc.example.com///" + sig("203.0.113.8", 1, now()), 2), LOGIN_START));
        assertFalse(fake.ch.isActive());
        assertEquals(0, fake.server.got.size());
        assertEquals(1, addon.verifiedLogins.get());
    }

    /** Stand-in for net.minecraft.network.Connection: same field names as Paper. */
    static final class FakeConnection extends ChannelInboundHandlerAdapter {
        public SocketAddress address;
        public SocketAddress haProxyAddress;
        public java.net.InetSocketAddress virtualHost;
        final ByteArrayOutputStream got = new ByteArrayOutputStream();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            address = ctx.channel().remoteAddress();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf b = (ByteBuf) msg;
            byte[] x = new byte[b.readableBytes()];
            b.readBytes(x);
            b.release();
            got.writeBytes(x);
        }
    }

    static final class Conn {
        final EmbeddedChannel ch;
        final FakeConnection server = new FakeConnection();

        Conn(Addon addon, String ip) {
            this(addon, ip, PaperAddon::setAddress);
        }

        Conn(Addon addon, String ip, HandshakeHandler.AddressSetter setter) {
            InetSocketAddress ra = new InetSocketAddress(ip, 40000);
            ch = new EmbeddedChannel(false, false) {
                @Override
                protected SocketAddress remoteAddress0() {
                    return ra;
                }
            };
            ch.pipeline().addLast(HandshakeHandler.NAME, new HandshakeHandler(addon, setter));
            ch.pipeline().addLast("packet_handler", server);
            try {
                ch.register();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void send(byte[] b) {
            ch.writeInbound(Unpooled.wrappedBuffer(b));
        }
    }

    static byte[] handshake(String host, int intent) {
        ByteBuf body = Unpooled.buffer();
        HandshakeHandler.writeVarInt(body, 0);
        HandshakeHandler.writeVarInt(body, 767);
        byte[] h = host.getBytes(StandardCharsets.UTF_8);
        HandshakeHandler.writeVarInt(body, h.length);
        body.writeBytes(h);
        body.writeShort(25565);
        HandshakeHandler.writeVarInt(body, intent);
        ByteBuf out = Unpooled.buffer();
        HandshakeHandler.writeVarInt(out, body.readableBytes());
        out.writeBytes(body);
        byte[] r = new byte[out.readableBytes()];
        out.readBytes(r);
        return r;
    }

    static final byte[] LOGIN_START = {6, 0, 5, 'S', 't', 'e', 'v', 'e'};

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    String sig(String ip, int port, long time) throws Exception {
        return Payload.sign(KEY, new InetSocketAddress(InetAddress.getByName(ip), port), time);
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    @Test
    void signedLoginGetsRealAddress() throws Exception {
        Conn c = new Conn(addon, EDGE);
        byte[] in = concat(handshake("Play.Example.com///" + sig("203.0.113.7", 50000, now()) + "\0FML3\0", 2), LOGIN_START);
        // byte by byte: the handler must wait for the whole frame
        for (byte b : in) {
            c.send(new byte[] {b});
        }
        assertEquals(new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 50000), c.server.address);
        assertEquals(new InetSocketAddress(EDGE, 40000), c.server.haProxyAddress);
        assertArrayEquals(concat(handshake("Play.Example.com\0FML3\0", 2), LOGIN_START), c.server.got.toByteArray());
        assertNull(c.ch.pipeline().get(HandshakeHandler.NAME), "handler must step aside after the handshake");
        assertEquals(1, addon.verifiedLogins.get());
    }

    @Test
    void ipv6Player() throws Exception {
        Conn c = new Conn(addon, EDGE);
        c.send(handshake("mc.example.com///" + sig("2001:db8::7", 1234, now()), 2));
        assertEquals(new InetSocketAddress(InetAddress.getByName("2001:db8::7"), 1234), c.server.address);
    }

    @Test
    void unsignedLoginFromEdgeIsKicked() {
        Conn c = new Conn(addon, EDGE);
        c.send(concat(handshake("mc.example.com", 2), LOGIN_START));
        ByteBuf out = c.ch.readOutbound();
        assertNotNull(out, "login disconnect expected");
        String s = out.toString(StandardCharsets.UTF_8);
        out.release();
        assertTrue(s.contains("AnyCast.ac"), s);
        assertFalse(c.ch.isActive());
        assertEquals(0, c.server.got.size());
    }

    @Test
    void statusPassesUnchanged() {
        Conn c = new Conn(addon, EDGE);
        byte[] in = concat(handshake("mc.example.com", 1), new byte[] {1, 0});
        c.send(in);
        assertArrayEquals(in, c.server.got.toByteArray());
        assertTrue(c.ch.isActive());
    }

    @Test
    void directConnectionIsClosed() {
        Conn c = new Conn(addon, "8.8.8.8");
        assertFalse(c.ch.isActive());
        assertEquals(0, c.server.got.size());
        assertEquals(1, addon.blockedConnections.get());
    }

    @Test
    void signatureFromAnotherServerIsRejected() throws Exception {
        byte[] other = KEY.clone();
        other[5] ^= 1;
        String bad = Payload.sign(other, new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 1), now());
        Conn c = new Conn(addon, EDGE);
        c.send(handshake("mc.example.com///" + bad, 2));
        assertFalse(c.ch.isActive());
        assertEquals(EDGE, ((InetSocketAddress) c.server.address).getHostString());
    }

    @Test
    void staleAndRepeatedSignatures() throws Exception {
        Conn old = new Conn(addon, EDGE);
        old.send(handshake("mc.example.com///" + sig("203.0.113.7", 1, now() - 3600), 2));
        assertFalse(old.ch.isActive());

        String s = sig("203.0.113.8", 2, now());
        Conn first = new Conn(addon, EDGE);
        first.send(handshake("mc.example.com///" + s, 2));
        assertTrue(first.ch.isActive());
        Conn again = new Conn(addon, EDGE);
        again.send(handshake("mc.example.com///" + s, 2));
        assertFalse(again.ch.isActive());
    }

    @Test
    void signedHandshakeFromStrangerIsDropped() throws Exception {
        Path off = dir.resolve("config.yml");
        Files.writeString(off, Files.readString(off) + "only-anycast: false\n");
        addon.reload();
        Conn c = new Conn(addon, "8.8.8.8");
        assertTrue(c.ch.isActive(), "old only-anycast: false: direct connections are allowed");
        c.send(handshake("mc.example.com///" + sig("1.1.1.1", 1, now()), 2));
        assertFalse(c.ch.isActive());
        Conn plain = new Conn(addon, "8.8.4.4");
        plain.send(handshake("mc.example.com", 2));
        assertTrue(plain.ch.isActive(), "plain direct login passes when direct players are allowed");
    }

    @Test
    void legacyPingPasses() {
        Conn c = new Conn(addon, EDGE);
        c.send(new byte[] {(byte) 0xFE, 1, (byte) 0xFA});
        assertArrayEquals(new byte[] {(byte) 0xFE, 1, (byte) 0xFA}, c.server.got.toByteArray());
    }

    @Test
    void noKeyLetsPlayersInWithEdgeAddress() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "key: \"\"\nip-list-url: \"http://127.0.0.1:1/none\"\n");
        addon.reload();
        Conn c = new Conn(addon, EDGE);
        c.send(concat(handshake("mc.example.com///" + sig("203.0.113.7", 1, now()), 2), LOGIN_START));
        assertTrue(c.ch.isActive());
        assertEquals(EDGE, ((InetSocketAddress) c.server.address).getHostString());
        assertArrayEquals(concat(handshake("mc.example.com", 2), LOGIN_START), c.server.got.toByteArray());
        List<String> st = new ArrayList<>(addon.status());
        assertTrue(st.get(1).contains("NOT set"), st.toString());
    }

    // ---- 1.1.0: closed holes -------------------------------------------------

    void directAllowed() throws Exception {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, Files.readString(f) + "allow-direct: true\n");
        addon.reload();
    }

    /** A login frame of exactly {@code size} bytes (the length prefix is 2 bytes). */
    static byte[] handshakeOfSize(String suffix, int intent, int size) {
        for (int n = 1; n < 400; n++) {
            byte[] h = handshake("a".repeat(n) + suffix, intent);
            if (h.length - 2 == size) {
                return h;
            }
        }
        throw new IllegalStateException("no host length gives " + size);
    }

    @Test
    void frameOf254BytesIsNotTakenForAnOldPing() throws Exception {
        // 254 encodes as FE 01: the old code passed it on as a pre-1.7 ping, unchecked,
        // and Paper reads it as an ordinary login.
        byte[] in = handshakeOfSize(".example.com", 2, 254);
        assertEquals((byte) 0xFE, in[0]);
        assertEquals(1, in[1]);
        Conn c = new Conn(addon, EDGE);
        c.send(concat(in, LOGIN_START));
        assertFalse(c.ch.isActive(), "unsigned login must be refused");
        assertEquals(0, c.server.got.size());

        byte[] big = handshakeOfSize(".example.com", 2, 382); // FE 02
        assertEquals((byte) 0xFE, big[0]);
        Conn d = new Conn(addon, EDGE);
        d.send(big);
        assertFalse(d.ch.isActive());
        assertEquals(0, d.server.got.size());
    }

    @Test
    void longSignedHandshakeStillWorks() throws Exception {
        String s = sig("203.0.113.9", 7, now());
        byte[] in = handshakeOfSize(".example.com///" + s, 2, 254);
        assertEquals((byte) 0xFE, in[0]);
        Conn c = new Conn(addon, EDGE);
        c.send(in);
        assertTrue(c.ch.isActive());
        assertEquals(new InetSocketAddress(InetAddress.getByName("203.0.113.9"), 7), c.server.address);
    }

    @Test
    void nothingAfterAnOldPing() {
        Conn c = new Conn(addon, EDGE);
        c.send(new byte[] {(byte) 0xFE});
        assertTrue(c.ch.isActive());
        c.send(handshake("mc.example.com", 2)); // the rest of a split frame, or a new handshake
        assertFalse(c.ch.isActive());
        assertArrayEquals(new byte[] {(byte) 0xFE}, c.server.got.toByteArray());
    }

    @Test
    void unreadableDataIsClosedNotPassed() {
        for (byte[] junk : new byte[][] {
                {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1}, // bad VarInt
                {5, 7, 1, 2, 3, 4}, // packet 7, not a handshake
                {0}, // empty frame
                {(byte) 0x80, (byte) 0x80, 0x04}, // 64 KiB frame from a stranger
                ("GET / HTTP/1.1\r\nHost: mc.example.com\r\nUser-Agent: curl/8.0\r\nAccept: */*\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII)}) {
            Conn c = new Conn(addon, EDGE);
            c.send(junk);
            assertFalse(c.ch.isActive(), java.util.Arrays.toString(junk));
            assertEquals(0, c.server.got.size());
        }
    }

    @Test
    void trustedAddressesStillPassOddData() {
        Conn c = new Conn(addon, "127.0.0.1");
        byte[] junk = {5, 7, 1, 2, 3, 4};
        c.send(junk);
        assertTrue(c.ch.isActive());
        assertArrayEquals(junk, c.server.got.toByteArray());
    }

    @Test
    void proxyHeaderOnlyFromAnycast() throws Exception {
        byte[] v1 = "PROXY TCP4 203.0.113.7 10.0.0.1 5000 25565\r\n".getBytes(StandardCharsets.US_ASCII);
        Conn edge = new Conn(addon, EDGE);
        edge.send(concat(v1, handshake("mc.example.com", 2)));
        assertTrue(edge.ch.isActive());
        assertEquals(v1.length + handshake("mc.example.com", 2).length, edge.server.got.size());

        directAllowed();
        Conn stranger = new Conn(addon, "8.8.8.8");
        stranger.send(concat(v1, handshake("mc.example.com", 2)));
        assertFalse(stranger.ch.isActive(), "a stranger must not name the player address");
        assertEquals(0, stranger.server.got.size());

        byte[] v2 = HandshakeHandler.PROXY_V2;
        Conn stranger2 = new Conn(addon, "8.8.4.4");
        for (int i = 0; i < v2.length; i++) {
            assertTrue(stranger2.ch.isActive(), "must wait for the whole signature, byte " + i);
            stranger2.send(new byte[] {v2[i]});
        }
        assertFalse(stranger2.ch.isActive());
        assertEquals(0, stranger2.server.got.size());
    }

    @Test
    void forwardingDataFromPlayersIsDropped() throws Exception {
        directAllowed();
        String uuid = "069a79f444e94726a5befca90e38aaf5";
        for (String host : new String[] {
                "mc.example.com\0" + "203.0.113.66\0" + uuid,
                "mc.example.com\0" + "203.0.113.66\0" + uuid + "\0[{\"name\":\"textures\"}]",
                "mc.example.com///203.0.113.66///1762632000///sig"}) {
            Conn c = new Conn(addon, "8.8.8.8");
            c.send(concat(handshake(host, 2), LOGIN_START));
            assertFalse(c.ch.isActive(), host);
            assertEquals(0, c.server.got.size());
        }
        // A Forge marker stays, unknown extra data is cut off.
        Conn forge = new Conn(addon, "8.8.8.8");
        forge.send(handshake("mc.example.com\0FML3\0", 2));
        assertArrayEquals(handshake("mc.example.com\0FML3\0", 2), forge.server.got.toByteArray());
        Conn odd = new Conn(addon, "8.8.8.8");
        odd.send(handshake("mc.example.com\0SomeMod", 2));
        assertArrayEquals(handshake("mc.example.com", 2), odd.server.got.toByteArray());
        // The owner's own proxy may forward.
        Conn own = new Conn(addon, "127.0.0.1");
        byte[] fw = handshake("mc.example.com\0" + "203.0.113.66\0" + uuid, 2);
        own.send(fw);
        assertArrayEquals(fw, own.server.got.toByteArray());
    }

    @Test
    void signedLoginWithForwardingAfterTheSignature() throws Exception {
        Conn c = new Conn(addon, EDGE);
        c.send(handshake("mc.example.com///" + sig("203.0.113.7", 1, now()) + "\0" + "1.2.3.4\0" + "069a79f444e94726a5befca90e38aaf5", 2));
        assertFalse(c.ch.isActive());
        assertEquals(0, c.server.got.size());
    }

    @Test
    void addressThatCannotBeSetRefusesLogin() throws Exception {
        HandshakeHandler.AddressSetter broken = (ch, a) -> {
            throw new NoSuchFieldException("address");
        };
        Conn login = new Conn(addon, EDGE, broken);
        login.send(handshake("mc.example.com///" + sig("203.0.113.7", 1, now()), 2));
        ByteBuf out = login.ch.readOutbound();
        assertNotNull(out);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("AnyCast.ac"));
        out.release();
        assertFalse(login.ch.isActive(), "with the edge address bans by IP would not work");
        assertEquals(0, login.server.got.size());

        Conn status = new Conn(addon, EDGE, broken);
        status.send(handshake("mc.example.com///" + sig("203.0.113.7", 2, now()), 1));
        assertTrue(status.ch.isActive(), "a ping does not need the address");
    }

    @Test
    void silentConnectionIsClosed() {
        Conn c = new Conn(addon, EDGE);
        c.send(new byte[] {20});
        c.ch.advanceTimeBy(HandshakeHandler.HANDSHAKE_TIMEOUT_MS + 1, java.util.concurrent.TimeUnit.MILLISECONDS);
        c.ch.runScheduledPendingTasks();
        assertFalse(c.ch.isActive());
    }

    @Test
    void transferCountsAsLogin() {
        Conn c = new Conn(addon, EDGE);
        c.send(handshake("mc.example.com", 3));
        assertFalse(c.ch.isActive(), "intent 3 (transfer) without a signature is a login without a signature");
    }

    @Test
    void addressListMustNotOpenEverything() {
        assertNotNull(IpList.problem(List.of(Cidr.parse("0.0.0.0/0"))));
        assertNotNull(IpList.problem(List.of(Cidr.parse("185.0.0.0/8"))));
        assertNotNull(IpList.problem(List.of(Cidr.parse("::/0"))));
        assertNotNull(IpList.problem(List.of()));
        assertNull(IpList.problem(List.of(Cidr.parse("185.219.84.0/24"), Cidr.parse("2a0d:5940::/32"))));
        List<Cidr> many = new ArrayList<>();
        for (int i = 0; i <= IpList.MAX_ENTRIES; i++) {
            many.add(Cidr.parse("10." + (i / 256) + "." + (i % 256) + ".1"));
        }
        assertNotNull(IpList.problem(many));
        assertFalse(IpList.urlAllowed("http://anycast.ac/ips/minecraft.txt"));
        assertFalse(IpList.urlAllowed("file:///etc/passwd"));
        assertTrue(IpList.urlAllowed("https://anycast.ac/ips/minecraft.txt"));
        assertTrue(IpList.urlAllowed("http://127.0.0.1:8080/x"));
    }

    @Test
    void badCacheFallsBackToBuiltIn() throws Exception {
        Files.writeString(dir.resolve("ip-cache.txt"), "0.0.0.0/0\n");
        IpList l = new IpList(dir, AddonLog.of(java.util.logging.Logger.getAnonymousLogger()));
        l.loadLocal();
        assertEquals("built-in", l.source());
        assertFalse(l.contains(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void stricterConfig() throws Exception {
        AddonConfig c = AddonConfig.parse("ip-list-url: \"http://evil.example/list\"\nsignature-max-age-seconds: 86400\n");
        assertEquals("https://anycast.ac/ips/minecraft.txt", c.ipListUrl);
        assertEquals(600, c.signatureMaxAgeSeconds);
        assertTrue(c.allow.isEmpty(), "nothing skips the checks unless the owner says so");
        assertEquals(120, new AddonConfig().signatureMaxAgeSeconds);

        Files.writeString(dir.resolve("config.yml"), "allow: [0.0.0.0/0, \"::/0\", 10.0.0.0/8]\nip-list-url: \"http://127.0.0.1:1/none\"\n");
        List<String> out = addon.reload();
        assertEquals(2, out.stream().filter(x -> x.contains("too wide")).count(), out.toString());
        assertTrue(addon.trusted(InetAddress.getByName("10.9.9.9")));
        assertFalse(addon.trusted(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void cidrNeverAsksDns() {
        assertNull(Cidr.parse("1:2"));
        assertNull(Cidr.parse("abc:def"));
        assertNull(Cidr.parse("10.0.0.0/+8"));
        assertNull(Cidr.parse("10.0.0.0/"));
        assertNotNull(Cidr.parse("::ffff:1.2.3.4"));
    }

    @Test
    void replayMemorySweep() throws Exception {
        String s = sig("203.0.113.8", 3, now());
        Conn first = new Conn(addon, EDGE);
        first.send(handshake("mc.example.com///" + s, 2));
        assertTrue(first.ch.isActive());
        addon.sweepSeen(); // fresh signatures survive the sweep
        Conn again = new Conn(addon, EDGE);
        again.send(handshake("mc.example.com///" + s, 2));
        assertFalse(again.ch.isActive());
    }
}
