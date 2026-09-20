package org.developerkubilay.safra.p2p;

import java.net.InetSocketAddress;
import java.util.Collection;

public interface SafraVoiceHostSocket {
    int localPortSnapshot();
    Collection<InetSocketAddress> publicEndpointsSnapshot();
    void punchRemoteEndpoint(InetSocketAddress remoteAddress);
}
