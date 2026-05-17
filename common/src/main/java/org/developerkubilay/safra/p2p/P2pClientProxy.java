package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;
import org.developerkubilay.safra.p2p.transport.P2pDirectDatagramTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class P2pClientProxy implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pClientProxy.class);

    private final P2pShareCode shareCode;
    private final P2pStunClient stunClient = new P2pStunClient();
    private final Map<Integer, ReliableTunnelConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = P2pRuntime.schedulerPool(2);
    private final Runnable onClose;
    private final Consumer<String> statusMessageSink;

    private P2pDatagramTransport transport;
    private ServerSocket proxyServer;
    private InetSocketAddress remoteAddress;
    private SafraRendezvousClient.JoinSession rendezvousSession;
    private DirectTcpBridge directTcpBridge;
    private int remoteQuicPort;
    private String remoteQuicCertificate = "";
    private int tunnelToken;
    private int quicLocalPort;
    private volatile boolean closed;

    public P2pClientProxy(P2pShareCode shareCode, Runnable onClose, Consumer<String> statusMessageSink) {
        this.shareCode = shareCode;
        this.onClose = onClose;
        this.statusMessageSink = statusMessageSink;
    }

    public int start() throws IOException {
        if (shareCode.isRendezvous()) {
            resolveRendezvousShareCode();
            if (P2pOptionalIntegrations.isVoiceChatAvailable()) {
                SafraVoiceTransportManager.getInstance().setJoinSession(rendezvousSession);
            } else {
                LOGGER.debug("Safra voicechat modu yok; join rendezvous websocket'i eslesme sonrasi kapatiliyor");
                discardRendezvousSession();
            }
        } else {
            transport = new P2pDirectDatagramTransport(P2pSockets.datagramSocket());
            InetAddress remoteInetAddress = InetAddress.getByName(shareCode.host());
            remoteAddress = new InetSocketAddress(remoteInetAddress, shareCode.port());
            tunnelToken = shareCode.token();
            remoteQuicPort = 0;
            remoteQuicCertificate = "";
            quicLocalPort = transport.getLocalPort();
        }

        proxyServer = new ServerSocket(0, 16, P2pSockets.loopbackAddress());
        LOGGER.debug("Safra P2P client proxy listening on {}:{} and dialing {}",
            proxyServer.getInetAddress().getHostAddress(), proxyServer.getLocalPort(), remoteAddress);

        P2pRuntime.start("safra-p2p-client-recv", this::receiveLoop);
        P2pRuntime.start("safra-p2p-client-accept", this::acceptLoop);
        return proxyServer.getLocalPort();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connections.values().forEach(ReliableTunnelConnection::close);
        connections.clear();
        scheduler.shutdownNow();
        if (proxyServer != null && !proxyServer.isClosed()) {
            try {
                proxyServer.close();
            } catch (IOException ignored) {
            }
        }
        if (transport != null && !transport.isClosed()) {
            transport.close();
        }
        if (directTcpBridge != null) {
            directTcpBridge.close();
            directTcpBridge = null;
        }
        if (rendezvousSession != null) {
            SafraVoiceTransportManager.getInstance().clearJoinSession(rendezvousSession);
            rendezvousSession.close();
            rendezvousSession = null;
        }
        LOGGER.debug("Safra P2P client proxy closed for {}", remoteAddress);
        onClose.run();
    }

    private void resolveRendezvousShareCode() throws IOException {
        P2pTransportBinding binding = P2pUdpBindingFactory.createBestJoinBinding(LOGGER, stunClient);
        try {
            resolveRendezvousShareCode(binding);
            transport = binding.transport();
        } catch (IOException exception) {
            if (!binding.relay() && P2pConstants.turnEnabled()) {
                LOGGER.debug("Safra join direct path patladi, TURN relay fallback denenecek: {}", exception.toString());
                binding.close();
                discardRendezvousSession();
                P2pTransportBinding turnBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join");
                try {
                    resolveRendezvousShareCode(turnBinding);
                    transport = turnBinding.transport();
                    return;
                } catch (IOException turnException) {
                    turnBinding.close();
                    discardRendezvousSession();
                    throw turnException;
                }
            }
            binding.close();
            discardRendezvousSession();
            throw exception;
        }
    }

    private void resolveRendezvousShareCode(P2pTransportBinding binding) throws IOException {
        rendezvousSession = SafraRendezvousClient.join(shareCode.rendezvousCode(), binding.publicEndpoints());
        quicLocalPort = binding.transport().getLocalPort();
        remoteAddress = rendezvousSession.hostAddress();
        tunnelToken = rendezvousSession.tunnelToken();
        remoteQuicPort = rendezvousSession.hostQuicPort();
        remoteQuicCertificate = rendezvousSession.hostQuicCertificate();
        if (tunnelToken == 0) {
            throw new IOException("Rendezvous sunucusu gecersiz tunel token'i dondurdu");
        }

        if (!binding.relay()) {
            P2pStunClient.DiscoveredEndpoint matchingLocalEndpoint = binding.stunEndpoints().get(P2pSockets.addressFamily(remoteAddress));
            if (matchingLocalEndpoint == null) {
                throw new IOException("Host ve joiner farkli IP ailesi kullaniyor ("
                    + binding.stunEndpoints().keySet() + " / "
                    + P2pSockets.addressFamily(remoteAddress) + ")");
            }

            if (samePublicIp(matchingLocalEndpoint.publicAddress(), remoteAddress)) {
                LOGGER.debug("Safra P2P host and joiner resolved to the same public IP {}; attempting NAT hairpin/self-connect path", remoteAddress.getAddress());
            }
        }

        if (remoteQuicPort > 0) {
            LOGGER.info("Safra rendezvous code {} resolved to {} with QUIC UDP {}",
                shareCode.rendezvousCode(), remoteAddress, remoteQuicPort);
        } else {
            LOGGER.debug("Safra P2P rendezvous code {} resolved to {}", shareCode.rendezvousCode(), remoteAddress);
        }
    }

    private boolean samePublicIp(InetSocketAddress joinerAddress, InetSocketAddress hostAddress) {
        if (joinerAddress == null || hostAddress == null) {
            return false;
        }

        InetAddress joinerInetAddress = joinerAddress.getAddress();
        InetAddress hostInetAddress = hostAddress.getAddress();
        return joinerInetAddress != null
            && hostInetAddress != null
            && joinerInetAddress.equals(hostInetAddress);
    }

    private void acceptLoop() {
        try (ServerSocket ignored = proxyServer) {
            Socket localSocket = proxyServer.accept();
            if (quicAdvertisedByHost() && !P2pQuicSupport.enabled()) {
                publishStatusMessage("Safra P2P QUIC kullanilamadi, UDP fallback kullaniliyor.");
                LOGGER.warn("Safra QUIC probe basarisiz; UDP fallback aktif: {}", P2pQuicSupport.unavailableReason());
            }
            if (canUseQuic()) {
                try {
                    startQuicBridge(localSocket);
                    return;
                } catch (IOException exception) {
                    LOGGER.warn("Safra QUIC connect basarisiz, reliable UDP tunnel fallback denenecek: {}", exception.toString());
                    publishStatusMessage("Safra P2P QUIC baglantisi kurulamadi, UDP fallback kullaniliyor.");
                    restoreDirectTransportAfterQuicFailure();
                }
            }
            startReliableTunnel(localSocket);
        } catch (IOException exception) {
            if (!closed) {
                LOGGER.debug("Proxy accept failed: {}", exception.toString());
                close();
            }
        }
    }

    private boolean canUseQuic() {
        return quicAdvertisedByHost()
            && P2pQuicSupport.enabled();
    }

    private boolean quicAdvertisedByHost() {
        return shareCode.isRendezvous()
            && remoteQuicPort > 0
            && remoteQuicCertificate != null
            && !remoteQuicCertificate.isBlank()
            && transport instanceof P2pDirectDatagramTransport;
    }

    private void startQuicBridge(Socket localSocket) throws IOException {
        if (remoteQuicPort < 1 || remoteAddress == null) {
            throw new IOException("Safra QUIC hedefi hazir degil");
        }

        if (transport != null && !transport.isClosed()) {
            transport.close();
            transport = null;
        }
        InetSocketAddress quicAddress = new InetSocketAddress(remoteAddress.getAddress(), remoteQuicPort);
        int[] localPorts = {quicLocalPort, quicLocalPort, 0};
        long[] retryDelaysMs = {0L, 500L, 1000L};
        IOException lastException = null;
        for (int attempt = 0; attempt < localPorts.length; attempt++) {
            if (retryDelaysMs[attempt] > 0L) {
                sleepQuietly(retryDelaysMs[attempt]);
            }
            try {
                LOGGER.info("Safra QUIC client dialing {}", quicAddress);
                P2pQuicSupport.bridgeClient(LOGGER, localSocket, remoteAddress, remoteQuicPort, localPorts[attempt], tunnelToken, remoteQuicCertificate);
                if (!closed) {
                    close();
                }
                return;
            } catch (IOException exception) {
                lastException = exception;
                if (attempt + 1 < localPorts.length) {
                    LOGGER.debug("Safra QUIC retry {} for {} after {}", attempt + 1, quicAddress, exception.toString());
                }
            }
        }
        throw new IOException("Safra QUIC connect to " + quicAddress + " failed", lastException);
    }

    private void restoreDirectTransportAfterQuicFailure() throws IOException {
        if (transport != null && !transport.isClosed()) {
            return;
        }

        transport = new P2pDirectDatagramTransport(P2pSockets.datagramSocket(quicLocalPort));
        P2pRuntime.start("safra-p2p-client-recv", this::receiveLoop);
    }

    private void startReliableTunnel(Socket localSocket) throws IOException {
        int connectionId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        LOGGER.debug("Safra P2P client accepted local Minecraft connection {}; opening UDP tunnel to {}", connectionId, remoteAddress);
        ReliableTunnelConnection connection = new ReliableTunnelConnection(
            LOGGER,
            "client",
            tunnelToken,
            connectionId,
            remoteAddress,
            localSocket,
            this::sendPacket,
            this::removeConnection,
            scheduler,
            true
        );
        connections.put(connectionId, connection);
        connection.start();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[P2pConstants.MAX_DATAGRAM_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                if (transport == null) {
                    return;
                }
                transport.receive(packet);
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.debug("Client UDP receive failed: {}", exception.toString());
                }
                return;
            }

            P2pPacket decoded = P2pPacket.decode(packet.getData(), packet.getLength());
            if (decoded == null || decoded.token() != tunnelToken) {
                continue;
            }

            ReliableTunnelConnection connection = connections.get(decoded.connectionId());
            if (connection != null) {
                connection.handlePacket(decoded);
            }
        }
    }

    private void sendPacket(P2pPacket packet, InetSocketAddress remoteAddress) {
        if (closed || transport == null || transport.isClosed()) {
            return;
        }

        byte[] encoded = packet.encode();
        try {
            transport.send(new DatagramPacket(encoded, encoded.length, remoteAddress));
        } catch (IOException exception) {
            LOGGER.debug("Client UDP send failed: {}", exception.toString());
        }
    }

    private void removeConnection(int connectionId) {
        connections.remove(connectionId);
        scheduler.schedule(this::closeIfIdle, 1L, TimeUnit.SECONDS);
    }

    private void closeIfIdle() {
        if (!closed && connections.isEmpty()) {
            close();
        }
    }

    private void sleepQuietly(long delayMs) throws IOException {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Safra QUIC retry interrupted", exception);
        }
    }

    private void discardRendezvousSession() {
        if (rendezvousSession == null) {
            return;
        }
        rendezvousSession.close();
        rendezvousSession = null;
    }

    private void publishStatusMessage(String message) {
        if (statusMessageSink == null || message == null || message.isBlank()) {
            return;
        }
        try {
            statusMessageSink.accept(message);
        } catch (RuntimeException exception) {
            LOGGER.debug("Safra P2P status mesaji yayinlanamadi: {}", exception.toString());
        }
    }
}
