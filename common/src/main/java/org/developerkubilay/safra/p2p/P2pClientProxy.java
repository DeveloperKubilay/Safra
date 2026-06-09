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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class P2pClientProxy implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pClientProxy.class);

    private final P2pShareCode shareCode;
    private final P2pStunClient stunClient = new P2pStunClient();
    private final Map<Integer, ReliableTunnelConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = P2pRuntime.schedulerPool(4);
    private final Runnable onClose;

    private P2pDatagramTransport transport;
    private ServerSocket proxyServer;
    private InetSocketAddress remoteAddress;
    private SafraRendezvousClient.JoinSession rendezvousSession;
    private int tunnelToken;
    private volatile boolean closed;

    public P2pClientProxy(P2pShareCode shareCode, Runnable onClose) {
        this.shareCode = shareCode;
        this.onClose = onClose;
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
        if (rendezvousSession != null) {
            SafraVoiceTransportManager.getInstance().clearJoinSession(rendezvousSession);
            rendezvousSession.close();
            rendezvousSession = null;
        }
        LOGGER.debug("Safra P2P client proxy closed for {}", remoteAddress);
        onClose.run();
    }

    private void resolveRendezvousShareCode() throws IOException {
        SafraRendezvousClient.SessionStatus sessionStatus = fetchSessionStatus();
        boolean preferRelay = sessionStatus != null
            && sessionStatus.active()
            && sessionStatus.relayReady()
            && !P2pConstants.forceDirectThenTurnRelay();
        P2pTransportBinding binding = preferRelay
            ? P2pUdpBindingFactory.createTurnBinding(LOGGER, "join")
            : P2pUdpBindingFactory.createBestJoinBinding(LOGGER, stunClient);
        try {
            resolveRendezvousShareCode(binding);
            transport = binding.transport();
        } catch (IOException exception) {
            if (!binding.relay()) {
                if (P2pConstants.forceDirectThenTurnRelay()) {
                    LOGGER.info("Safra test modu direct P2P yolunu bilincli olarak kesti; TURN fallback deneniyor");
                }
                LOGGER.debug("Safra join direct path patladi, TURN relay fallback denenecek: {}", exception.toString());
                try {
                    SafraRendezvousClient.ResolvedRelay relay = rendezvousSession == null
                        ? null
                        : rendezvousSession.requestRelayFallback();
                    if (relay != null && relay.address() != null) {
                        P2pTransportBinding turnBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join");
                        try {
                            remoteAddress = relay.address();
                            if (relay.tunnelToken() != 0) {
                                tunnelToken = relay.tunnelToken();
                            }
                            transport = turnBinding.transport();
                            binding.close();
                            if (P2pConstants.forceDirectThenTurnRelay()) {
                                LOGGER.info("Safra test modu TURN relay ile devam ediyor");
                            }
                            return;
                        } catch (RuntimeException exception2) {
                            turnBinding.close();
                            throw exception2;
                        }
                    }
                } catch (IOException relayException) {
                    LOGGER.debug("Safra join relay istegi basarisiz oldu, klasik TURN fallback deneniyor: {}", relayException.toString());
                }

                binding.close();
                discardRendezvousSession();
                P2pTransportBinding turnBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join");
                try {
                    resolveRendezvousShareCode(turnBinding);
                    transport = turnBinding.transport();
                    if (P2pConstants.forceDirectThenTurnRelay()) {
                        LOGGER.info("Safra test modu TURN relay ile devam ediyor");
                    }
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
        remoteAddress = rendezvousSession.hostAddress(binding.relay());
        tunnelToken = rendezvousSession.tunnelToken();
        if (remoteAddress == null) {
            throw new IOException(binding.relay()
                ? "Rendezvous sunucusu relay adresi dondurmedi"
                : "Rendezvous sunucusu host adresi dondurmedi");
        }
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
            if (P2pConstants.forceDirectThenTurnRelay()) {
                throw new IOException("Safra test modu direct P2P yolunu bilincli olarak kesti; TURN fallback denenecek");
            }
        }

        LOGGER.debug("Safra P2P rendezvous code {} resolved to {}", shareCode.rendezvousCode(), remoteAddress);
    }

    private SafraRendezvousClient.SessionStatus fetchSessionStatus() {
        try {
            return SafraRendezvousClient.fetchSessionStatus(shareCode.rendezvousCode());
        } catch (IOException exception) {
            LOGGER.debug("Safra join session status alinamadi: {}", exception.toString());
            return null;
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
            startReliableTunnel(localSocket);
        } catch (IOException exception) {
            if (!closed) {
                LOGGER.debug("Proxy accept failed: {}", exception.toString());
                close();
            }
        }
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

    private void discardRendezvousSession() {
        if (rendezvousSession == null) {
            return;
        }
        rendezvousSession.close();
        rendezvousSession = null;
    }

}
