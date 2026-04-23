package org.developerkubilay.safra.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.api.ClientModInitializer;
import org.developerkubilay.safra.client.p2p.P2pManager;

public class SafraClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(P2pManager.getInstance()::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> P2pManager.getInstance().shutdown());
    }
}
