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
        assertFalse(c.onlyAnycast);
        assertEquals(List.of("1.2.3.4", "::1"), c.allow);
        assertEquals(1, c.warnings.size());
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
            InetSocketAddress ra = new InetSocketAddress(ip, 40000);
            ch = new EmbeddedChannel(false, false) {
                @Override
                protected SocketAddress remoteAddress0() {
                    return ra;
                }
            };
            ch.pipeline().addLast(HandshakeHandler.NAME, new HandshakeHandler(addon, PaperAddon::setAddress));
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
        assertTrue(c.ch.isActive(), "only-anycast off: direct connections are allowed");
        c.send(handshake("mc.example.com///" + sig("1.1.1.1", 1, now()), 2));
        assertFalse(c.ch.isActive());
        Conn plain = new Conn(addon, "8.8.4.4");
        plain.send(handshake("mc.example.com", 2));
        assertTrue(plain.ch.isActive(), "plain direct login passes when only-anycast is off");
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
}
