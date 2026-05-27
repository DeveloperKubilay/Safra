package org.developerkubilay.safra.p2p;

public final class SafraVoiceTransportManager {
    private static final SafraVoiceTransportManager INSTANCE = new SafraVoiceTransportManager();

    private volatile SafraRendezvousClient.HostSession hostSession;
    private volatile SafraRendezvousClient.JoinSession joinSession;

    private SafraVoiceTransportManager() {
    }

    public static SafraVoiceTransportManager getInstance() {
        return INSTANCE;
    }

    public synchronized void setHostSession(SafraRendezvousClient.HostSession session) {
        hostSession = session;
    }

    public synchronized void clearHostSession(SafraRendezvousClient.HostSession session) {
        if (hostSession == session) {
            hostSession = null;
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

    public SafraRendezvousClient.JoinSession joinSession() {
        return joinSession;
    }

    public boolean hasJoinSession() {
        return joinSession != null;
    }

    public void punchHostVoiceEndpoint(java.net.InetSocketAddress remoteAddress) {
    }
}
