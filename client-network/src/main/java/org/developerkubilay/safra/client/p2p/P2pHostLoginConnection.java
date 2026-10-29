package org.developerkubilay.safra.client.p2p;

import org.developerkubilay.safra.p2p.P2pConstants;

public interface P2pHostLoginConnection {
    boolean safra$isHostTunnel();

    static int loginTicks(Object connection, int original) {
        return connection instanceof P2pHostLoginConnection host && host.safra$isHostTunnel()
            ? P2pConstants.HOST_LOGIN_TIMEOUT_TICKS : original;
    }
}
