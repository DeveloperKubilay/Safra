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
    private volatile Thread connectThread;
    private volatile QuicClientConnection connection;
    private volatile QuicStream stream;
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
        connectThread = Thread.currentThread();
        try {
            P2pSockets.tuneClient(minecraftSocket);
            long certificateDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMs);
            while (!closed.get() && certificate == null && System.nanoTime() < certificateDeadline) {
                requestCertificate(500L);
            }
            if (closed.get()) {
                return;
            }
            if (certificate == null) {
                throw new IOException("The Kwik host certificate did not arrive in time");
            }
            if (certificate.length == 0) {
                throw new IOException("The Kwik host certificate arrived empty");
            }

            long remainingNanos = certificateDeadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new IOException("The Kwik attempt timed out");
            }

            quicSocket = new P2pKwikDatagramSocket(
                (datagram, destination) -> sender.accept(P2pPacket.quicData(token, connectionId, datagram)));

            QuicClientConnectionImpl.ExtendedBuilder builder = new QuicClientConnectionImpl.ExtendedBuilder();
            builder.maxUdpPayloadSize(P2pConstants.MAX_PAYLOAD_SIZE);
            builder.enforceMaxUdpPayloadSize(true);
            builder.useStrictSmallestAllowedMaximumDatagramSize();
            builder.useLowLatencyCongestionControl(true);
            connection = builder
                .host(P2pConstants.LOCAL_PROXY_HOST)
                .port(P2pConstants.KWIK_VIRTUAL_PORT)
                .applicationProtocol(P2pConstants.KWIK_APPLICATION_PROTOCOL)
                .connectTimeout(Duration.ofNanos(remainingNanos))
                .maxIdleTimeout(Duration.ofSeconds(P2pConstants.KWIK_IDLE_TIMEOUT_SECONDS))
                .defaultStreamReceiveBufferSize((long) P2pConstants.MIN_STREAM_WINDOW_BYTES)
                .maxOpenPeerInitiatedBidirectionalStreams(1)
                .noServerCertificateCheck()
                .customTrustStore(P2pKwikCertificate.trustStore(certificate))
                .socketFactory(destination -> quicSocket)
                .build();
            connection.connect();
            if (closed.get()) {
                return;
            }
            stream = connection.createStream(true);
            if (established != null) {
                established.run();
            }
            P2pKwikStreams.pipe(logger, "client", stream, minecraftSocket, this::close);
            logger.debug("Safra Kwik client tunnel {} connected", connectionId);
        } catch (IOException | GeneralSecurityException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (closed.get()) {
                return;
            }
            logger.warn("Safra Kwik client tunnel {} could not be opened: {}", connectionId, exception.toString());
            if (failure != null) {
                closeForRetry();
                failure.run();
            } else {
                close();
            }
        } finally {
            connectThread = null;
        }
    }

    private void requestCertificate(long waitMs) throws InterruptedException {
        sender.accept(P2pPacket.quicOpen(token, connectionId));
        byte[] answer = certificates.poll(waitMs, TimeUnit.MILLISECONDS);
        if (answer == null) {
            return;
        }
        certificate = answer;
    }

    boolean matchesCertificate(byte[] encoded) {
        return !closed.get() && certificate != null && Arrays.equals(certificate, encoded);
    }

    boolean ownsSocket(Socket socket) {
        return minecraftSocket == socket;
    }

    long recoveryTimeoutNanos() {
        QuicClientConnection active = connection;
        if (active == null || closed.get()) {
            return TimeUnit.SECONDS.toNanos(7L);
        }
        Statistics stats = active.getStats();
        return TimeUnit.MILLISECONDS.toNanos(Math.max(7_000L, 6L * stats.smoothedRtt() + 4L * stats.rttVar()));
    }

    void ping() {
        if (!closed.get() && connection instanceof QuicClientConnectionImpl active && active.isConnected()) {
            active.ping();
        }
    }

    void logLinkQuality() {
        if (!SafraBuildInfo.diagnostics()) {
            return;
        }
        QuicClientConnection active = connection;
        if (active == null || closed.get()) {
            return;
        }
        P2pKwikStreams.logLinkQuality(logger, "client", connectionId, active, stream);
    }

    private void closeForRetry() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        interruptConnectThread();
        sender.accept(P2pPacket.close(token, connectionId));
        closeQuic();
        removal.run();
    }

    private void close(boolean notifyRemote) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        interruptConnectThread();
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

    private void interruptConnectThread() {
        Thread thread = connectThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
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
