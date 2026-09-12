package org.developerkubilay.safra.server;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.developerkubilay.safra.SafraForge;

@Mod.EventBusSubscriber(modid = SafraForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SafraForgeServerEvents {
    private SafraForgeServerEvents() {
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
