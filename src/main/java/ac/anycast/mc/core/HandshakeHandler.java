package ac.anycast.mc.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * First handler of every player connection (Paper, Velocity). Works on raw bytes
 * before the server's own decoders:
 * <ol>
 *   <li>on connect: closes connections that do not come from anycast.ac;</li>
 *   <li>on the first packet (handshake): checks the signature, restores the original
 *       server address and gives the server the real player address;</li>
 *   <li>removes itself, so the rest of the connection costs nothing.</li>
 * </ol>
 * Anything it cannot read is closed, not passed on: a check that steps aside on odd
 * input is a check that can be walked around. The only exceptions are pre-1.7 pings,
 * a PROXY protocol header from anycast.ac, and addresses from the allow list.
 */
public final class HandshakeHandler extends ChannelInboundHandlerAdapter {
    public static final String NAME = "anycast-addon";
    /** A handshake through anycast.ac is about 330 bytes, a vanilla client's at most about 780. */
    static final int MAX_FRAME = 2048;
    /** A proxy from the allow list may send BungeeCord forwarding with skin data. */
    static final int MAX_FRAME_TRUSTED = 64 * 1024;
    /** Clients and the edge send the handshake right away. */
    static final long HANDSHAKE_TIMEOUT_MS = 10_000;
    /** A 1.6 ping with its host is well under this. */
    static final int MAX_LEGACY = 512;
    static final byte[] PROXY_V1 = "PROXY ".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PROXY_V2 = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    /** Puts the real player address into the platform's connection object. */
    public interface AddressSetter {
        void set(Channel ch, InetSocketAddress real) throws Exception;
    }

    private final Addon addon;
    private final AddressSetter setter;
    private ByteBuf buf;
    private boolean closed;
    private boolean trusted;
    private boolean edge;
    /** Pre-1.7 ping forwarded: bytes so far (0 = not a legacy ping). */
    private int legacy;
    private boolean legacy16;
    private ScheduledFuture<?> timeout;

