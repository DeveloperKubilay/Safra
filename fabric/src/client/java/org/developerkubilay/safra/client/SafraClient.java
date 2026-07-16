package org.developerkubilay.safra.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.api.ClientModInitializer;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;

public class SafraClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
        ClientTickEvents.END_CLIENT_TICK.register(P2pManager.getInstance()::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> P2pManager.getInstance().shutdown());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> P2pManager.getInstance().shutdown());
    }
}
