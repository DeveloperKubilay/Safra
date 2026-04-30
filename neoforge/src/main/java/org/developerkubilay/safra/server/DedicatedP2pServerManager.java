package org.developerkubilay.safra.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.developerkubilay.safra.p2p.P2pHostService;
import org.developerkubilay.safra.p2p.P2pHostSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class DedicatedP2pServerManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Safra P2P");

    private static P2pHostService hostService;

    private DedicatedP2pServerManager() {
    }

    public static synchronized void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) {
            return;
        }

        stopHosting();

        int tcpPort = server.getPort();
        try {
            P2pHostSupport.HostStartResult hostStartResult = P2pHostSupport.startDedicatedHost(tcpPort, server.getLocalIp(), LOGGER);
            hostService = hostStartResult.service();
            LOGGER.info("Safra P2P dedicated server opened on local TCP port {}. Share code: {}", tcpPort, hostStartResult.shareCode().toDisplayCode());
            LOGGER.info("Players should use Direct Connect, enable P2P, and paste this code.");
        } catch (IOException exception) {
            LOGGER.warn("Safra P2P dedicated server could not start on local TCP port {}", tcpPort, exception);
        }
    }

    public static synchronized void serverStopping(ServerStoppingEvent event) {
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
