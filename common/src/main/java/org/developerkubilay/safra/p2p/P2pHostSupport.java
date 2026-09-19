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

    public static String resolvePreferredRendezvousCode(String preferredRendezvousCode) {
        String normalized = P2pShareCode.normalizeRendezvousCode(preferredRendezvousCode);
        return normalized != null ? normalized : P2pShareCode.createRendezvousCode();
    }

    public static int createRendezvousShareToken(String rendezvousCode) {
        return P2pShareCode.rendezvousTunnelToken(rendezvousCode);
    }

    public static HostStartResult startDedicatedHost(int tcpPort, String serverIp, Logger logger) throws IOException {
        return startDedicatedHost(tcpPort, serverIp, null, logger);
    }

    public static HostStartResult startDedicatedHost(int tcpPort, String serverIp, String preferredRendezvousCode, Logger logger) throws IOException {
        String resolvedCode = P2pConstants.useApi30Rendezvous()
            ? resolvePreferredRendezvousCode(preferredRendezvousCode)
            : P2pShareCode.normalizeRendezvousCode(preferredRendezvousCode);
        P2pHostService service = new P2pHostService(
            tcpPort,
            P2pConstants.useApi30Rendezvous() ? createRendezvousShareToken(resolvedCode) : createShareToken(),
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
        if (serverIp == null || serverIp.trim().isEmpty() || "0.0.0.0".equals(serverIp) || "::".equals(serverIp)) {
            return InetAddress.getLoopbackAddress();
        }

        try {
            return InetAddress.getByName(serverIp);
        } catch (UnknownHostException exception) {
            logger.warn("Safra P2P could not resolve server-ip '{}', falling back to loopback", serverIp, exception);
            return InetAddress.getLoopbackAddress();
        }
    }

    public static final class HostStartResult {
        private final P2pHostService service;
        private final P2pShareCode shareCode;

        public HostStartResult(P2pHostService service, P2pShareCode shareCode) {
            this.service = service;
            this.shareCode = shareCode;
        }

        public P2pHostService service() {
            return service;
        }

        public P2pShareCode shareCode() {
            return shareCode;
        }
    }
}
