package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDirectDatagramTransport;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentialClient;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentials;
import org.developerkubilay.safra.p2p.turn.P2pTurnDatagramTransport;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class P2pUdpBindingFactory {
    private P2pUdpBindingFactory() {
    }

    static P2pTransportBinding createBestHostBinding(Logger logger, P2pStunClient stunClient, int preferredPort) throws IOException {
        return createBestHostBinding(logger, stunClient, preferredPort, true);
    }

    static P2pTransportBinding createBestHostBinding(Logger logger, P2pStunClient stunClient, int preferredPort, boolean allowRelayFallback) throws IOException {
        if (allowRelayFallback && P2pConstants.forceTurnRelay()) {
            return createTurnBinding(logger, "host");
        }

        try {
            return createDirectHostBinding(stunClient, preferredPort);
        } catch (IOException exception) {
            if (!allowRelayFallback || !P2pConstants.turnEnabled()) {
                throw exception;
            }
            logger.debug("Safra host STUN acilamadi, TURN relay denenecek: {}", exception.toString());
            return createTurnBinding(logger, "host");
        }
    }

    static P2pTransportBinding createBestJoinBinding(Logger logger, P2pStunClient stunClient) throws IOException {
        if (P2pConstants.forceTurnRelay()) {
            return createTurnBinding(logger, "join");
        }

        try {
            return createDirectJoinBinding(stunClient);
        } catch (IOException exception) {
            if (!P2pConstants.turnEnabled()) {
                throw exception;
            }
            logger.debug("Safra join STUN acilamadi, TURN relay denenecek: {}", exception.toString());
            return createTurnBinding(logger, "join");
        }
    }

    static P2pTransportBinding createTurnBinding(Logger logger, String role) throws IOException {
        P2pTurnCredentials credentials = P2pTurnCredentialClient.fetch(role, P2pConstants.forceTurnRelay());
        P2pTurnDatagramTransport transport = P2pTurnDatagramTransport.open(logger, role, credentials);
        return new P2pTransportBinding(
            transport,
            Collections.singletonList(transport.relayAddress()),
            Collections.<String, P2pStunClient.DiscoveredEndpoint>emptyMap(),
            true
        );
    }

    private static P2pTransportBinding createDirectHostBinding(P2pStunClient stunClient, int preferredPort) throws IOException {
        DatagramSocket socket = bindSocket(preferredPort);
        boolean success = false;
        try {
            Map<String, P2pStunClient.DiscoveredEndpoint> discovered = stunClient.discoverCandidates(socket);
            if (discovered.isEmpty()) {
                throw new IOException("STUN ile genel UDP ucu bulunamadi");
            }
            success = true;
            return new P2pTransportBinding(
                new P2pDirectDatagramTransport(socket),
                P2pStunClient.publicEndpoints(discovered),
                new LinkedHashMap<String, P2pStunClient.DiscoveredEndpoint>(discovered),
                false
            );
        } finally {
            if (!success) {
                socket.close();
            }
        }
    }

    private static P2pTransportBinding createDirectJoinBinding(P2pStunClient stunClient) throws IOException {
        DatagramSocket socket = P2pSockets.datagramSocket();
        boolean success = false;
        try {
            Map<String, P2pStunClient.DiscoveredEndpoint> discovered = stunClient.discoverCandidates(socket);
            if (discovered.isEmpty()) {
                throw new IOException("STUN ile joiner genel UDP ucu bulunamadi");
            }
            success = true;
            return new P2pTransportBinding(
                new P2pDirectDatagramTransport(socket),
                P2pStunClient.publicEndpoints(discovered),
                new LinkedHashMap<String, P2pStunClient.DiscoveredEndpoint>(discovered),
                false
            );
        } finally {
            if (!success) {
                socket.close();
            }
        }
    }

    private static DatagramSocket bindSocket(int preferredPort) throws IOException {
        try {
            return P2pSockets.datagramSocket(preferredPort);
        } catch (BindException ignored) {
            return P2pSockets.datagramSocket();
        }
    }
}
