package org.developerkubilay.safra.server;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.fmlserverevents.FMLServerStoppingEvent;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.p2p.CachedRendezvousConfigLoader;
import org.developerkubilay.safra.p2p.ConsoleShareCodePrinter;
import org.developerkubilay.safra.p2p.P2pHostService;
import org.developerkubilay.safra.p2p.P2pHostSupport;
import org.developerkubilay.safra.p2p.RemoteRendezvousBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;

public final class DedicatedP2pServerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Safra P2P");
    private static P2pHostService hostService;

    private DedicatedP2pServerManager() {
    }

    public static synchronized void serverStarted(FMLServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) {
            return;
        }

        stopHosting();
        CachedRendezvousConfigLoader.initialize(Paths.get("config", "safra-client.json"));
        RemoteRendezvousBootstrap.initializeDedicated();

        int tcpPort = server.getPort();
        SafraClientConfig config = SafraClientConfig.get();
        String fixedCode = config.isOpenToLanFixedCodeEnabled() ? config.ensureOpenToLanFixedCode() : null;
        try {
            P2pHostSupport.HostStartResult hostStartResult = P2pHostSupport.startDedicatedHost(tcpPort, server.getLocalIp(), fixedCode, LOGGER);
            hostService = hostStartResult.service();
            String shareCodeText = hostStartResult.shareCode().toDisplayCode();
            LOGGER.info("Safra P2P dedicated server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
            ConsoleShareCodePrinter.printDedicatedShareCodeIfSupported(shareCodeText);
            LOGGER.info("Players should use Direct Connect, enable P2P, and paste this code.");
        } catch (IOException exception) {
            LOGGER.warn("Safra P2P dedicated server could not start on local TCP port {}", tcpPort, exception);
        }
    }

    public static synchronized void serverStopping(FMLServerStoppingEvent event) {
        if (event.getServer().isDedicatedServer()) {
            stopHosting();
        }
    }

    private static void stopHosting() {
        if (hostService == null) {
            return;
        }

        LOGGER.info("Safra P2P dedicated server host stopping");
        hostService.close();
        hostService = null;
    }
}
