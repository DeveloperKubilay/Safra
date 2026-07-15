package org.developerkubilay.safra;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import org.developerkubilay.safra.p2p.P2pOptionalIntegrations;
import org.developerkubilay.safra.server.DedicatedP2pServerManager;

@Mod(SafraNeoForge.MOD_ID)
public final class SafraNeoForge {
    public static final String MOD_ID = "safra";

    public SafraNeoForge(IEventBus modEventBus) {
        initializeGeyserIntegration();
        NeoForge.EVENT_BUS.addListener(DedicatedP2pServerManager::serverStarted);
        NeoForge.EVENT_BUS.addListener(DedicatedP2pServerManager::serverStopping);
    }

    private static void initializeGeyserIntegration() {
        if (!ModList.get().isLoaded("geyser_neoforge")) {
            return;
        }

        FMLLoader.getCurrent().getGameLayer().modules().stream()
            .filter(module -> module.getPackages().contains("org.geysermc.geyser.api"))
            .map(module -> Class.forName(module, "org.geysermc.geyser.api.GeyserApi"))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .ifPresent(P2pOptionalIntegrations::setGeyserApiClass);
    }
}
