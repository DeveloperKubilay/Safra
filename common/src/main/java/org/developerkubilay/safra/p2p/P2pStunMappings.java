package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class P2pStunMappings {
    private final P2pStunClient stunClient = new P2pStunClient();
    private final Map<String, P2pStunClient.DiscoveredEndpoint> endpoints = new ConcurrentHashMap<>();
    private final List<P2pStunClient.PendingRequest> pendingRequests = new CopyOnWriteArrayList<>();

    Collection<InetSocketAddress> discoverPublicEndpoints(DatagramSocket socket) {
        Map<String, P2pStunClient.DiscoveredEndpoint> discovered = stunClient.discoverCandidates(socket);
        replaceWith(discovered);
        return P2pStunClient.publicEndpoints(discovered);
    }

    void clear() {
        endpoints.clear();
        pendingRequests.clear();
    }

    boolean isEmpty() {
        return endpoints.isEmpty();
    }

    int size() {
        return endpoints.size();
    }

    Collection<InetSocketAddress> publicEndpoints() {
        return P2pStunClient.publicEndpoints(endpoints);
    }

    P2pStunClient.DiscoveredEndpoint preferredCandidate() {
        return P2pStunClient.preferredCandidate(endpoints);
    }

    void requestCandidates(DatagramSocket socket) {
        pendingRequests.clear();
        pendingRequests.addAll(stunClient.requestCandidates(socket));
    }

    void sendKeepAlives(DatagramSocket socket, Logger logger, String debugPrefix) {
        for (P2pStunClient.DiscoveredEndpoint endpoint : endpoints.values()) {
            if (endpoint.stunServer() == null) {
                continue;
            }
            try {
                stunClient.sendKeepAlive(socket, endpoint.stunServer());
            } catch (IOException exception) {
                logger.debug("{}: {}", debugPrefix, exception.toString());
            }
        }
    }

    boolean rememberResponse(DatagramPacket packet) {
        for (P2pStunClient.PendingRequest pendingRequest : pendingRequests) {
            P2pStunClient.DiscoveredEndpoint refreshed = stunClient.tryParseResponse(packet, pendingRequest);
            if (refreshed == null) {
                continue;
            }

            pendingRequests.remove(pendingRequest);
            endpoints.put(refreshed.family(), refreshed.withServer(pendingRequest.server()));
            return true;
        }

        SocketAddress remote = packet.getSocketAddress();
        for (P2pStunClient.DiscoveredEndpoint endpoint : endpoints.values()) {
            if (!endpoint.matches(remote)) {
                continue;
            }

            P2pStunClient.DiscoveredEndpoint refreshed = stunClient.tryParseResponse(packet);
            if (refreshed == null) {
                return false;
            }

            endpoints.put(refreshed.family(), refreshed.withServer(endpoint.stunServer()));
            return true;
        }

        return false;
    }

    private void replaceWith(Map<String, P2pStunClient.DiscoveredEndpoint> discovered) {
        endpoints.clear();
        endpoints.putAll(discovered);
    }
}
