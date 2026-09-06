package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDirectDatagramTransport;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentialClient;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentials;
import org.developerkubilay.safra.p2p.turn.P2pTurnDatagramTransport;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.util.List;
import java.util.Map;

final class P2pUdpBindingFactory {
    private P2pUdpBindingFactory() {
    }

    static P2pTransportBinding createBestHostBinding(Logger logger, P2pStunClient stunClient, int preferredPort) throws IOException {
        try {
            return createDirectHostBinding(logger, stunClient, preferredPort);
        } catch (IOException exception) {
            logger.debug("Safra host STUN could not be opened, trying relay-required flow: {}", exception.toString());
            return createLocalHostBinding(preferredPort);
        }
    }

    static P2pTransportBinding createBestJoinBinding(Logger logger, P2pStunClient stunClient) throws IOException {
        try {
            return createDirectJoinBinding(stunClient);
        } catch (IOException exception) {
            logger.debug("Safra join STUN could not be opened, trying relay-required flow: {}", exception.toString());
            return createLocalJoinBinding();
        }
    }

    static P2pTransportBinding createTurnBinding(Logger logger, String role) throws IOException {
        if (P2pConstants.neverUseRelayServer()) {
            throw new IOException("TURN relay is disabled in config");
        }
        P2pTurnCredentials credentials = P2pTurnCredentialClient.fetch(role, false);
        return createTurnBinding(logger, role, credentials);
    }

    static P2pTransportBinding createTurnBinding(Logger logger, String role, P2pTurnCredentials credentials) throws IOException {
        if (P2pConstants.neverUseRelayServer()) {
            throw new IOException("TURN relay is disabled in config");
        }
        P2pTurnDatagramTransport transport = P2pTurnDatagramTransport.open(logger, role, credentials);
        return new P2pTransportBinding(
            transport,
            List.of(transport.relayAddress()),
            Map.of(),
            true
        );
    }

    private static P2pTransportBinding createDirectHostBinding(Logger logger, P2pStunClient stunClient, int preferredPort) throws IOException {
        try {
            return createDirectBinding(bindIpv4Socket(preferredPort), stunClient, true,
                "Could not discover a public IPv4 UDP endpoint with STUN");
        } catch (IOException exception) {
            logger.info("Safra IPv4 zorlamasi ise yaramadi, genel STUN deneniyor: {}", exception.toString());
        }
        return createDirectBinding(bindSocket(preferredPort), stunClient, false,
            "Could not discover a public UDP endpoint with STUN");
    }

    static P2pTransportBinding createDirectJoinBinding(P2pStunClient stunClient) throws IOException {
        return createDirectBinding(P2pSockets.datagramSocket(), stunClient, false,
            "Could not discover a public joiner UDP endpoint with STUN");
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
                Map.copyOf(discovered),
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
            List.of(),
            Map.of(),
            false
        );
    }

    private static P2pTransportBinding createLocalJoinBinding() throws IOException {
        return new P2pTransportBinding(
            new P2pDirectDatagramTransport(P2pSockets.ipv4DatagramSocket()),
            List.of(),
            Map.of(),
            false
        );
    }
}
