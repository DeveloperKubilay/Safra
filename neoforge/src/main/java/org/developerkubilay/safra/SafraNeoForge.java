package org.developerkubilay.safra;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.developerkubilay.safra.server.DedicatedP2pServerManager;

@Mod(SafraNeoForge.MOD_ID)
public final class SafraNeoForge {
    public static final String MOD_ID = "safra";

    public SafraNeoForge(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(DedicatedP2pServerManager::serverStarted);
        NeoForge.EVENT_BUS.addListener(DedicatedP2pServerManager::serverStopping);
    }
}
