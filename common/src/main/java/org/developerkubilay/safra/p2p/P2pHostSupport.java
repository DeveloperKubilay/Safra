package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

public final class P2pHostSupport {
    private P2pHostSupport() {
    }

    public static int createShareToken() {
        int token;
        do {
            token = ThreadLocalRandom.current().nextInt();
        } while (token == 0);
        return token;
    }

    public static HostStartResult startDedicatedHost(int tcpPort, String serverIp, Logger logger) throws IOException {
        return startDedicatedHost(tcpPort, serverIp, null, logger);
    }

    public static HostStartResult startDedicatedHost(int tcpPort, String serverIp, String preferredRendezvousCode, Logger logger) throws IOException {
        String normalizedPreferredCode = P2pShareCode.normalizeRendezvousCode(preferredRendezvousCode);
        int token = normalizedPreferredCode != null ? P2pShareCode.rendezvousTunnelToken(normalizedPreferredCode) : createShareToken();
        P2pHostService service = new P2pHostService(
            tcpPort,
            token,
            resolveTargetAddress(serverIp, logger),
            normalizedPreferredCode,
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
