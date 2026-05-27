package org.developerkubilay.safra.p2p.turn;

import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class P2pTurnDatagramTransport implements P2pDatagramTransport {
    private P2pTurnDatagramTransport() {
    }

    public static P2pTurnDatagramTransport open(Logger logger, String role, P2pTurnCredentials credentials) throws IOException {
        throw new IOException("TURN transport 1.10.2 portunda henuz devre disi");
    }

    public InetSocketAddress relayAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        throw new IOException("TURN transport kapali");
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        throw new IOException("TURN transport kapali");
    }

    @Override
    public int getLocalPort() {
        return 0;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return relayAddress();
    }

    @Override
    public boolean isClosed() {
        return true;
    }

    @Override
    public void close() {
    }
}
