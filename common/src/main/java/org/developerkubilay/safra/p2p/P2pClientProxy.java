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

    private volatile P2pDatagramTransport transport;
    private ServerSocket proxyServer;
    private InetSocketAddress remoteAddress;
    private SafraRendezvousClient.JoinSession rendezvousSession;
    private int tunnelToken;
    private volatile boolean relayTransportActive;
    private volatile boolean directRetryAttempted;
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
                LOGGER.debug("Safra voicechat is not available; keeping join rendezvous session for direct-to-TURN fallback");
            }
        } else {
            transport = new P2pDirectDatagramTransport(P2pSockets.datagramSocket());
            InetAddress remoteInetAddress = InetAddress.getByName(shareCode.host());
            remoteAddress = new InetSocketAddress(remoteInetAddress, shareCode.port());
            tunnelToken = shareCode.token();
        }

        proxyServer = new ServerSocket(0, 16, P2pSockets.loopbackAddress());

        P2pRuntime.start("safra-p2p-client-recv", this::receiveLoop);
        P2pRuntime.start("safra-p2p-client-accept", this::acceptLoop);
        return proxyServer.getLocalPort();
    }

    public boolean usesRendezvousShareCode() {
        return shareCode.isRendezvous();
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
        try {
            resolveRendezvousShareCode(binding);
            transport = binding.transport();
            relayTransportActive = binding.relay();
        } catch (IOException exception) {
            if (!binding.relay()) {
                LOGGER.debug("Safra join direct path failed, trying TURN relay fallback: {}", exception.toString());
                try {
                    RelayRoute relayRoute = createRelayRoute();
                    transport = relayRoute.binding().transport();
                    relayTransportActive = true;
                    remoteAddress = relayRoute.address();
                    tunnelToken = relayRoute.tunnelToken();
                    binding.close();
                    return;
                } catch (IOException relayException) {
                    LOGGER.debug("Safra join relay request failed, trying classic TURN fallback: {}", relayException.toString());
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
                    resolveRendezvousShareCode(classicTurnBinding);
                    transport = classicTurnBinding.transport();
                    relayTransportActive = true;
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
        if (remoteAddress == null) {
            throw new IOException(binding.relay()
                ? "Rendezvous server did not return a relay address"
                : "Rendezvous server did not return a host address");
        }
        if (tunnelToken == 0) {
            throw new IOException("Rendezvous server returned an invalid tunnel token");
        }

        if (!binding.relay()) {
            if (P2pConstants.useApi30Rendezvous() && remoteAddress == null) {
                throw new IOException("Host address is not ready yet; relay fallback will be attempted");
            }
            P2pStunClient.DiscoveredEndpoint matchingLocalEndpoint = binding.stunEndpoints().get(P2pSockets.addressFamily(remoteAddress));
            if (matchingLocalEndpoint == null) {
                throw new IOException("Host and joiner are using different IP families ("
                    + binding.stunEndpoints().keySet() + " / "
                    + P2pSockets.addressFamily(remoteAddress) + ")");
            }

            if (samePublicIp(matchingLocalEndpoint.publicAddress(), remoteAddress)) {
                LOGGER.debug("Safra P2P host and joiner resolved to the same public IP {}; attempting NAT hairpin/self-connect path", remoteAddress.getAddress());
            }
            if (P2pConstants.forceDirectThenTurnRelay()) {
                throw new IOException("Safra test mode intentionally blocked the direct P2P path; TURN fallback will be attempted");
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
            true,
            relayTransportActive ? null : this::fallbackOpenToRelay
        );
        connections.put(connectionId, connection);
        connection.start();
    }

    private void receiveLoop() {
        P2pDatagramTransport activeTransport = transport;
        byte[] buffer = new byte[P2pConstants.MAX_DATAGRAM_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                if (activeTransport == null) {
                    return;
                }
                activeTransport.receive(packet);
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

    private void fallbackOpenToRelay(ReliableTunnelConnection connection) {
        if (closed || rendezvousSession == null) {
            connection.failOpenFallback();
            return;
        }

        if (!directRetryAttempted && P2pConstants.useApi30Rendezvous()) {
            directRetryAttempted = true;
            try {
                if (retryOpenWithFreshDirectBinding(connection)) {
                    return;
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.info("Safra fresh direct P2P retry could not be prepared, TURN will be used: {}", exception.toString());
            }
        }

        if (P2pConstants.neverUseRelayServer()) {
            connection.failOpenFallback();
            return;
        }

        LOGGER.info("Safra direct P2P tuneli acilmadi, TURN fallback baslatiliyor: {}", remoteAddress);
        try {
            RelayRoute relayRoute = createRelayRoute();
            if (closed || connection.isOpened()) {
                relayRoute.binding().close();
                return;
            }
            P2pDatagramTransport previousTransport = transport;
            transport = relayRoute.binding().transport();
            relayTransportActive = true;
            remoteAddress = relayRoute.address();
            tunnelToken = relayRoute.tunnelToken();
            P2pRuntime.start("safra-p2p-client-relay-recv", this::receiveLoop);
            connection.retryOpen(remoteAddress);
            if (previousTransport != null && previousTransport != transport && !previousTransport.isClosed()) {
                previousTransport.close();
            }
            LOGGER.info("Safra TURN fallback hazir, tunel yeniden deneniyor: {}", remoteAddress);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Safra TURN fallback kurulamadı", exception);
            connection.failOpenFallback();
        }
    }

    private boolean retryOpenWithFreshDirectBinding(ReliableTunnelConnection connection) throws IOException {
        P2pTransportBinding freshBinding = P2pUdpBindingFactory.createDirectJoinBinding(LOGGER, stunClient);
        boolean switched = false;
        try {
            if (closed || connection.isOpened()) {
                return true;
            }
            InetSocketAddress refreshedHostAddress = rendezvousSession.refreshDirect(freshBinding.publicEndpoints());
            if (refreshedHostAddress == null) {
                throw new IOException("Rendezvous server did not refresh the direct host address");
            }
            if (!freshBinding.stunEndpoints().containsKey(P2pSockets.addressFamily(refreshedHostAddress))) {
                throw new IOException("Fresh direct endpoint and host use different IP families");
            }
            if (closed || connection.isOpened()) {
                return true;
            }

            P2pDatagramTransport previousTransport = transport;
            transport = freshBinding.transport();
            relayTransportActive = false;
            remoteAddress = refreshedHostAddress;
            P2pRuntime.start("safra-p2p-client-direct-retry-recv", this::receiveLoop);
            connection.retryDirectOpen(remoteAddress);
            switched = true;
            if (previousTransport != null && previousTransport != transport && !previousTransport.isClosed()) {
                previousTransport.close();
            }
            LOGGER.info("Safra P2P fresh STUN mapping ready, direct tunnel is being retried: {}", remoteAddress);
            return true;
        } finally {
            if (!switched) {
                freshBinding.close();
            }
        }
    }

    private RelayRoute createRelayRoute() throws IOException {
        if (rendezvousSession == null) {
            throw new IOException("Rendezvous session is not available for TURN fallback");
        }

        P2pTransportBinding relayBinding = null;
        try {
            if (!P2pConstants.useApi30Rendezvous()) {
                relayBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join");
            }

            SafraRendezvousClient.ResolvedRelay relay = rendezvousSession.requestRelayFallback(
                relayBinding == null ? java.util.List.of() : relayBinding.publicEndpoints()
            );
            if (relay == null || relay.address() == null) {
                throw new IOException("Rendezvous server did not return a relay address");
            }

            if (relayBinding == null) {
                if (relay.credentials() == null) {
                    throw new IOException("Rendezvous server did not return TURN credentials");
                }
                relayBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join", relay.credentials());
                relay = rendezvousSession.requestRelayFallback(relayBinding.publicEndpoints());
                if (relay == null || relay.address() == null) {
                    throw new IOException("Rendezvous server did not return a host relay address");
                }
            }

            int relayTunnelToken = relay.tunnelToken() == 0 ? tunnelToken : relay.tunnelToken();
            RelayRoute route = new RelayRoute(relayBinding, relay.address(), relayTunnelToken);
            relayBinding = null;
            return route;
        } finally {
            if (relayBinding != null) {
                relayBinding.close();
            }
        }
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

    private record RelayRoute(P2pTransportBinding binding, InetSocketAddress address, int tunnelToken) {
    }

}
