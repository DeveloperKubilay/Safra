package org.developerkubilay.safra.p2p;

import java.net.InetSocketAddress;

interface P2pQuicHostSession extends AutoCloseable {
    int port();

    String mode();

    String certificate();

    void punch(InetSocketAddress remoteAddress);

    @Override
    void close();
}
