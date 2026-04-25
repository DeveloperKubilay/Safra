package org.developerkubilay.safra.p2p;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SafraVoiceTransportManager {
    private static final SafraVoiceTransportManager INSTANCE = new SafraVoiceTransportManager();

    private final Set<SafraVoiceServerSocket> serverSockets = ConcurrentHashMap.newKeySet();

    private volatile SafraRendezvousClient.HostSession hostSession;
    private volatile String hostCode;
    private volatile SafraRendezvousClient.JoinSession joinSession;
    private volatile String joinCode;

    private SafraVoiceTransportManager() {
    }

    public static SafraVoiceTransportManager getInstance() {
        return INSTANCE;
    }

    public synchronized void setHostSession(String code, SafraRendezvousClient.HostSession session) {
        hostCode = code;
        hostSession = session;
        refreshServerSocketsAsync();
    }

    public synchronized void clearHostSession(SafraRendezvousClient.HostSession session) {
        if (hostSession == session) {
            hostSession = null;
            hostCode = null;
            refreshServerSocketsAsync();
        }
    }

    public synchronized void setJoinSession(String code, SafraRendezvousClient.JoinSession session) {
        joinCode = code;
        joinSession = session;
    }

    public synchronized void clearJoinSession(SafraRendezvousClient.JoinSession session) {
        if (joinSession == session) {
            joinSession = null;
            joinCode = null;
        }
    }

    SafraRendezvousClient.HostSession hostSession() {
        return hostSession;
    }

    String hostCode() {
        return hostCode;
    }

    SafraRendezvousClient.JoinSession joinSession() {
        return joinSession;
    }

    String joinCode() {
        return joinCode;
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
        Thread.ofVirtual()
            .name("safra-voice-refresh")
            .start(socket::refreshSafraBinding);
    }
}
