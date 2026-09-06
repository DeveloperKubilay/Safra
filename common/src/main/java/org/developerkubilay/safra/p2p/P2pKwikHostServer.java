package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;
import org.slf4j.Logger;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One QUIC server for every joiner, the way a QUIC server is meant to be used. Each joiner gets a
 * loopback address of its own so Kwik separates them as it would separate real peers, and datagrams
 * Kwik sends are routed back to the Safra connection that address belongs to.
 */
final class P2pKwikHostServer implements AutoCloseable {
    private static final long PEER_IDLE_NANOS = TimeUnit.SECONDS.toNanos(2L * P2pConstants.KWIK_IDLE_TIMEOUT_SECONDS);
    /** Above the port the socket reports as its own, so a joiner is never given that address. */
    private static final int FIRST_PEER_PORT = P2pConstants.KWIK_VIRTUAL_PORT + 1;

    private final Logger logger;
    private final int token;
    private final int minecraftPort;
    private final InetAddress minecraftAddress;
    private final P2pKwikCertificate certificate;
    private final Sender sender;
    private final Map<Integer, Peer> peersByConnection = new ConcurrentHashMap<>();
    private final Map<Integer, Peer> peersByPort = new ConcurrentHashMap<>();
    private final AtomicInteger nextPeerPort = new AtomicInteger(P2pConstants.KWIK_VIRTUAL_PORT);
    private final P2pKwikDatagramSocket socket;
    private final ServerConnector connector;
    private volatile boolean closed;

    P2pKwikHostServer(Logger logger, int token, int minecraftPort, InetAddress minecraftAddress,
                      P2pKwikCertificate certificate, Sender sender) throws IOException, CertificateException {
        this.logger = logger;
        this.token = token;
        this.minecraftPort = minecraftPort;
        this.minecraftAddress = minecraftAddress;
        this.certificate = certificate;
        this.sender = sender;
        this.socket = new P2pKwikDatagramSocket(this::route);

        ServerConnectionConfig configuration = ServerConnectionConfig.builder()
            .maxOpenPeerInitiatedBidirectionalStreams(1)
            .maxOpenPeerInitiatedUnidirectionalStreams(0)
            .maxConnectionBufferSize(P2pConstants.TCP_BUFFER_SIZE)
            .maxBidirectionalStreamBufferSize(P2pConstants.TCP_BUFFER_SIZE)
            .maxIdleTimeoutInSeconds(P2pConstants.KWIK_IDLE_TIMEOUT_SECONDS)
            .useStrictSmallestAllowedMaximumDatagramSize(true)
            .build();
        connector = ServerConnector.builder()
            .withSocket(socket)
            .withKeyStore(certificate.keyStore(), "safra-p2p", P2pKwikCertificate.keyPassword())
            .withConfiguration(configuration)
            .withLogger(new NullLogger())
            .build();
        connector.registerApplicationProtocol(P2pConstants.KWIK_APPLICATION_PROTOCOL, new MinecraftProtocol());
        connector.start();
    }

    void handlePacket(P2pPacket packet, InetSocketAddress remoteAddress, P2pDatagramTransport transport) {
        if (closed) {
            return;
        }

        switch (packet.type()) {
            case QUIC_OPEN -> sendCertificate(peer(packet.connectionId(), remoteAddress, transport));
            case QUIC_DATA -> {
                Peer peer = peersByConnection.get(packet.connectionId());
                if (peer != null) {
                    refresh(peer, remoteAddress, transport);
                    socket.deliver(packet.payload(), peer.address);
                }
            }
            case CLOSE -> removePeer(packet.connectionId());
            default -> {
            }
        }
    }

