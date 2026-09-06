package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;

record P2pTransportBinding(
    P2pDatagramTransport transport,
    Collection<InetSocketAddress> publicEndpoints,
    Map<P2pSockets.AddressFamily, P2pStunClient.DiscoveredEndpoint> stunEndpoints,
    boolean relay
) implements AutoCloseable {
    @Override
    public void close() {
        transport.close();
    }
}
