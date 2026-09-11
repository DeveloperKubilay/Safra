package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;

public final class P2pHostSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pHostSupport.class);

    private P2pHostSupport() {
    }

    /**
     * A loader's transforming class loader reaches these for the first time when a world is opened to
     * LAN, and it took two and a half seconds to do it on a discovery that runs in twelve milliseconds
     * once the classes are in memory. That is the one moment a player is sitting there waiting for a
     * share code, so the loading happens here instead, where the game is starting and nobody is.
     */
    public static void warmUp() {
        P2pRuntime.start("safra-warmup", () -> {
            try (DatagramSocket socket = P2pSockets.datagramSocket()) {
                new P2pStunClient();
                P2pKwikCertificate.create();
                LOGGER.debug("Safra P2P classes ready on local port {}", socket.getLocalPort());
            } catch (Exception exception) {
                LOGGER.debug("Safra P2P warm-up did not finish: {}", exception.toString());
            }
        });
    }

    public static String resolvePreferredRendezvousCode(String preferredRendezvousCode) {
        String normalized = P2pShareCode.normalizeRendezvousCode(preferredRendezvousCode);
        return normalized != null ? normalized : P2pShareCode.createRendezvousCode();
    }

    public static int createRendezvousShareToken(String rendezvousCode) {
        return P2pShareCode.rendezvousTunnelToken(rendezvousCode);
    }

    public static HostStartResult startDedicatedHost(int tcpPort, String serverIp, String preferredRendezvousCode, Logger logger) throws IOException {
        String resolvedCode = resolvePreferredRendezvousCode(preferredRendezvousCode);
        P2pHostService service = new P2pHostService(
            tcpPort,
            createRendezvousShareToken(resolvedCode),
            resolveTargetAddress(serverIp, logger),
            resolvedCode,
            false
        );
        try {
            return new HostStartResult(service, service.start());
        } catch (IOException exception) {
            service.close();
            throw exception;
        }
    }

    private static InetAddress resolveTargetAddress(String serverIp, Logger logger) {
        if (serverIp == null || serverIp.isBlank() || "0.0.0.0".equals(serverIp) || "::".equals(serverIp)) {
            return InetAddress.getLoopbackAddress();
        }

        try {
            return InetAddress.getByName(serverIp);
        } catch (UnknownHostException exception) {
            logger.warn("Safra P2P could not resolve server-ip '{}', falling back to loopback", serverIp, exception);
            return InetAddress.getLoopbackAddress();
        }
    }

    public record HostStartResult(P2pHostService service, P2pShareCode shareCode) {
    }
}
