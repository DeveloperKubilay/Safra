package org.developerkubilay.safra.server;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import org.developerkubilay.safra.SafraForge;

@Mod.EventBusSubscriber(modid = SafraForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SafraForgeServerEvents {
    private SafraForgeServerEvents() {
    }

    @SubscribeEvent
    public static void serverStarted(FMLServerStartedEvent event) {
        DedicatedP2pServerManager.serverStarted(event);
    }

    @SubscribeEvent
    public static void serverStopping(FMLServerStoppingEvent event) {
        DedicatedP2pServerManager.serverStopping(event);
    }
}
