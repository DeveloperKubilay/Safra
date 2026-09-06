package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Safra UDP zarfı ile Kwik'in yerel UDP soketi arasındaki küçük köprü. */
final class P2pKwikGateway implements AutoCloseable {
    private final Logger logger;
    private final DatagramSocket gatewaySocket;
    private final InetSocketAddress kwikSocketAddress;
    private final Consumer<byte[]> outbound;
    private final AtomicBoolean closed = new AtomicBoolean();

    P2pKwikGateway(Logger logger, DatagramSocket gatewaySocket, InetSocketAddress kwikSocketAddress,
                   Consumer<byte[]> outbound, String threadName) {
        this.logger = logger;
        this.gatewaySocket = gatewaySocket;
        this.kwikSocketAddress = kwikSocketAddress;
        this.outbound = outbound;
        P2pRuntime.start(threadName, this::receiveLoop);
    }

    int port() {
        return gatewaySocket.getLocalPort();
    }

    void deliver(byte[] quicDatagram) {
        if (closed.get() || quicDatagram.length == 0 || quicDatagram.length > P2pConstants.MAX_PAYLOAD_SIZE) {
            return;
        }
        try {
            gatewaySocket.send(new DatagramPacket(quicDatagram, quicDatagram.length, kwikSocketAddress));
        } catch (IOException exception) {
            if (!closed.get()) {
                logger.debug("Safra Kwik yerel paket teslimi başarısız: {}", exception.toString());
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            gatewaySocket.close();
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[P2pConstants.MAX_PAYLOAD_SIZE];
        while (!closed.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                gatewaySocket.receive(packet);
                if (!packet.getSocketAddress().equals(kwikSocketAddress)) {
                    continue;
                }
                outbound.accept(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()));
            } catch (IOException exception) {
                if (!closed.get()) {
                    logger.debug("Safra Kwik yerel UDP köprüsü kapandı: {}", exception.toString());
                }
                return;
            }
        }
    }
}


