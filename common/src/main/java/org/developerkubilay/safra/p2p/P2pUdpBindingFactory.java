package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDirectDatagramTransport;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentialClient;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentials;
import org.developerkubilay.safra.p2p.turn.P2pTurnDatagramTransport;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class P2pUdpBindingFactory {
    private P2pUdpBindingFactory() {
    }

    static P2pTransportBinding createBestHostBinding(Logger logger, P2pStunClient stunClient, int preferredPort) throws IOException {
        return createBestHostBinding(logger, stunClient, preferredPort, true);
    }

    static P2pTransportBinding createBestHostBinding(Logger logger, P2pStunClient stunClient, int preferredPort, boolean allowRelayFallback) throws IOException {

        try {
            return createDirectHostBinding(stunClient, preferredPort);
        } catch (IOException exception) {
            if (!allowRelayFallback || !P2pConstants.turnEnabled()) {
                throw exception;
            }
            logger.debug("Safra host STUN could not be opened, trying TURN relay: {}", exception.toString());
            return createTurnBinding(logger, "host");
        }
    }

    static P2pTransportBinding createBestJoinBinding(Logger logger, P2pStunClient stunClient) throws IOException {

        try {
            return createDirectJoinBinding(stunClient);
        } catch (IOException exception) {
            if (P2pConstants.neverUseRelayServer()) {
                throw exception;
            }
            if (!P2pConstants.turnEnabled()) {
                throw exception;
            }
            logger.debug("Safra join STUN could not be opened, trying TURN relay: {}", exception.toString());
            return createTurnBinding(logger, "join");
        }
    }

    static P2pTransportBinding createTurnBinding(Logger logger, String role) throws IOException {
        if (P2pConstants.neverUseRelayServer()) {
            throw new IOException("TURN relay is disabled in config");
        }
        P2pTurnCredentials credentials = P2pTurnCredentialClient.fetch(role, false);
        P2pTurnDatagramTransport transport = P2pTurnDatagramTransport.open(logger, role, credentials);
        List<InetSocketAddress> endpoints = new ArrayList<>();
        endpoints.add(transport.relayAddress());
        return new P2pTransportBinding(
            transport,
            endpoints,
            new HashMap<String, P2pStunClient.DiscoveredEndpoint>(),
            true
        );
    }

    private static P2pTransportBinding createDirectHostBinding(P2pStunClient stunClient, int preferredPort) throws IOException {
        DatagramSocket socket = bindSocket(preferredPort);
        boolean success = false;
        try {
            Map<String, P2pStunClient.DiscoveredEndpoint> discovered = stunClient.discoverCandidates(socket);
            if (discovered.isEmpty()) {
                throw new IOException("Could not discover a public UDP endpoint with STUN");
            }
            success = true;
            return new P2pTransportBinding(
                new P2pDirectDatagramTransport(socket),
                P2pStunClient.publicEndpoints(discovered),
                new HashMap<>(discovered),
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
                throw new IOException("Could not discover a public joiner UDP endpoint with STUN");
            }
            success = true;
            return new P2pTransportBinding(
                new P2pDirectDatagramTransport(socket),
                P2pStunClient.publicEndpoints(discovered),
                new HashMap<>(discovered),
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
