package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;
import tech.kwik.core.log.NullLogger;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

final class P2pKwikHostTunnel implements AutoCloseable {
    private final Logger logger;
    private final int token;
    private final int connectionId;
    private final int minecraftPort;
    private final java.net.InetAddress minecraftAddress;
    private final P2pKwikCertificate certificate;
    private final BiConsumer<P2pPacket, InetSocketAddress> sender;
    private volatile InetSocketAddress remoteAddress;
    private final Runnable removal;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean streamClaimed = new AtomicBoolean();

    private volatile P2pKwikGateway gateway;
    private volatile ServerConnector connector;
    private volatile DatagramSocket serverSocket;

    P2pKwikHostTunnel(Logger logger, int token, int connectionId, int minecraftPort, java.net.InetAddress minecraftAddress,
                      P2pKwikCertificate certificate, InetSocketAddress remoteAddress,
                      BiConsumer<P2pPacket, InetSocketAddress> sender, Runnable removal) {
        this.logger = logger;
        this.token = token;
        this.connectionId = connectionId;
        this.minecraftPort = minecraftPort;
        this.minecraftAddress = minecraftAddress;
        this.certificate = certificate;
        this.remoteAddress = remoteAddress;
        this.sender = sender;
        this.removal = removal;
    }

    void start() throws IOException, CertificateException {
        serverSocket = localDatagramSocket();
        DatagramSocket gatewaySocket = localDatagramSocket();
        gateway = new P2pKwikGateway(logger, gatewaySocket,
            (InetSocketAddress) serverSocket.getLocalSocketAddress(),
            datagram -> sender.accept(P2pPacket.quicData(token, connectionId, datagram), remoteAddress),
            "safra-kwik-host-gateway");

        ServerConnectionConfig configuration = ServerConnectionConfig.builder()
            .maxOpenPeerInitiatedBidirectionalStreams(1)
            .maxOpenPeerInitiatedUnidirectionalStreams(0)
            .maxConnectionBufferSize(P2pConstants.TCP_BUFFER_SIZE)
            .maxBidirectionalStreamBufferSize(P2pConstants.TCP_BUFFER_SIZE)
            .maxIdleTimeoutInSeconds(P2pConstants.KWIK_IDLE_TIMEOUT_SECONDS)
            .useStrictSmallestAllowedMaximumDatagramSize(true)
            .build();
        connector = ServerConnector.builder()
            .withSocket(serverSocket)
            .withKeyStore(certificate.keyStore(), "safra-p2p", P2pKwikCertificate.keyPassword())
            .withConfiguration(configuration)
            .withLogger(new NullLogger())
            .build();
        connector.registerApplicationProtocol(P2pConstants.KWIK_APPLICATION_PROTOCOL, new MinecraftProtocol());
        connector.start();
    }

    void sendCertificate() throws GeneralSecurityException {
        byte[] encoded = certificate.encoded();
        if (encoded.length > P2pConstants.MAX_PAYLOAD_SIZE) {
            throw new GeneralSecurityException("Kwik host sertifikası Safra UDP paketine sığmıyor");
        }
        sender.accept(P2pPacket.quicCertificate(token, connectionId, encoded), remoteAddress);
    }

    void updateRemoteAddress(InetSocketAddress remoteAddress) {
        if (remoteAddress != null && !remoteAddress.isUnresolved()) {
            if (remoteAddress.equals(this.remoteAddress)) {
                return;
            }
            this.remoteAddress = remoteAddress;
            sender.accept(P2pPacket.punch(token), remoteAddress);
            try {
                sendCertificate();
            } catch (GeneralSecurityException exception) {
                logger.debug("Safra Kwik host sertifikası yeni adrese gönderilemedi: {}", exception.toString());
            }
        }
    }

    void handlePacket(P2pPacket packet) {
        if (packet.type() == P2pPacket.Type.QUIC_OPEN) {
            try {
                sendCertificate();
            } catch (GeneralSecurityException exception) {
                logger.warn("Safra Kwik host sertifikası gönderilemedi: {}", exception.toString());
                close();
            }
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

    private void close(boolean notifyRemote) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (notifyRemote) {
            sender.accept(P2pPacket.close(token, connectionId), remoteAddress);
        }
        if (connector != null) {
            connector.close();
        }
        if (gateway != null) {
            gateway.close();
        }
        if (serverSocket != null) {
            serverSocket.close();
        }
        removal.run();
    }

    private void handleMinecraftStream(QuicStream stream) {
        if (!streamClaimed.compareAndSet(false, true)) {
            logger.warn("Safra Kwik host tunnel {} ikinci Minecraft akışını reddetti", connectionId);
            close();
            return;
        }
        try {
            Socket minecraftSocket = new Socket(minecraftAddress, minecraftPort);
            P2pSockets.tune(minecraftSocket);
            P2pKwikStreams.pipe(logger, "host", stream, minecraftSocket, this::close);
            logger.debug("Safra Kwik host tunnel {} yerel Minecraft'a bağlandı", connectionId);
        } catch (IOException exception) {
            logger.warn("Safra Kwik host tunnel {} yerel Minecraft'a bağlanamadı: {}", connectionId, exception.toString());
            close();
        }
    }

    private static DatagramSocket localDatagramSocket() throws IOException {
        return new DatagramSocket(new InetSocketAddress(P2pSockets.loopbackAddress(), 0));
    }

    private final class MinecraftProtocol implements ApplicationProtocolConnectionFactory {
        @Override
        public ApplicationProtocolConnection createConnection(String protocol, QuicConnection quicConnection) {
            return new ApplicationProtocolConnection() {
                @Override
                public void acceptPeerInitiatedStream(QuicStream stream) {
                    P2pRuntime.start("safra-kwik-host-stream", () -> handleMinecraftStream(stream));
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


