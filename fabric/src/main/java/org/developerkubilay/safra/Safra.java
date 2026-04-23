package org.developerkubilay.safra;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.developerkubilay.safra.server.DedicatedP2pServerManager;

public class Safra implements ModInitializer {

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(DedicatedP2pServerManager::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(DedicatedP2pServerManager::serverStopping);
    }
}
