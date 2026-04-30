package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.TickEvent;
import org.developerkubilay.safra.SafraNeoForge;
import org.developerkubilay.safra.client.p2p.P2pManager;

@Mod.EventBusSubscriber(modid = SafraNeoForge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SafraNeoForgeClientEvents {
    private SafraNeoForgeClientEvents() {
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        P2pManager.getInstance().tick(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void clientStopping(GameShuttingDownEvent event) {
        P2pManager.getInstance().shutdown();
    }
}