    /** Forgets joiners that stopped sending, for the ones that leave without saying so. */
    void sweepIdlePeers() {
        long deadline = System.nanoTime() - PEER_IDLE_NANOS;
        peersByConnection.values().removeIf(peer -> {
            if (peer.lastSeenAt - deadline > 0L) {
                return false;
            }
            peersByPort.remove(peer.address.getPort());
            logger.debug("Safra Kwik host dropped idle joiner {}", peer.connectionId);
            return true;
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        peersByConnection.clear();
        peersByPort.clear();
        connector.close();
        socket.close();
    }

    private Peer peer(int connectionId, InetSocketAddress remoteAddress, P2pDatagramTransport transport) {
        Peer existing = peersByConnection.get(connectionId);
        if (existing == null) {
            Peer created = new Peer(connectionId, new InetSocketAddress(P2pSockets.loopbackAddress(), allocatePort()));
            existing = peersByConnection.putIfAbsent(connectionId, created);
            if (existing == null) {
                existing = created;
                peersByPort.put(created.address.getPort(), created);
                logger.debug("Safra Kwik host received connection request {} from {}", connectionId, remoteAddress);
            }
        }
        refresh(existing, remoteAddress, transport);
        return existing;
    }

    private void refresh(Peer peer, InetSocketAddress remoteAddress, P2pDatagramTransport transport) {
        peer.lastSeenAt = System.nanoTime();
        peer.transport = transport;
        if (remoteAddress == null || remoteAddress.isUnresolved() || remoteAddress.equals(peer.remote)) {
            return;
        }

        boolean moved = peer.remote != null;
        peer.remote = remoteAddress;
        if (moved) {
            sender.send(transport, P2pPacket.punch(token), remoteAddress);
            sendCertificate(peer);
        }
    }

    private void removePeer(int connectionId) {
        Peer peer = peersByConnection.remove(connectionId);
        if (peer != null) {
            peersByPort.remove(peer.address.getPort());
        }
    }

    private void route(byte[] datagram, InetSocketAddress destination) {
        Peer peer = destination == null ? null : peersByPort.get(destination.getPort());
        if (peer != null && peer.remote != null) {
            sender.send(peer.transport, P2pPacket.quicData(token, peer.connectionId, datagram), peer.remote);
        }
    }

    private void sendCertificate(Peer peer) {
        if (peer.remote == null) {
            return;
        }

        try {
            byte[] encoded = certificate.encoded();
            if (encoded.length > P2pConstants.MAX_PAYLOAD_SIZE) {
                throw new GeneralSecurityException("The Kwik host certificate does not fit in a Safra UDP packet");
            }
            sender.send(peer.transport, P2pPacket.quicCertificate(token, peer.connectionId, encoded), peer.remote);
        } catch (GeneralSecurityException exception) {
            logger.warn("Safra could not send the Kwik host certificate: {}", exception.toString());
        }
    }

    private int allocatePort() {
        return nextPeerPort.updateAndGet(port -> port >= 65535 ? FIRST_PEER_PORT : port + 1);
    }

    private void openMinecraftStream(QuicConnection quicConnection, QuicStream stream) {
        try {
            Socket minecraftSocket = new Socket(minecraftAddress, minecraftPort);
            P2pSockets.tune(minecraftSocket);
            P2pKwikStreams.pipe(logger, "host", stream, minecraftSocket, quicConnection::close);
            logger.debug("Safra Kwik host tunnel connected to local Minecraft {}:{}",
                minecraftAddress.getHostAddress(), minecraftPort);
        } catch (IOException exception) {
            logger.warn("Safra Kwik host could not reach local Minecraft: {}", exception.toString());
            quicConnection.close();
        }
    }

    @FunctionalInterface
    interface Sender {
        void send(P2pDatagramTransport transport, P2pPacket packet, InetSocketAddress destination);
    }

    private static final class Peer {
        private final int connectionId;
        private final InetSocketAddress address;
        private volatile InetSocketAddress remote;
        private volatile P2pDatagramTransport transport;
        private volatile long lastSeenAt;

        private Peer(int connectionId, InetSocketAddress address) {
            this.connectionId = connectionId;
            this.address = address;
        }
    }

    private final class MinecraftProtocol implements ApplicationProtocolConnectionFactory {
        @Override
        public ApplicationProtocolConnection createConnection(String protocol, QuicConnection quicConnection) {
            AtomicBoolean streamClaimed = new AtomicBoolean();
            return new ApplicationProtocolConnection() {
                @Override
                public void acceptPeerInitiatedStream(QuicStream stream) {
                    if (!streamClaimed.compareAndSet(false, true)) {
                        logger.warn("Safra Kwik host refused a second Minecraft stream on one QUIC connection");
                        quicConnection.close();
                        return;
                    }
                    P2pRuntime.start("safra-kwik-host-stream", () -> openMinecraftStream(quicConnection, stream));
                }
            };
        }

        @Override
        public int maxConcurrentPeerInitiatedUnidirectionalStreams() {
            return 0;
        }

        @Override
        public int maxConcurrentPeerInitiatedBidirectionalStreams() {
            return 1;
        }
    }
}
