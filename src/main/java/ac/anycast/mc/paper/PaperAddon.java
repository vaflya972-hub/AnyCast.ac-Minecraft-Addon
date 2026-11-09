package ac.anycast.mc.paper;

import ac.anycast.mc.core.Addon;
import ac.anycast.mc.core.AddonLog;
import ac.anycast.mc.core.HandshakeHandler;
import ac.anycast.mc.core.Reflect;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper and its forks (Purpur, Pufferfish, Folia). Hooks player connections through
 * Paper's ChannelInitializeListenerHolder, so the handler sits in front of the
 * server's own decoders. Plain Spigot/CraftBukkit has no such hook and is not supported.
 */
public final class PaperAddon extends JavaPlugin {
    private static final Key KEY = Key.key("anycast", "addon");
    private static final String HOLDER = "io.papermc.paper.network.ChannelInitializeListenerHolder";
    private static final String LISTENER = "io.papermc.paper.network.ChannelInitializeListener";

    private Addon addon;
    private boolean hooked;

    @Override
    public void onEnable() {
        addon = new Addon(getDataFolder().toPath(), AddonLog.of(getLogger()), "Paper");
        try {
            addon.start();
            hook();
            warnBungeeMode();
        } catch (ClassNotFoundException e) {
            getLogger().severe("This server is not Paper. The addon needs Paper or a Paper fork (Purpur, Pufferfish, Folia).");
            getServer().getPluginManager().disablePlugin(this);
        } catch (Exception e) {
            getLogger().severe("Cannot start: " + e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (hooked) {
            try {
                Class.forName(HOLDER).getMethod("removeListener", Key.class).invoke(null, KEY);
            } catch (ReflectiveOperationException e) {
                getLogger().warning("Cannot remove the connection hook: " + e);
            }
            hooked = false;
        }
        if (addon != null) {
            addon.stop();
        }
    }

    private void hook() throws ReflectiveOperationException {
        Class<?> holder = Class.forName(HOLDER);
        Class<?> listener = Class.forName(LISTENER);
        Addon a = addon;
        Object proxy = Proxy.newProxyInstance(getClassLoader(), new Class<?>[] {listener}, (p, m, args) -> switch (m.getName()) {
            case "afterInitChannel" -> {
                Channel ch = (Channel) args[0];
                ch.pipeline().addFirst(HandshakeHandler.NAME, new HandshakeHandler(a, PaperAddon::setAddress));
                yield null;
            }
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            case "toString" -> "AnyCast.ac connection hook";
            default -> null;
        });
        Method add = holder.getMethod("addListener", Key.class, listener);
        add.invoke(null, KEY, proxy);
        hooked = true;
    }

    /**
     * spigot.yml bungeecord: true makes Paper believe the address and UUID written into
     * the handshake. That is right behind your own BungeeCord, and a way to log in as
     * anybody when players can reach this server. The addon drops such data from players.
     */
    private void warnBungeeMode() {
        try {
            if (getServer().spigot().getConfig().getBoolean("settings.bungeecord")) {
                getLogger().warning("spigot.yml has bungeecord: true. Point anycast.ac at your BungeeCord or Velocity proxy, not at this server: "
                        + "this server takes player addresses and UUIDs from the handshake. The addon drops that data from players, "
                        + "so players coming straight from anycast.ac cannot join here.");
            }
        } catch (Throwable ignored) {
            // not Spigot-based, nothing to check
        }
    }

    /** net.minecraft.network.Connection is the "packet_handler" of the pipeline. */
    public static void setAddress(Channel ch, InetSocketAddress real) throws Exception {
        Object conn = ch.pipeline().get("packet_handler");
        if (conn == null) {
            throw new IllegalStateException("no packet_handler in the pipeline");
        }
        Field hp = Reflect.field(conn.getClass(), "haProxyAddress");
        if (hp != null && hp.get(conn) == null) {
            hp.set(conn, ch.remoteAddress()); // Player#getHAProxyAddress() = the anycast.ac edge
        }
        Reflect.addressField(conn.getClass(), "address", "haProxyAddress").set(conn, real);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> lines;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            try {
                lines = addon.reload();
                lines.addAll(addon.status());
            } catch (Exception e) {
                lines = List.of("Reload failed: " + e.getMessage());
            }
        } else {
            lines = addon.status();
        }
        for (String l : lines) {
            sender.sendMessage(l);
        }
        return true;
    }
}
