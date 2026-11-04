package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.developerkubilay.safra.SafraForge;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.developerkubilay.safra.client.p2p.P2pManager;

@Mod.EventBusSubscriber(modid = SafraForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SafraForgeClientEvents {
    static {
        P2pErrorComponents.warmUp();
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> P2pManager.getInstance().shutdown(), "safra-p2p-shutdown"));
    }

    private SafraForgeClientEvents() {
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        P2pManager.getInstance().tick(Minecraft.getInstance());
    }
    @SubscribeEvent
    public static void clientLoggingOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && (client.world != null || client.getIntegratedServer() != null)) {
            P2pManager.getInstance().shutdown();
        }
    }
}
