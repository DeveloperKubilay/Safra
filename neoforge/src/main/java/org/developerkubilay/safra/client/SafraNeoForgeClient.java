package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.developerkubilay.safra.SafraNeoForge;
import org.developerkubilay.safra.client.p2p.P2pManager;

@Mod.EventBusSubscriber(modid = SafraNeoForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SafraNeoForgeClient {
    private SafraNeoForgeClient() {
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