    public HandshakeHandler(Addon addon, AddressSetter setter) {
        this.addon = addon;
        this.setter = setter;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketAddress sa = ctx.channel().remoteAddress();
        InetAddress a = sa instanceof InetSocketAddress isa ? isa.getAddress() : null;
        trusted = addon.trusted(a);
        edge = addon.edge(a);
        // Let the server see the connection start first: it then cleans up normally on close.
        ctx.fireChannelActive();
        if (a != null && !addon.acceptsConnection(a)) {
            closed = true;
            ctx.close();
            return;
        }
        timeout = ctx.executor().schedule(() -> {
            if (!closed && legacy == 0 && ctx.channel().isActive()) {
                drop(ctx);
            }
        }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (closed) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (!(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (legacy > 0) {
            // After a pre-1.7 ping only the rest of that ping may follow, never a new handshake.
            legacy += in.readableBytes();
            if (legacy16 && legacy <= MAX_LEGACY) {
                ctx.fireChannelRead(in);
            } else {
                in.release();
                drop(ctx);
            }
            return;
        }
        if (buf == null) {
            buf = ctx.alloc().buffer(Math.max(256, in.readableBytes()));
        }
        buf.writeBytes(in);
        in.release();
        handle(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        release();
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        release();
        if (timeout != null) {
            timeout.cancel(false);
        }
        super.channelInactive(ctx);
    }

    private void release() {
        if (buf != null) {
            buf.release();
            buf = null;
        }
    }

    private void handle(ChannelHandlerContext ctx) {
        int r = buf.readerIndex();
        int end = buf.writerIndex();
        if (end - r < 1) {
            return;
        }
        // PROXY protocol header: the server has proxy protocol on ("Player address: PROXY
        // protocol" in the panel). Only anycast.ac and the allow list may send one, otherwise
        // anybody could name any address.
        int px = proxyHeader(buf, r, end);
        if (px == MORE) {
            return;
        }
        if (px == YES) {
            if (edge || trusted) {
                pass(ctx);
            } else {
                addon.warnLimited("proxyheader", "Closed a direct connection that sent a PROXY protocol header: only anycast.ac may send one.");
                drop(ctx);
            }
            return;
        }
        if (buf.getUnsignedByte(r) == 0xFE) {
            int lg = legacyPing(buf, r, end);
            if (lg == MORE) {
                return;
            }
            if (lg == YES) {
                legacy = end - r;
                legacy16 = end - r >= 3;
                ByteBuf b = buf;
                buf = null;
                ctx.fireChannelRead(b); // the handler stays and closes anything after the ping
                return;
            }
            // A modern frame whose length starts with 0xFE (254, 382, ... bytes): Paper reads
            // it as a normal handshake, so it is checked like any other.
        }
        long lenAt = varInt(buf, r, end);
        if (lenAt == NEED_MORE) {
            return;
        }
        if (lenAt == BAD) {
            reject(ctx);
            return;
        }
        int len = (int) (lenAt >>> 32);
        int start = (int) lenAt;
        if (len <= 0 || len > (trusted ? MAX_FRAME_TRUSTED : MAX_FRAME)) {
            reject(ctx);
            return;
        }
        if (end - start < len) {
            return;
        }
        Handshake hs = Handshake.parse(buf, start, start + len);
        if (hs == null) {
            reject(ctx);
            return;
        }
        SocketAddress sa = ctx.channel().remoteAddress();
        Addon.Decision d = addon.decide(sa instanceof InetSocketAddress isa ? isa : null, hs.host, hs.intent);
        switch (d.action()) {
            case PASS -> pass(ctx);
            case DROP -> drop(ctx);
            case KICK -> kick(ctx, d.kick());
            case REWRITE -> {
                if (d.address() != null) {
                    try {
                        setter.set(ctx.channel(), d.address());
                    } catch (Exception | LinkageError e) {
                        addon.warnLimited("setter", "Cannot set the player address on this server version: " + e
                                + ". Logins are refused until the addon is updated, otherwise bans by IP would not work.");
                        if (hs.intent != 1) {
                            kick(ctx, Addon.KICK_NO_ADDRESS);
                            return;
                        }
                    }
                }
                ByteBuf out = ctx.alloc().buffer(len + 16 + end - start - len);
                hs.write(out, d.host());
                out.writeBytes(buf, start + len, end - start - len);
                release();
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(out);
            }
        }
    }

    /** Hands everything received so far to the server unchanged and steps aside. */
    private void pass(ChannelHandlerContext ctx) {
        ByteBuf b = buf;
        buf = null;
        ctx.pipeline().remove(this);
        if (b != null) {
            ctx.fireChannelRead(b);
        }
    }

    /** Unreadable first packet: closed, except from the allow list (the owner's own tools). */
    private void reject(ChannelHandlerContext ctx) {
        if (trusted) {
            pass(ctx);
            return;
        }
        if (addon.debug()) {
            addon.log().info("Closed a connection with an unreadable handshake from " + ctx.channel().remoteAddress());
        }
        drop(ctx);
    }

    private void drop(ChannelHandlerContext ctx) {
        closed = true;
        release();
        ctx.close();
    }

    private void kick(ChannelHandlerContext ctx, String text) {
        closed = true;
        release();
        ByteBuf out = ctx.alloc().buffer();
        writeKick(out, text);
        ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
    }

    static final int NO = 0;
    static final int YES = 1;
    static final int MORE = 2;

    /** Does the data start with a PROXY protocol v1 or v2 header? MORE while it could still be one. */
    static int proxyHeader(ByteBuf b, int r, int end) {
        int a = prefix(b, r, end, PROXY_V1);
        if (a != NO) {
            return a;
        }
        return prefix(b, r, end, PROXY_V2);
    }

    private static int prefix(ByteBuf b, int r, int end, byte[] sig) {
        int n = Math.min(end - r, sig.length);
        for (int i = 0; i < n; i++) {
            if (b.getByte(r + i) != sig[i]) {
                return NO;
            }
        }
        return n == sig.length ? YES : MORE;
    }

    /**
     * Pre-1.7 ping: "FE" (1.3), "FE 01" (1.4, 1.5) or "FE 01 FA ..." (1.6). "FE 01 00 ..."
     * and "FE 02 ..." are modern frames of 254 and 382+ bytes.
     */
    static int legacyPing(ByteBuf b, int r, int end) {
        int n = end - r;
        if (n == 1) {
            return YES;
        }
        if (b.getUnsignedByte(r + 1) != 0x01) {
            return NO;
        }
        if (n == 2) {
            return YES;
        }
        return b.getUnsignedByte(r + 2) == 0xFA ? YES : NO;
    }

    static final long NEED_MORE = -1;
    static final long BAD = -2;

    /** VarInt at {@code i}: (value << 32) | index after it, or NEED_MORE / BAD. */
    static long varInt(ByteBuf b, int i, int end) {
        int v = 0;
        for (int n = 0; n < 5; n++) {
            if (i >= end) {
                return NEED_MORE;
            }
            int x = b.getUnsignedByte(i++);
            v |= (x & 0x7F) << (7 * n);
            if ((x & 0x80) == 0) {
                return ((long) v << 32) | (i & 0xFFFFFFFFL);
            }
        }
        return BAD;
    }

    static void writeVarInt(ByteBuf b, int v) {
        while ((v & ~0x7F) != 0) {
            b.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        b.writeByte(v);
    }

    /** Login Disconnect (state LOGIN, id 0x00, JSON text) as a whole frame. */
    static void writeKick(ByteBuf out, String text) {
        byte[] js = ("{\"text\":\"" + jsonEscape(text) + "\"}").getBytes(StandardCharsets.UTF_8);
        ByteBuf body = out.alloc().buffer(js.length + 8);
        try {
            writeVarInt(body, 0);
            writeVarInt(body, js.length);
            body.writeBytes(js);
            writeVarInt(out, body.readableBytes());
            out.writeBytes(body);
        } finally {
            body.release();
        }
    }

    static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    /** Handshake packet fields. */
    static final class Handshake {
        int proto;
        String host;
        int port;
        int intent;

        static Handshake parse(ByteBuf b, int i, int end) {
            long x = varInt(b, i, end);
            if (x < 0 || (x >>> 32) != 0) {
                return null; // not packet 0x00
            }
            i = (int) x;
            Handshake h = new Handshake();
            if ((x = varInt(b, i, end)) < 0) {
                return null;
            }
            h.proto = (int) (x >>> 32);
            i = (int) x;
            if ((x = varInt(b, i, end)) < 0) {
                return null;
            }
            int n = (int) (x >>> 32);
            i = (int) x;
            if (n < 0 || n > end - i) {
                return null;
            }
            h.host = b.toString(i, n, StandardCharsets.UTF_8);
            i += n;
            if (end - i < 3) {
                return null;
            }
            h.port = b.getUnsignedShort(i);
            i += 2;
            if ((x = varInt(b, i, end)) < 0 || (int) x != end) {
                return null;
            }
            h.intent = (int) (x >>> 32);
            return h;
        }

        void write(ByteBuf out, String newHost) {
            byte[] hb = newHost.getBytes(StandardCharsets.UTF_8);
            ByteBuf body = out.alloc().buffer(hb.length + 16);
            try {
                writeVarInt(body, 0);
                writeVarInt(body, proto);
                writeVarInt(body, hb.length);
                body.writeBytes(hb);
                body.writeShort(port);
                writeVarInt(body, intent);
                writeVarInt(out, body.readableBytes());
                out.writeBytes(body);
            } finally {
                body.release();
            }
        }
    }
}
