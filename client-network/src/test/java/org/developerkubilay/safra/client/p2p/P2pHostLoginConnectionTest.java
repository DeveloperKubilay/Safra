package org.developerkubilay.safra.client.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class P2pHostLoginConnectionTest {
    @Test
    void extendsOnlyMarkedHostConnectionsToAFiniteDeadline() {
        P2pHostLoginConnection host = () -> true;
        P2pHostLoginConnection ordinary = () -> false;
        assertEquals(3600, P2pHostLoginConnection.loginTicks(host, 600));
        assertEquals(3600, P2pHostLoginConnection.loginTicks(host, 600));
        assertEquals(600, P2pHostLoginConnection.loginTicks(ordinary, 600));
        assertEquals(600, P2pHostLoginConnection.loginTicks(new Object(), 600));
        assertEquals(600, P2pHostLoginConnection.loginTicks(null, 600));
    }
}
