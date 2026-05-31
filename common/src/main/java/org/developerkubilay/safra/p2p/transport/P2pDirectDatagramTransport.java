package org.developerkubilay.safra.p2p.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;

public final class P2pDirectDatagramTransport implements P2pDatagramTransport {
    private final DatagramSocket socket;

    public P2pDirectDatagramTransport(DatagramSocket socket) {
        this.socket = socket;
    }

    public DatagramSocket socket() {
        return socket;
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        socket.receive(packet);
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        synchronized (socket) {
            socket.send(packet);
        }
    }

    @Override
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return socket.getLocalSocketAddress();
    }

    @Override
    public boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public void close() {
        socket.close();
    }
}
