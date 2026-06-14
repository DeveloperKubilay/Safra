package org.developerkubilay.safra.client.config;

import org.developerkubilay.safra.p2p.P2pConstants;

public final class RemoteRendezvousConfigUpdater {
    private RemoteRendezvousConfigUpdater() {
    }

    public static void initialize(BaseSafraClientConfig config) {
        if (config == null) {
            return;
        }

        P2pConstants.setRuntimeRendezvousUrl(config.getRendezvousUrl());
        P2pConstants.setRuntimeNeverUseRelayServer(config.isNeverUseRelayServer());
    }
}
