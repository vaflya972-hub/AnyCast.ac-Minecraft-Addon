package ac.anycast.mc.velocity;

import ac.anycast.mc.core.Addon;
import ac.anycast.mc.core.AddonLog;
import ac.anycast.mc.core.HandshakeHandler;
import ac.anycast.mc.core.Reflect;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Velocity 3. Wraps the proxy's server channel initializer (the same hook other
 * network plugins use), so the handler sits in front of Velocity's decoders.
 */
public final class VelocityAddon {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dir;
    private Addon addon;

    @Inject
    public VelocityAddon(ProxyServer proxy, Logger logger, @DataDirectory Path dir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        addon = new Addon(dir, new AddonLog() {
            @Override
            public void info(String msg) {
                logger.info(msg);
            }

            @Override
            public void warn(String msg) {
                logger.warn(msg);
            }
        }, "Velocity");
        try {
            addon.start();
            hook();
        } catch (Exception ex) {
            logger.error("Cannot start, players will NOT be checked: {}", ex.toString());
            return;
        }
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("anycast").plugin(this).build(), new SimpleCommand() {
            @Override
            public void execute(Invocation inv) {
                List<String> lines;
                String[] args = inv.arguments();
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    try {
                        lines = new ArrayList<>(addon.reload());
                        lines.addAll(addon.status());
                    } catch (Exception ex) {
                        lines = List.of("Reload failed: " + ex.getMessage());
                    }
                } else {
                    lines = addon.status();
                }
                for (String l : lines) {
                    inv.source().sendMessage(Component.text(l));
                }
            }

            @Override
            public boolean hasPermission(Invocation inv) {
                return inv.source().hasPermission("anycast.admin");
            }
        });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (addon != null) {
            addon.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private void hook() throws ReflectiveOperationException {
        Field cmField = Reflect.field(proxy.getClass(), "cm");
        if (cmField == null) {
            throw new NoSuchFieldException("VelocityServer.cm");
        }
        Object cm = cmField.get(proxy);
        Object holder = cm.getClass().getMethod("getServerChannelInitializer").invoke(cm);
        Method get = holder.getClass().getMethod("get");
        Method set = holder.getClass().getMethod("set", ChannelInitializer.class);
        ChannelInitializer<Channel> original = (ChannelInitializer<Channel>) get.invoke(holder);
        Method init = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
        init.setAccessible(true);
        Addon a = addon;
        set.invoke(holder, new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                init.invoke(original, ch);
                ch.pipeline().addFirst(HandshakeHandler.NAME, new HandshakeHandler(a, VelocityAddon::setAddress));
            }
        });
    }

    /** MinecraftConnection is the "handler" of the pipeline and keeps the address in remoteAddress. */
    public static void setAddress(Channel ch, InetSocketAddress real) throws Exception {
        Object conn = ch.pipeline().get("handler");
        if (conn == null) {
            throw new IllegalStateException("no handler in the pipeline");
        }
        Reflect.addressField(conn.getClass(), "remoteAddress", "").set(conn, real);
    }
}
