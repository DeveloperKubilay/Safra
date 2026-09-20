package org.developerkubilay.safra.p2p;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SafraVoiceTransportManager {
    private static final SafraVoiceTransportManager INSTANCE = new SafraVoiceTransportManager();

    private final Set<SafraVoiceHostSocket> serverSockets = ConcurrentHashMap.newKeySet();

    private volatile SafraRendezvousClient.JoinSession joinSession;

    private SafraVoiceTransportManager() {
    }

    public static SafraVoiceTransportManager getInstance() {
        return INSTANCE;
    }

    public synchronized void setJoinSession(SafraRendezvousClient.JoinSession session) {
        joinSession = session;
    }

    public synchronized void clearJoinSession(SafraRendezvousClient.JoinSession session) {
        if (joinSession == session) {
            joinSession = null;
        }
    }

    public SafraRendezvousClient.JoinSession joinSession() {
        return joinSession;
    }

    public boolean hasJoinSession() {
        return joinSession != null;
    }

    void registerServerSocket(SafraVoiceHostSocket socket) {
        serverSockets.add(socket);
    }

    void unregisterServerSocket(SafraVoiceHostSocket socket) {
        serverSockets.remove(socket);
    }

    public void punchHostVoiceEndpoint(java.net.InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return;
        }
        for (SafraVoiceHostSocket socket : serverSockets) {
            P2pRuntime.start("safra-voice-punch", () -> socket.punchRemoteEndpoint(remoteAddress));
        }
    }

    public Collection<InetSocketAddress> awaitHostVoiceEndpoints(int preferredPort, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        Collection<InetSocketAddress> endpoints = hostVoiceEndpointsSnapshot(preferredPort);
        while (endpoints.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
            endpoints = hostVoiceEndpointsSnapshot(preferredPort);
        }
        return endpoints;
    }

    public Collection<InetSocketAddress> hostVoiceEndpointsSnapshot(int preferredPort) {
        if (preferredPort > 0) {
            for (SafraVoiceHostSocket socket : serverSockets) {
                if (socket.localPortSnapshot() != preferredPort) {
                    continue;
                }
                Collection<InetSocketAddress> endpoints = socket.publicEndpointsSnapshot();
                if (!endpoints.isEmpty()) {
                    return List.copyOf(endpoints);
                }
            }
            return List.of();
        }
        for (SafraVoiceHostSocket socket : serverSockets) {
            Collection<InetSocketAddress> endpoints = socket.publicEndpointsSnapshot();
            if (!endpoints.isEmpty()) {
                return List.copyOf(endpoints);
            }
        }
        return List.of();
    }
}
