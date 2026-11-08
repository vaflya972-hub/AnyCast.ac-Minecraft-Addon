package ac.anycast.mc.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * First handler of every player connection (Paper, Velocity). Works on raw bytes
 * before the server's own decoders:
 * <ol>
 *   <li>on connect: closes connections that do not come from anycast.ac;</li>
 *   <li>on the first packet (handshake): checks the signature, restores the original
 *       server address and gives the server the real player address;</li>
 *   <li>removes itself, so the rest of the connection costs nothing.</li>
 * </ol>
 */
public final class HandshakeHandler extends ChannelInboundHandlerAdapter {
    public static final String NAME = "anycast-addon";
    private static final int MAX_FRAME = 64 * 1024;

    /** Puts the real player address into the platform's connection object. */
    public interface AddressSetter {
        void set(Channel ch, InetSocketAddress real) throws Exception;
    }

    private final Addon addon;
    private final AddressSetter setter;
    private ByteBuf buf;
    private boolean closed;

    public HandshakeHandler(Addon addon, AddressSetter setter) {
        this.addon = addon;
        this.setter = setter;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketAddress sa = ctx.channel().remoteAddress();
        // Let the server see the connection start first: it then cleans up normally on close.
        ctx.fireChannelActive();
        if (sa instanceof InetSocketAddress isa && !addon.acceptsConnection(isa.getAddress())) {
            closed = true;
            ctx.close();
        }
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
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        release();
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
        if (buf.getUnsignedByte(r) == 0xFE) {
            pass(ctx); // legacy ping (1.6 and older): no handshake to look at
            return;
        }
        long lenAt = varInt(buf, r, end);
        if (lenAt == NEED_MORE) {
            return;
        }
        if (lenAt == BAD) {
            pass(ctx);
            return;
        }
        int len = (int) (lenAt >>> 32);
        int start = (int) lenAt;
        if (len <= 0 || len > MAX_FRAME) {
            pass(ctx);
            return;
        }
        if (end - start < len) {
            return;
        }
        Handshake hs = Handshake.parse(buf, start, start + len);
        if (hs == null) {
            pass(ctx);
            return;
        }
        SocketAddress sa = ctx.channel().remoteAddress();
        Addon.Decision d = addon.decide(sa instanceof InetSocketAddress isa ? isa : null, hs.host, hs.intent);
        switch (d.action()) {
            case PASS -> pass(ctx);
            case DROP -> {
                closed = true;
                release();
                ctx.close();
            }
            case KICK -> {
                closed = true;
                release();
                ByteBuf out = ctx.alloc().buffer();
                writeKick(out, d.kick());
                ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
            }
            case REWRITE -> {
                if (d.address() != null) {
                    try {
                        setter.set(ctx.channel(), d.address());
                    } catch (Exception | LinkageError e) {
                        addon.log().warn("Cannot set the player address on this server version: " + e);
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
