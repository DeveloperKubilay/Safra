package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.developerkubilay.safra.SafraNeoForge;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pQuicBootstrap;

@Mod(value = SafraNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class SafraNeoForgeClient {
    public SafraNeoForgeClient() {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
        P2pQuicBootstrap.downloadNativeAsync();
        NeoForge.EVENT_BUS.addListener(SafraNeoForgeClient::clientTick);
        NeoForge.EVENT_BUS.addListener(SafraNeoForgeClient::clientStopping);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        P2pManager.getInstance().tick(Minecraft.getInstance());
    }

    private static void clientStopping(GameShuttingDownEvent event) {
        P2pManager.getInstance().shutdown();
    }
}
