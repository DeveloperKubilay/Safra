package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.impl.QuicClientConnectionImpl;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class P2pKwikClientTunnel implements AutoCloseable {
    private final Logger logger;
    private final int token;
    private final int connectionId;
    private final Socket minecraftSocket;
    private final Consumer<P2pPacket> sender;
    private final Runnable removal;
    private final long attemptTimeoutMs;
    private final Runnable failure;
    private final Runnable established;
    private final CountDownLatch certificateReady = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile byte[] certificate;
    private volatile P2pKwikGateway gateway;
    private volatile QuicClientConnection connection;
    private volatile DatagramSocket quicSocket;

    P2pKwikClientTunnel(Logger logger, int token, int connectionId, Socket minecraftSocket,
                         long attemptTimeoutMs, Consumer<P2pPacket> sender, Runnable removal,
                         Runnable failure, Runnable established) {
        this.logger = logger;
        this.token = token;
        this.connectionId = connectionId;
        this.minecraftSocket = minecraftSocket;
        this.attemptTimeoutMs = attemptTimeoutMs;
        this.sender = sender;
        this.removal = removal;
        this.failure = failure;
        this.established = established;
    }

    void start() {
        P2pRuntime.start("safra-kwik-client-connect", this::connect);
    }

    void handlePacket(P2pPacket packet) {
        if (packet.type() == P2pPacket.Type.QUIC_CERTIFICATE) {
            certificate = Arrays.copyOf(packet.payload(), packet.payload().length);
            certificateReady.countDown();
        } else if (packet.type() == P2pPacket.Type.QUIC_DATA && gateway != null) {
            gateway.deliver(packet.payload());
        } else if (packet.type() == P2pPacket.Type.CLOSE) {
            close(false);
        }
    }

    @Override
    public void close() {
        close(true);
    }

    private void connect() {
        try {
            P2pSockets.tune(minecraftSocket);
            long certificateDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMs);
            while (certificateReady.getCount() != 0 && System.nanoTime() < certificateDeadline) {
                sender.accept(P2pPacket.quicOpen(token, connectionId));
                certificateReady.await(500L, TimeUnit.MILLISECONDS);
            }
            if (certificateReady.getCount() != 0) {
                throw new IOException("Kwik host sertifikası zamanında gelmedi");
            }
            if (certificate == null || certificate.length == 0) {
                throw new IOException("Kwik host sertifikası boş geldi");
            }

            long remainingNanos = certificateDeadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new IOException("Kwik denemesi zaman aşımına uğradı");
            }

            quicSocket = localDatagramSocket();
            DatagramSocket gatewaySocket = localDatagramSocket();
            gateway = new P2pKwikGateway(logger, gatewaySocket,
                (InetSocketAddress) quicSocket.getLocalSocketAddress(),
                datagram -> sender.accept(P2pPacket.quicData(token, connectionId, datagram)),
                "safra-kwik-client-gateway");

            QuicClientConnectionImpl.ExtendedBuilder builder = new QuicClientConnectionImpl.ExtendedBuilder();
            builder.maxUdpPayloadSize(P2pConstants.MAX_PAYLOAD_SIZE);
            builder.enforceMaxUdpPayloadSize(true);
            builder.useStrictSmallestAllowedMaximumDatagramSize();
            connection = builder
                .host(P2pConstants.LOCAL_PROXY_HOST)
                .port(gateway.port())
                .applicationProtocol(P2pConstants.KWIK_APPLICATION_PROTOCOL)
                .connectTimeout(Duration.ofNanos(remainingNanos))
                .maxIdleTimeout(Duration.ofSeconds(P2pConstants.KWIK_IDLE_TIMEOUT_SECONDS))
                .defaultStreamReceiveBufferSize((long) P2pConstants.TCP_BUFFER_SIZE)
                .maxOpenPeerInitiatedBidirectionalStreams(1)
                .noServerCertificateCheck()
                .customTrustStore(P2pKwikCertificate.trustStore(certificate))
                .socketFactory(destination -> quicSocket)
                .build();
            connection.connect();
            QuicStream stream = connection.createStream(true);
            P2pKwikStreams.pipe(logger, "client", stream, minecraftSocket, this::close);
            if (established != null) {
                established.run();
            }
            logger.debug("Safra Kwik client tunnel {} connected", connectionId);
        } catch (IOException | GeneralSecurityException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Safra Kwik client tunnel {} açılamadı: {}", connectionId, exception.toString());
            if (!closed.get() && failure != null) {
                closeForRetry();
                failure.run();
            } else {
                close();
            }
        }
    }

    private void closeForRetry() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sender.accept(P2pPacket.close(token, connectionId));
        if (connection != null) {
            connection.close();
        }
        if (gateway != null) {
            gateway.close();
        }
        if (quicSocket != null) {
            quicSocket.close();
        }
        removal.run();
    }

    private void close(boolean notifyRemote) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (notifyRemote) {
            sender.accept(P2pPacket.close(token, connectionId));
        }
        if (connection != null) {
            connection.close();
        }
        if (gateway != null) {
            gateway.close();
        }
        if (quicSocket != null) {
            quicSocket.close();
        }
        try {
            minecraftSocket.close();
        } catch (IOException ignored) {
        }
        removal.run();
    }

    private static DatagramSocket localDatagramSocket() throws IOException {
        return new DatagramSocket(new InetSocketAddress(P2pSockets.loopbackAddress(), 0));
    }
}
