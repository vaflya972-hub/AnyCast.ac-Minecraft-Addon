package ac.anycast.mc.bungee;

import ac.anycast.mc.core.Addon;
import java.net.InetSocketAddress;
import net.md_5.bungee.api.event.ClientConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/** Closes direct connections before BungeeCord reads a byte. Separate class: old builds lack the event. */
public final class ConnectGate implements Listener {
    private final Addon addon;

    public ConnectGate(Addon addon) {
        this.addon = addon;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConnect(ClientConnectEvent e) {
        if (e.getSocketAddress() instanceof InetSocketAddress a && !addon.acceptsConnection(a.getAddress())) {
            e.setCancelled(true);
        }
    }
}
