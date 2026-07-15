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
            return createDirectHostBinding(logger, stunClient, preferredPort);
        } catch (IOException exception) {
            if (P2pConstants.useApi30Rendezvous()) {
                logger.debug("Safra host STUN acilamadi, relay-required akisi denenecek: {}", exception.toString());
                return createLocalHostBinding(preferredPort);
            }
            if (!(allowRelayFallback && !P2pConstants.neverUseRelayServer())) {
                throw exception;
            }
            logger.debug("Safra host STUN acilamadi, TURN relay denenecek: {}", exception.toString());
            return createTurnBinding(logger, "host");
        }
    }

    static P2pTransportBinding createBestJoinBinding(Logger logger, P2pStunClient stunClient) throws IOException {

        try {
            return createDirectJoinBinding(logger, stunClient);
        } catch (IOException exception) {
            if (P2pConstants.useApi30Rendezvous()) {
                logger.debug("Safra join STUN acilamadi, relay-required akisi denenecek: {}", exception.toString());
                return createLocalJoinBinding();
            }
            if (P2pConstants.neverUseRelayServer()) {
                throw exception;
            }
            logger.debug("Safra join STUN acilamadi, TURN relay denenecek: {}", exception.toString());
            return createTurnBinding(logger, "join");
        }
    }

    static P2pTransportBinding createTurnBinding(Logger logger, String role) throws IOException {
        if (P2pConstants.neverUseRelayServer()) {
            throw new IOException("TURN relay configde kapali");
        }
        P2pTurnCredentials credentials = P2pTurnCredentialClient.fetch(role, false);
        return createTurnBinding(logger, role, credentials);
    }

    static P2pTransportBinding createTurnBinding(Logger logger, String role, P2pTurnCredentials credentials) throws IOException {
        if (P2pConstants.neverUseRelayServer()) {
            throw new IOException("TURN relay configde kapali");
        }
        P2pTurnDatagramTransport transport = P2pTurnDatagramTransport.open(logger, role, credentials);
        return new P2pTransportBinding(
            transport,
            java.util.Collections.singletonList(transport.relayAddress()),
            java.util.Collections.<String, P2pStunClient.DiscoveredEndpoint>emptyMap(),
            true
        );
    }

    private static P2pTransportBinding createDirectHostBinding(Logger logger, P2pStunClient stunClient, int preferredPort) throws IOException {
        boolean success = false;
        try {
            return createDirectBinding(bindIpv4Socket(preferredPort), stunClient, true, "STUN ile IPv4 UDP ucu bulunamadi");
        } catch (IOException exception) {
            logger.info("Safra IPv4 STUN basarisiz, genel STUN deneniyor: {}", exception.toString());
        }
        return createDirectBinding(bindSocket(preferredPort), stunClient, false, "STUN ile genel UDP ucu bulunamadi");
    }

    static P2pTransportBinding createDirectJoinBinding(Logger logger, P2pStunClient stunClient) throws IOException {
        try {
            return createDirectBinding(P2pSockets.ipv4DatagramSocket(), stunClient, true, "STUN ile IPv4 joiner ucu bulunamadi");
        } catch (IOException exception) {
            logger.info("Safra IPv4 STUN basarisiz, genel STUN deneniyor: {}", exception.toString());
        }
        return createDirectBinding(P2pSockets.datagramSocket(), stunClient, false, "STUN ile joiner genel UDP ucu bulunamadi");
    }

    private static P2pTransportBinding createDirectBinding(DatagramSocket socket, P2pStunClient stunClient,
                                                            boolean ipv4Only, String failureMessage) throws IOException {
        boolean success = false;
        try {
            Map<String, P2pStunClient.DiscoveredEndpoint> discovered = ipv4Only
                ? stunClient.discoverIpv4Candidates(socket)
                : stunClient.discoverCandidates(socket);
            if (discovered.isEmpty()) {
                throw new IOException(failureMessage);
            }
            success = true;
            return new P2pTransportBinding(
                new P2pDirectDatagramTransport(socket),
                P2pStunClient.publicEndpoints(discovered),
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<String, P2pStunClient.DiscoveredEndpoint>(discovered)),
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

    private static DatagramSocket bindIpv4Socket(int preferredPort) throws IOException {
        try {
            return P2pSockets.ipv4DatagramSocket(preferredPort);
        } catch (BindException ignored) {
            return P2pSockets.ipv4DatagramSocket();
        }
    }

    private static P2pTransportBinding createLocalHostBinding(int preferredPort) throws IOException {
        DatagramSocket socket = bindSocket(preferredPort);
        return new P2pTransportBinding(
            new P2pDirectDatagramTransport(socket),
            java.util.Collections.<InetSocketAddress>emptyList(),
            java.util.Collections.<String, P2pStunClient.DiscoveredEndpoint>emptyMap(),
            false
        );
    }

    private static P2pTransportBinding createLocalJoinBinding() throws IOException {
        DatagramSocket socket = P2pSockets.ipv4DatagramSocket();
        return new P2pTransportBinding(
            new P2pDirectDatagramTransport(socket),
            java.util.Collections.singletonList(new InetSocketAddress(P2pSockets.ipv4WildcardAddress(), socket.getLocalPort())),
            java.util.Collections.<String, P2pStunClient.DiscoveredEndpoint>emptyMap(),
            false
        );
    }
}
