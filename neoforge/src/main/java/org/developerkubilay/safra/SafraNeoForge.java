package org.developerkubilay.safra;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.developerkubilay.safra.server.DedicatedP2pServerManager;

@Mod(SafraNeoForge.MOD_ID)
public final class SafraNeoForge {
    public static final String MOD_ID = "safra";

    public SafraNeoForge(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(DedicatedP2pServerManager::serverStarted);
        NeoForge.EVENT_BUS.addListener(DedicatedP2pServerManager::serverStopping);

        if (FMLEnvironment.dist.isClient()) {
            try {
                Class.forName("org.developerkubilay.safra.client.SafraNeoForgeClientEvents")
                    .getMethod("register")
                    .invoke(null);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to initialize NeoForge client events", exception);
            }
        }
    }
}
