package org.developerkubilay.safra.server;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.developerkubilay.safra.SafraNeoForge;

@Mod.EventBusSubscriber(modid = SafraNeoForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SafraNeoForgeServerEvents {
    private SafraNeoForgeServerEvents() {
    }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        DedicatedP2pServerManager.serverStarted(event);
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        DedicatedP2pServerManager.serverStopping(event);
    }
}
