package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.Statistics;
import tech.kwik.core.impl.QuicClientConnectionImpl;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
    private final BlockingQueue<byte[]> certificates = new ArrayBlockingQueue<>(4);
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile byte[] certificate;
    private volatile QuicClientConnection connection;
    private volatile P2pKwikDatagramSocket quicSocket;

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
            certificates.offer(Arrays.copyOf(packet.payload(), packet.payload().length));
        } else if (packet.type() == P2pPacket.Type.QUIC_DATA && quicSocket != null) {
            quicSocket.deliver(packet.payload());
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
            long pathRoundTripNanos = 0L;
            while (certificate == null && System.nanoTime() < certificateDeadline) {
                pathRoundTripNanos = requestCertificate(500L);
            }
            if (certificate == null) {
                throw new IOException("The Kwik host certificate did not arrive in time");
            }
            if (certificate.length == 0) {
                throw new IOException("The Kwik host certificate arrived empty");
            }

            for (int probe = 0; probe < 2 && System.nanoTime() < certificateDeadline; probe++) {
                long warm = requestCertificate(250L);
                if (warm > 0L && warm < pathRoundTripNanos) {
                    pathRoundTripNanos = warm;
                }
            }

            long remainingNanos = certificateDeadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new IOException("The Kwik attempt timed out");
            }

            quicSocket = new P2pKwikDatagramSocket(
                (datagram, destination) -> sender.accept(P2pPacket.quicData(token, connectionId, datagram)));

            long connectTimeoutNanos = Math.max(TimeUnit.SECONDS.toNanos(4), remainingNanos);
            QuicClientConnectionImpl.ExtendedBuilder builder = new QuicClientConnectionImpl.ExtendedBuilder();
            builder.maxUdpPayloadSize(P2pConstants.MAX_PAYLOAD_SIZE);
            builder.enforceMaxUdpPayloadSize(true);
            builder.useStrictSmallestAllowedMaximumDatagramSize();
            connection = builder
                .host(P2pConstants.LOCAL_PROXY_HOST)
                .port(P2pConstants.KWIK_VIRTUAL_PORT)
                .applicationProtocol(P2pConstants.KWIK_APPLICATION_PROTOCOL)
                .connectTimeout(Duration.ofNanos(connectTimeoutNanos))
                .maxIdleTimeout(Duration.ofSeconds(P2pConstants.KWIK_IDLE_TIMEOUT_SECONDS))
                .defaultStreamReceiveBufferSize((long) P2pConstants.MAX_STREAM_WINDOW_BYTES)
                .maxOpenPeerInitiatedBidirectionalStreams(1)
                .noServerCertificateCheck()
                .customTrustStore(P2pKwikCertificate.trustStore(certificate))
                .socketFactory(destination -> quicSocket)
                .build();
            connection.connect();
            int roundTripMs = (int) TimeUnit.NANOSECONDS.toMillis(pathRoundTripNanos);
            int window = Math.max(P2pConstants.MAX_STREAM_WINDOW_BYTES, P2pConstants.streamWindowBytes(roundTripMs));
            connection.setDefaultBidirectionalStreamReceiveBufferSize(window);
            if (SafraBuildInfo.diagnostics()) {
                logger.info("Safra tunnel {} sized its window to {} bytes for a {}ms round trip",
                    connectionId, window, roundTripMs);
            }
            QuicStream stream = connection.createStream(true);
            P2pKwikStreams.pipe(logger, "client", stream, minecraftSocket, this::close);
            if (established != null) {
                established.run();
            }
            logger.info("Safra Kwik client tunnel {} connected", connectionId);
        } catch (IOException | GeneralSecurityException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Safra Kwik client tunnel {} could not be opened: {}", connectionId, exception.toString());
            if (!closed.get() && failure != null) {
                closeForRetry();
                failure.run();
            } else {
                close();
            }
        }
    }

    /** Asks the host to send its certificate, and reports how long the answer took, or 0 if none came. */
    private long requestCertificate(long waitMs) throws InterruptedException {
        long requestedAt = System.nanoTime();
        sender.accept(P2pPacket.quicOpen(token, connectionId));
        byte[] answer = certificates.poll(waitMs, TimeUnit.MILLISECONDS);
        if (answer == null) {
            return 0L;
        }
        certificate = answer;
        return System.nanoTime() - requestedAt;
    }

    void logLinkQuality() {
        if (!SafraBuildInfo.diagnostics()) {
            return;
        }
        QuicClientConnection active = connection;
        if (active == null || closed.get()) {
            return;
        }
        Statistics stats = active.getStats();
        logger.info("Safra tunnel {}: {} packets, {} lost, rtt {}ms (variation {}ms)",
            connectionId, stats.packetsSent(), stats.lostPackets(), stats.smoothedRtt(), stats.rttVar());
    }

    private void closeForRetry() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sender.accept(P2pPacket.close(token, connectionId));
        closeQuic();
        removal.run();
    }

    private void close(boolean notifyRemote) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (notifyRemote) {
            sender.accept(P2pPacket.close(token, connectionId));
        }
        closeQuic();
        try {
            minecraftSocket.close();
        } catch (IOException ignored) {
        }
        removal.run();
    }

    private void closeQuic() {
        if (connection != null) {
            connection.close();
        }
        if (quicSocket != null) {
            quicSocket.close();
        }
    }
}
