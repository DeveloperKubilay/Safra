package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BiConsumer;

/**
 * The socket Kwik reads from and writes to. Datagrams do not reach the network here: outgoing ones
 * are handed to the Safra transport, and incoming ones are queued by {@link #deliver} as the Safra
 * receive loop unwraps them. Each datagram carries the address Kwik should see it as coming from,
 * so one socket can serve several peers.
 */
final class P2pKwikDatagramSocket extends DatagramSocket {
    /** The peer of a socket that only ever talks to one, and the address the client dials. */
    static final InetSocketAddress SINGLE_PEER =
        new InetSocketAddress(P2pSockets.loopbackAddress(), P2pConstants.KWIK_VIRTUAL_PORT);
    private static final Datagram CLOSE_SIGNAL = new Datagram(new byte[0], SINGLE_PEER);
    /** As many datagrams as the socket buffer this stands in for would have held. */
    private static final int QUEUE_CAPACITY = P2pConstants.SOCKET_BUFFER_SIZE / P2pConstants.MAX_DATAGRAM_SIZE;

    private final BiConsumer<byte[], InetSocketAddress> outbound;
    private final BlockingQueue<Datagram> inbound = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean closed;

    P2pKwikDatagramSocket(BiConsumer<byte[], InetSocketAddress> outbound) throws SocketException {
        super((SocketAddress) null);
        this.outbound = outbound;
    }

    void deliver(byte[] quicDatagram) {
        deliver(quicDatagram, SINGLE_PEER);
    }

    void deliver(byte[] quicDatagram, InetSocketAddress peer) {
        if (!closed && quicDatagram.length > 0 && quicDatagram.length <= P2pConstants.MAX_PAYLOAD_SIZE) {
            // Dropped rather than queued when full, the way a full socket buffer drops: QUIC recovers
            // from loss, but a datagram delivered late distorts its round-trip estimate.
            inbound.offer(new Datagram(quicDatagram, peer));
        }
    }

    @Override
    public void send(DatagramPacket packet) {
        if (closed) {
            return;
        }
        int offset = packet.getOffset();
        outbound.accept(
            Arrays.copyOfRange(packet.getData(), offset, offset + packet.getLength()),
            (InetSocketAddress) packet.getSocketAddress()
        );
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        Datagram datagram;
        try {
            datagram = inbound.take();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SocketException("Safra Kwik socket receive was interrupted");
        }

        if (datagram == CLOSE_SIGNAL) {
            inbound.offer(CLOSE_SIGNAL);
            throw new SocketException("Safra Kwik socket is closed");
        }

        int length = Math.min(datagram.payload.length, packet.getLength());
        System.arraycopy(datagram.payload, 0, packet.getData(), packet.getOffset(), length);
        packet.setLength(length);
        packet.setAddress(datagram.peer.getAddress());
        packet.setPort(datagram.peer.getPort());
    }

    @Override
    public int getLocalPort() {
        return closed ? -1 : P2pConstants.KWIK_VIRTUAL_PORT;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return SINGLE_PEER;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            inbound.clear();
            inbound.offer(CLOSE_SIGNAL);
        }
        super.close();
    }

    private record Datagram(byte[] payload, InetSocketAddress peer) {
    }
}
