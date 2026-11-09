package ac.anycast.mc.bungee;

import ac.anycast.mc.core.Addon;
import ac.anycast.mc.core.AddonLog;
import ac.anycast.mc.core.Reflect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.PlayerHandshakeEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import net.md_5.bungee.protocol.packet.Handshake;

/**
 * BungeeCord and Waterfall. Direct connections are closed in ClientConnectEvent
 * (before BungeeCord reads anything); the signature is handled in PlayerHandshakeEvent.
 */
public final class BungeeAddon extends Plugin implements Listener {
    private Addon addon;
    private boolean connectGate;
    /** Connections whose handshake carried a valid signature (the edge pings with its own address too). */
    private final Map<PendingConnection, Boolean> signed = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onEnable() {
        addon = new Addon(getDataFolder().toPath(), AddonLog.of(getLogger()), "BungeeCord");
        try {
            addon.start();
        } catch (Exception e) {
            getLogger().severe("Cannot start, players will NOT be checked: " + e);
            return;
        }
        getProxy().getPluginManager().registerListener(this, this);
        try {
            Class.forName("net.md_5.bungee.api.event.ClientConnectEvent");
            getProxy().getPluginManager().registerListener(this, new ConnectGate(addon));
            connectGate = true;
        } catch (ClassNotFoundException e) {
            getLogger().warning("This BungeeCord has no ClientConnectEvent: direct connections are closed at the handshake instead.");
        }
        try {
            if (getProxy().getConfig().getThrottle() > 0) {
                getLogger().warning("connection_throttle is on in config.yml of BungeeCord. All players come from a few anycast.ac addresses, "
                        + "so set connection_throttle: -1 (anycast.ac limits connections per player address itself).");
            }
        } catch (Throwable ignored) {
            // older API
        }
        boolean query = false;
        try {
            for (net.md_5.bungee.api.config.ListenerInfo l : getProxy().getConfig().getListeners()) {
                query |= l.isQueryEnabled();
            }
        } catch (Throwable ignored) {
            // older API
        }
        addon.warnExposure(query, getProxy().getPluginManager().getPlugin("Geyser-BungeeCord") != null);
        if (addon.versionBlocksStart()) {
            getProxy().stop("AnyCast.ac Minecraft Addon: critical update required, see the console");
            return;
        }
        getProxy().getPluginManager().registerCommand(this, new Command("anycast", "anycast.admin") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                List<String> lines;
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    try {
                        lines = new ArrayList<>(addon.reload());
                        lines.addAll(addon.status());
                    } catch (Exception e) {
                        lines = List.of("Reload failed: " + e.getMessage());
                    }
                } else {
                    lines = addon.status();
                }
                for (String l : lines) {
                    sender.sendMessage(new TextComponent(l));
                }
            }
        });
    }

    @Override
    public void onDisable() {
        if (addon != null) {
            addon.stop();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHandshake(PlayerHandshakeEvent e) {
        PendingConnection conn = e.getConnection();
        Handshake hs = e.getHandshake();
        SocketAddress sa = conn.getSocketAddress();
        InetSocketAddress remote = sa instanceof InetSocketAddress isa ? isa : null;
        if (!connectGate && remote != null && !addon.acceptsConnection(remote.getAddress())) {
            close(conn);
            return;
        }
        // BungeeCord has already moved everything after "\0" (Forge marker, or forwarding
        // data a player made up) into InitialHandler.extraDataInHandshake. With ip_forward
        // off it hands that to the backend as is, so it is checked together with the host.
        Field extraField = Reflect.field(conn.getClass(), "extraDataInHandshake");
        String extra = "";
        try {
            if (extraField != null && extraField.get(conn) instanceof String x) {
                extra = x;
            }
        } catch (IllegalAccessException ignored) {
            // field present but closed: the host alone is checked
        }
        int intent = hs.getRequestedProtocol();
        Addon.Decision d = addon.decide(remote, hs.getHost() + extra, intent);
        switch (d.action()) {
            case PASS -> {
            }
            // BungeeCord cannot send a kick before the login state: close quietly, the log says why.
            case DROP, KICK -> close(conn);
            case REWRITE -> {
                String full = d.host();
                int z = full.indexOf('\0');
                String host = z < 0 ? full : full.substring(0, z);
                if (host.endsWith(".")) {
                    host = host.substring(0, host.length() - 1);
                }
                hs.setHost(host);
                try {
                    if (extraField != null) {
                        extraField.set(conn, z < 0 ? "" : full.substring(z));
                    }
                    Field vh = Reflect.field(conn.getClass(), "virtualHost");
                    if (vh != null) {
                        vh.set(conn, InetSocketAddress.createUnresolved(host, hs.getPort()));
                    }
                    if (d.address() != null) {
                        signed.put(conn, Boolean.TRUE);
                        setAddress(conn, d.address());
                    }
                } catch (Exception | LinkageError ex) {
                    addon.warnLimited("setter", "Cannot set the player address on this BungeeCord version: " + ex
                            + ". Logins are refused until the addon is updated, otherwise bans by IP would not work.");
                    if (intent != 1 && d.address() != null) {
                        close(conn);
                    }
                }
            }
        }
    }

    /**
     * Pings that never reach PlayerHandshakeEvent: pre-1.7 (0xFE) pings, and on builds
     * without ClientConnectEvent direct ones too. From anycast.ac, with a key in config.yml,
     * only a ping whose handshake was signed gets an answer.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPing(ProxyPingEvent e) {
        PendingConnection conn = e.getConnection();
        SocketAddress sa = conn == null ? null : conn.getSocketAddress();
        if (!(sa instanceof InetSocketAddress isa) || isa.getAddress() == null) {
            return;
        }
        java.net.InetAddress a = isa.getAddress();
        if (addon.trusted(a) || signed.containsKey(conn)) {
            return;
        }
        if ((addon.edge(a) && addon.signing()) || (!connectGate && !addon.acceptsConnection(a))) {
            close(conn); // the answer is then written to a closed channel and never leaves
        }
    }

    /** InitialHandler.ch is a ChannelWrapper; HAProxy support sets the address the same way. */
    private static void setAddress(PendingConnection conn, InetSocketAddress real) throws Exception {
        Field chField = Reflect.field(conn.getClass(), "ch");
        if (chField == null) {
            throw new NoSuchFieldException("InitialHandler.ch");
        }
        Object ch = chField.get(conn);
        try {
            Method m = ch.getClass().getMethod("setRemoteAddress", SocketAddress.class);
            m.invoke(ch, real);
        } catch (NoSuchMethodException e) {
            Reflect.addressField(ch.getClass(), "remoteAddress", "").set(ch, real);
        }
    }

    private static void close(PendingConnection conn) {
        try {
            Field chField = Reflect.field(conn.getClass(), "ch");
            Object ch = chField.get(conn);
            ch.getClass().getMethod("close").invoke(ch);
        } catch (Exception e) {
            conn.disconnect(new TextComponent(""));
        }
    }
}
