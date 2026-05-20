package org.developerkubilay.safra.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.api.ClientModInitializer;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pQuicBootstrap;

public class SafraClient implements ClientModInitializer {

    static {
        System.setProperty("io.netty.transport.noNative", "true");
    }

    @Override
    public void onInitializeClient() {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
        P2pQuicBootstrap.downloadNativeAsync();
        ClientTickEvents.END_CLIENT_TICK.register(P2pManager.getInstance()::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> P2pManager.getInstance().shutdown());
    }
}
