package org.developerkubilay.safra.p2p;

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
}
