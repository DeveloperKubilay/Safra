package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;

final class P2pTransportBinding implements AutoCloseable {
    private final P2pDatagramTransport transport;
    private final Collection<InetSocketAddress> publicEndpoints;
    private final Map<String, P2pStunClient.DiscoveredEndpoint> stunEndpoints;
    private final boolean relay;

    P2pTransportBinding(
        P2pDatagramTransport transport,
        Collection<InetSocketAddress> publicEndpoints,
        Map<String, P2pStunClient.DiscoveredEndpoint> stunEndpoints,
        boolean relay
    ) {
        this.transport = transport;
        this.publicEndpoints = publicEndpoints;
        this.stunEndpoints = stunEndpoints;
        this.relay = relay;
    }

    P2pDatagramTransport transport() {
        return transport;
    }

    Collection<InetSocketAddress> publicEndpoints() {
        return publicEndpoints;
    }

    Map<String, P2pStunClient.DiscoveredEndpoint> stunEndpoints() {
        return stunEndpoints;
    }

    boolean relay() {
        return relay;
    }

    @Override
    public void close() {
        transport.close();
    }
}
