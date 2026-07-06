package org.developerkubilay.safra;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.server.ServerStartCallback;
import net.fabricmc.fabric.api.event.server.ServerStopCallback;
import org.developerkubilay.safra.server.DedicatedP2pServerManager;

public class Safra implements ModInitializer {

    @Override
    public void onInitialize() {
        ServerStartCallback.EVENT.register(DedicatedP2pServerManager::serverStarted);
        ServerStopCallback.EVENT.register(DedicatedP2pServerManager::serverStopping);
    }
}
