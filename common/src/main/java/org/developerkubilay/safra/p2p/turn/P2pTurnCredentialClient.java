package org.developerkubilay.safra.p2p.turn;

import java.io.IOException;

public final class P2pTurnCredentialClient {
    private P2pTurnCredentialClient() {
    }

    public static P2pTurnCredentials fetch(String role, boolean turnOnly) throws IOException {
        throw new IOException("Safra 1.8.9 buildinde TURN relay henuz desteklenmiyor");
    }
}
