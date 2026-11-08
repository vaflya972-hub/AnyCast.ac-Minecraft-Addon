package ac.anycast.mc.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** IPv4/IPv6 network, "1.2.3.4", "1.2.3.0/24", "2a0d::/32". */
public final class Cidr {
    private final byte[] net;
    private final int bits;
    private final String text;

    private Cidr(byte[] net, int bits, String text) {
        this.net = net;
        this.bits = bits;
        this.text = text;
    }

    /** Parses one entry; null when it is not an address or network. Never resolves names. */
    public static Cidr parse(String s) {
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        String addr = s;
        int bits = -1;
        int slash = s.indexOf('/');
        if (slash >= 0) {
            addr = s.substring(0, slash);
            try {
                bits = Integer.parseInt(s.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        byte[] b = literal(addr);
        if (b == null) {
            return null;
        }
        int max = b.length * 8;
        if (bits < 0) {
            bits = max;
        }
        if (bits > max) {
            return null;
        }
        for (int i = 0; i < b.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - i * 8));
            b[i] &= (byte) (0xFF << (8 - keep));
        }
        return new Cidr(b, bits, s);
    }

    /** Parses a text list: one entry per line, "#" starts a comment. Bad lines are counted in {@code bad}. */
    public static List<Cidr> parseList(String body, int[] bad) {
        List<Cidr> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            int h = line.indexOf('#');
            if (h >= 0) {
                line = line.substring(0, h);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            Cidr c = parse(line);
            if (c == null) {
                if (bad != null) {
                    bad[0]++;
                }
            } else {
                out.add(c);
            }
        }
        return out;
    }

    public boolean contains(InetAddress a) {
        byte[] b = normalize(a.getAddress());
        if (b.length != net.length) {
            return false;
        }
        for (int i = 0; i < b.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - i * 8));
            if (keep == 0) {
                return true;
            }
            int mask = (0xFF << (8 - keep)) & 0xFF;
            if ((b[i] & mask) != (net[i] & mask)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return text;
    }

    /** IPv4-mapped IPv6 (::ffff:1.2.3.4) is compared as IPv4. */
    static byte[] normalize(byte[] b) {
        if (b.length == 16) {
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    return b;
                }
            }
            if (b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                return new byte[] {b[12], b[13], b[14], b[15]};
            }
        }
        return b;
    }

    /** Address literal without DNS: digits and dots for IPv4, hex and colons for IPv6. */
    private static byte[] literal(String s) {
        if (s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean v6 = s.indexOf(':') >= 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = v6 ? (Character.digit(c, 16) >= 0 || c == ':' || c == '.') : (c >= '0' && c <= '9') || c == '.';
            if (!ok) {
                return null;
            }
        }
        if (!v6) {
            String[] parts = s.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) {
                    return null;
                }
                int v = Integer.parseInt(parts[i]);
                if (v > 255) {
                    return null;
                }
                b[i] = (byte) v;
            }
            return b;
        }
        try {
            return normalize(InetAddress.getByName(s).getAddress());
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
