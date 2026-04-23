package org.developerkubilay.safra.server;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.developerkubilay.safra.p2p.P2pHostService;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

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
        int token = createShareToken();
        InetAddress targetAddress = resolveTargetAddress(server.getLocalIp());
        P2pHostService service = new P2pHostService(tcpPort, token, targetAddress);

        try {
            P2pShareCode shareCode = service.start();
            hostService = service;
            LOGGER.info("Safra P2P dedicated server opened on local TCP port {}. Share code: {}", tcpPort, shareCode.toDisplayCode());
            LOGGER.info("Players should use Direct Connect, enable P2P, and paste this code.");
        } catch (IOException exception) {
            service.close();
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

    private static InetAddress resolveTargetAddress(String serverIp) {
        if (serverIp == null || serverIp.isBlank() || "0.0.0.0".equals(serverIp) || "::".equals(serverIp)) {
            return InetAddress.getLoopbackAddress();
        }

        try {
            return InetAddress.getByName(serverIp);
        } catch (UnknownHostException exception) {
            LOGGER.warn("Safra P2P could not resolve server-ip '{}', falling back to loopback", serverIp, exception);
            return InetAddress.getLoopbackAddress();
        }
    }

    private static int createShareToken() {
        int token;
        do {
            token = ThreadLocalRandom.current().nextInt();
        } while (token == 0);
        return token;
    }
}
