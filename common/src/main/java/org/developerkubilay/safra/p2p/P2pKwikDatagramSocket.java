package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * The socket Kwik reads from and writes to. Datagrams do not reach the network here: outgoing ones
 * are handed to the Safra transport wrapped in a QUIC_DATA packet, and incoming ones are queued by
 * {@link #deliver(byte[])} as the Safra receive loop unwraps them.
 */
final class P2pKwikDatagramSocket extends DatagramSocket {
    private static final byte[] CLOSE_SIGNAL = new byte[0];
    private static final InetSocketAddress PEER_ADDRESS =
        new InetSocketAddress(P2pSockets.loopbackAddress(), P2pConstants.KWIK_VIRTUAL_PORT);

    private final Consumer<byte[]> outbound;
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    P2pKwikDatagramSocket(Consumer<byte[]> outbound) throws SocketException {
        super((SocketAddress) null);
        this.outbound = outbound;
    }

    void deliver(byte[] quicDatagram) {
        if (!closed && quicDatagram.length > 0 && quicDatagram.length <= P2pConstants.MAX_PAYLOAD_SIZE) {
            inbound.add(quicDatagram);
        }
    }

    @Override
    public void send(DatagramPacket packet) {
        if (closed) {
            return;
        }
        int offset = packet.getOffset();
        outbound.accept(Arrays.copyOfRange(packet.getData(), offset, offset + packet.getLength()));
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        byte[] datagram;
        try {
            datagram = inbound.take();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SocketException("Safra Kwik socket receive was interrupted");
        }

        if (datagram == CLOSE_SIGNAL) {
            inbound.add(CLOSE_SIGNAL);
            throw new SocketException("Safra Kwik socket is closed");
        }

        int length = Math.min(datagram.length, packet.getLength());
        System.arraycopy(datagram, 0, packet.getData(), packet.getOffset(), length);
        packet.setLength(length);
        packet.setAddress(PEER_ADDRESS.getAddress());
        packet.setPort(PEER_ADDRESS.getPort());
    }

    @Override
    public int getLocalPort() {
        return closed ? -1 : P2pConstants.KWIK_VIRTUAL_PORT;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return PEER_ADDRESS;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            inbound.add(CLOSE_SIGNAL);
        }
        super.close();
    }
}
