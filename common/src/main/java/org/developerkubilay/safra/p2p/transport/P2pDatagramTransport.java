package org.developerkubilay.safra.p2p.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.SocketAddress;

public interface P2pDatagramTransport extends AutoCloseable {
    void receive(DatagramPacket packet) throws IOException;

    void send(DatagramPacket packet) throws IOException;

    int getLocalPort();

    SocketAddress getLocalSocketAddress();

    boolean isClosed();

    @Override
    void close();
}
