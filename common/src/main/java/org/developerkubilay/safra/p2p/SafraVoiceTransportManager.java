package org.developerkubilay.safra.p2p;

import java.net.InetSocketAddress;

public final class SafraVoiceTransportManager {
    private static final SafraVoiceTransportManager INSTANCE = new SafraVoiceTransportManager();

    private SafraVoiceTransportManager() {
    }

    public static SafraVoiceTransportManager getInstance() {
        return INSTANCE;
    }

    public void setHostSession(SafraRendezvousClient.HostSession session) {
    }

    public void clearHostSession(SafraRendezvousClient.HostSession session) {
    }

    public void setJoinSession(SafraRendezvousClient.JoinSession session) {
    }

    public void clearJoinSession(SafraRendezvousClient.JoinSession session) {
    }

    public void punchHostVoiceEndpoint(InetSocketAddress remoteAddress) {
    }
}
