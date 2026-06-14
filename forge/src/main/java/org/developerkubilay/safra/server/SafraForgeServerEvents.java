package org.developerkubilay.safra.server;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fmlserverevents.FMLServerAboutToStartEvent;
import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import org.developerkubilay.safra.SafraForge;

import java.nio.file.Paths;

@Mod.EventBusSubscriber(modid = SafraForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SafraForgeServerEvents {
    private SafraForgeServerEvents() {
    }

    @SubscribeEvent
    public static void serverAboutToStart(FMLServerAboutToStartEvent event) {
        if (event.getServer().isDedicatedServer()) {
            DedicatedServerPropertyFixer.ensureOfflineSafeDefaults(Paths.get("server.properties"));
        }
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
