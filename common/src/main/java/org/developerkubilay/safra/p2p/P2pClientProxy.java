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
        P2pTransportBinding binding = P2pUdpBindingFactory.createBestJoinBinding(LOGGER, stunClient);
        if (P2pConstants.forceDirectThenTurnRelay()) {
            LOGGER.info("Safra test modu join ilk binding relay={} endpoints={} stunFamilies={}",
                binding.relay(), binding.publicEndpoints(), binding.stunEndpoints().keySet());
        }
        try {
            resolveRendezvousShareCode(binding);
            transport = binding.transport();
        } catch (IOException exception) {
            if (!binding.relay()) {
                if (P2pConstants.forceDirectThenTurnRelay()) {
                    LOGGER.info("Safra test modu direct P2P yolunu bilincli olarak kesti; TURN fallback deneniyor");
                }
                LOGGER.debug("Safra join direct path patladi, TURN relay fallback denenecek: {}", exception.toString());
                P2pTransportBinding turnBinding = null;
                try {
                    java.util.Collection<InetSocketAddress> relayRequestEndpoints = java.util.List.of();
                    if (!P2pConstants.useApi30Rendezvous()) {
                        turnBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join");
                        relayRequestEndpoints = turnBinding.publicEndpoints();
                    }
                    SafraRendezvousClient.ResolvedRelay relay = rendezvousSession == null
                        ? null
                        : rendezvousSession.requestRelayFallback(relayRequestEndpoints);
                    if (relay != null && relay.address() != null) {
                        if (P2pConstants.forceDirectThenTurnRelay()) {
                            LOGGER.info("Safra test modu rendezvous relay cevabi alindi endpoint={} tunnelToken={}",
                                relay.address(), relay.tunnelToken());
                        }
                        try {
                            remoteAddress = relay.address();
                            if (relay.tunnelToken() != 0) {
                                tunnelToken = relay.tunnelToken();
                            }
                            if (turnBinding == null && relay.credentials() != null) {
                                turnBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join", relay.credentials());
                                if (P2pConstants.useApi30Rendezvous() && rendezvousSession != null) {
                                    relay = rendezvousSession.requestRelayFallback(turnBinding.publicEndpoints());
                                    if (relay != null && relay.address() != null) {
                                        remoteAddress = relay.address();
                                    }
                                }
                            }
                            if (turnBinding != null) {
                                transport = turnBinding.transport();
                                binding.close();
                            }
                            if (P2pConstants.forceDirectThenTurnRelay()) {
                                LOGGER.info("Safra test modu TURN relay ile devam ediyor");
                            }
                            return;
                        } catch (RuntimeException exception2) {
                            if (turnBinding != null) {
                                turnBinding.close();
                            }
                            throw exception2;
                        }
                    }
                } catch (IOException relayException) {
                    if (P2pConstants.forceDirectThenTurnRelay()) {
                        LOGGER.warn("Safra test modu rendezvous relay istegi basarisiz oldu: {}", relayException.toString());
                    }
                    if (turnBinding != null) {
                        turnBinding.close();
                        turnBinding = null;
                    }
                    LOGGER.debug("Safra join relay istegi basarisiz oldu, klasik TURN fallback deneniyor: {}", relayException.toString());
                }
                if (turnBinding != null) {
                    turnBinding.close();
                }

                if (P2pConstants.useApi30Rendezvous()) {
                    binding.close();
                    discardRendezvousSession();
                    throw exception;
                }

                binding.close();
                discardRendezvousSession();
                P2pTransportBinding classicTurnBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join");
                try {
                    if (P2pConstants.forceDirectThenTurnRelay()) {
                        LOGGER.info("Safra test modu klasik TURN fallback binding endpoints={}", classicTurnBinding.publicEndpoints());
                    }
                    resolveRendezvousShareCode(classicTurnBinding);
                    transport = classicTurnBinding.transport();
                    if (P2pConstants.forceDirectThenTurnRelay()) {
                        LOGGER.info("Safra test modu TURN relay ile devam ediyor");
                    }
                    return;
                } catch (IOException turnException) {
                    classicTurnBinding.close();
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
        tunnelToken = P2pConstants.useApi30Rendezvous()
            ? P2pShareCode.rendezvousTunnelToken(shareCode.rendezvousCode())
            : rendezvousSession.tunnelToken();
        if (P2pConstants.forceDirectThenTurnRelay()) {
            LOGGER.info("Safra test modu rendezvous resolve bindingRelay={} remoteAddress={} tunnelToken={} hostTcpPort={}",
                binding.relay(), remoteAddress, tunnelToken, rendezvousSession.hostTcpPort());
        }
        if (remoteAddress == null) {
            throw new IOException(binding.relay()
                ? "Rendezvous sunucusu relay adresi dondurmedi"
                : "Rendezvous sunucusu host adresi dondurmedi");
        }
        if (tunnelToken == 0) {
            throw new IOException("Rendezvous sunucusu gecersiz tunel token'i dondurdu");
        }

        if (!binding.relay()) {
            if (P2pConstants.useApi30Rendezvous() && remoteAddress == null) {
                throw new IOException("Api-3.0 host adresi henuz hazir degil; relay fallback denenecek");
            }
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
        if (P2pConstants.forceDirectThenTurnRelay()) {
            LOGGER.info("Safra test modu local bridge {} -> remote {} token={}", proxyServer.getLocalPort(), remoteAddress, tunnelToken);
        }
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
