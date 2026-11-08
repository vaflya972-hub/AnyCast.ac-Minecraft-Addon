package ac.anycast.mc.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/** Small reflection helpers for the platform hooks. */
public final class Reflect {
    private static final ConcurrentHashMap<Class<?>, Field> ADDRESS = new ConcurrentHashMap<>();

    private Reflect() {}

    /** Field by name in the class or its parents; null when absent. */
    public static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // parent
            }
        }
        return null;
    }

    /**
     * The connection's player address field: named {@code preferred} when present,
     * otherwise the only instance field typed exactly {@link SocketAddress}
     * (names differ between mappings), skipping {@code skip}.
     */
    public static Field addressField(Class<?> c, String preferred, String skip) {
        return ADDRESS.computeIfAbsent(c, k -> {
            Field f = field(k, preferred);
            if (f != null && f.getType() == SocketAddress.class) {
                return f;
            }
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                for (Field g : x.getDeclaredFields()) {
                    if (g.getType() == SocketAddress.class && !Modifier.isStatic(g.getModifiers()) && !g.getName().equals(skip)) {
                        g.setAccessible(true);
                        return g;
                    }
                }
            }
            throw new IllegalStateException("no SocketAddress field in " + k.getName());
        });
    }
}
