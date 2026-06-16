package org.developerkubilay.safra.p2p;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SafraVoiceTransportManager {
    private static final SafraVoiceTransportManager INSTANCE = new SafraVoiceTransportManager();

    private final Set<SafraVoiceServerSocket> serverSockets = ConcurrentHashMap.newKeySet();

    private volatile SafraRendezvousClient.HostSession hostSession;
    private volatile SafraRendezvousClient.JoinSession joinSession;

    private SafraVoiceTransportManager() {
    }

    public static SafraVoiceTransportManager getInstance() {
        return INSTANCE;
    }

    public synchronized void setHostSession(SafraRendezvousClient.HostSession session) {
        hostSession = session;
        refreshServerSocketsAsync();
    }

    public synchronized void clearHostSession(SafraRendezvousClient.HostSession session) {
        if (hostSession == session) {
            hostSession = null;
            refreshServerSocketsAsync();
        }
    }

    public synchronized void setJoinSession(SafraRendezvousClient.JoinSession session) {
        joinSession = session;
    }

    public synchronized void clearJoinSession(SafraRendezvousClient.JoinSession session) {
        if (joinSession == session) {
            joinSession = null;
        }
    }

    SafraRendezvousClient.HostSession hostSession() {
        return hostSession;
    }

    String hostCode() {
        SafraRendezvousClient.HostSession session = hostSession;
        return session == null ? null : session.code();
    }

    public SafraRendezvousClient.JoinSession joinSession() {
        return joinSession;
    }

    public boolean hasJoinSession() {
        return joinSession != null;
    }

    void registerServerSocket(SafraVoiceServerSocket socket) {
        serverSockets.add(socket);
        refreshServerSocketAsync(socket);
    }

    void unregisterServerSocket(SafraVoiceServerSocket socket) {
        serverSockets.remove(socket);
    }

    private void refreshServerSocketsAsync() {
        for (SafraVoiceServerSocket socket : serverSockets) {
            refreshServerSocketAsync(socket);
        }
    }

    private void refreshServerSocketAsync(SafraVoiceServerSocket socket) {
        P2pRuntime.start("safra-voice-refresh", socket::refreshSafraBinding);
    }

    public void punchHostVoiceEndpoint(java.net.InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return;
        }
        for (SafraVoiceServerSocket socket : serverSockets) {
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
            for (SafraVoiceServerSocket socket : serverSockets) {
                if (socket.localPortSnapshot() != preferredPort) {
                    continue;
                }
                Collection<InetSocketAddress> endpoints = socket.publicEndpointsSnapshot();
                if (!endpoints.isEmpty()) {
                    return List.copyOf(endpoints);
                }
            }
        }
        for (SafraVoiceServerSocket socket : serverSockets) {
            Collection<InetSocketAddress> endpoints = socket.publicEndpointsSnapshot();
            if (!endpoints.isEmpty()) {
                return List.copyOf(endpoints);
            }
        }
        return List.of();
    }
}
