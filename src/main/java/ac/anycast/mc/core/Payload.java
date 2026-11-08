package ac.anycast.mc.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed player address that the anycast.ac edge appends to the server address
 * of a login handshake. See PROTOCOL.md.
 *
 * <pre>
 * host field = name "///" base64url(payload) [tail starting with \0, e.g. Forge marker]
 * payload    = ver(1)=1 | flags(1) bit0=IPv6 | addr(4|16) | port(2) | time(4) | mac(16)
 * mac        = HMAC-SHA256(key, "anycast-mc/1" | payload without mac)[0..16)
 * </pre>
 */
public final class Payload {
    public static final String SEPARATOR = "///";
    static final int VERSION = 1;
    static final int MAC_LEN = 16;
    public static final int KEY_LEN = 32;
    private static final byte[] DOMAIN = "anycast-mc/1".getBytes(StandardCharsets.US_ASCII);

    private Payload() {}

    /** Handshake address split into the original part and the signature. */
    public record Split(String cleaned, String signature) {}

    /** Verified signature contents. */
    public record Signed(InetSocketAddress address, long unixTime, String id) {}

    /** Why a signature was not accepted. */
    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** Splits "name///sig[\0tail]" into "name[\0tail]" and "sig"; null when there is no signature. */
    public static Split split(String host) {
        int i = host.indexOf(SEPARATOR);
        if (i < 0) {
            return null;
        }
        String rest = host.substring(i + SEPARATOR.length());
        int z = rest.indexOf('\0');
        String sig = z < 0 ? rest : rest.substring(0, z);
        String tail = z < 0 ? "" : rest.substring(z);
        return new Split(host.substring(0, i) + tail, sig);
    }

    /** Decodes a base64url key from config.yml; null when it is not 32 bytes. */
    public static byte[] parseKey(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            byte[] k = Base64.getUrlDecoder().decode(s.trim());
            return k.length == KEY_LEN ? k : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Checks the version, length and MAC. Freshness and replay are checked by the caller. */
    public static Signed verify(byte[] key, String signature) throws Invalid {
        if (signature.length() > 64) {
            throw new Invalid("signature too long");
        }
        byte[] p;
        try {
            p = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            throw new Invalid("signature is not base64url");
        }
        if (p.length < 2 || p[0] != VERSION) {
            throw new Invalid("unknown signature version");
        }
        boolean v6 = (p[1] & 1) != 0;
        int addrLen = v6 ? 16 : 4;
        int body = 2 + addrLen + 2 + 4;
        if (p.length != body + MAC_LEN) {
            throw new Invalid("bad signature length");
        }
        byte[] mac = mac(key, p, body);
        if (!MessageDigest.isEqual(mac, Arrays.copyOfRange(p, body, body + MAC_LEN))) {
            throw new Invalid("signature does not match the key");
        }
        InetAddress ip;
        try {
            ip = InetAddress.getByAddress(Arrays.copyOfRange(p, 2, 2 + addrLen));
        } catch (UnknownHostException e) {
            throw new Invalid("bad address");
        }
        int port = ((p[2 + addrLen] & 0xFF) << 8) | (p[3 + addrLen] & 0xFF);
        int o = 4 + addrLen;
        long time = ((long) (p[o] & 0xFF) << 24) | ((p[o + 1] & 0xFF) << 16) | ((p[o + 2] & 0xFF) << 8) | (p[o + 3] & 0xFF);
        return new Signed(new InetSocketAddress(ip, port), time, Base64.getUrlEncoder().withoutPadding().encodeToString(mac));
    }

    /** Builds a signature (used by tests; the edge does the same in Go). */
    public static String sign(byte[] key, InetSocketAddress client, long unixTime) {
        byte[] a = client.getAddress().getAddress();
        byte[] p = new byte[2 + a.length + 2 + 4 + MAC_LEN];
        p[0] = VERSION;
        p[1] = (byte) (a.length == 16 ? 1 : 0);
        System.arraycopy(a, 0, p, 2, a.length);
        int o = 2 + a.length;
        p[o] = (byte) (client.getPort() >> 8);
        p[o + 1] = (byte) client.getPort();
        p[o + 2] = (byte) (unixTime >> 24);
        p[o + 3] = (byte) (unixTime >> 16);
        p[o + 4] = (byte) (unixTime >> 8);
        p[o + 5] = (byte) unixTime;
        int body = o + 6;
        System.arraycopy(mac(key, p, body), 0, p, body, MAC_LEN);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(p);
    }

    private static byte[] mac(byte[] key, byte[] p, int len) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            m.update(DOMAIN);
            m.update(p, 0, len);
            return Arrays.copyOf(m.doFinal(), MAC_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
