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
import java.util.Set;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class P2pClientProxy implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pClientProxy.class);

    private final P2pShareCode shareCode;
    private final P2pStunClient stunClient = new P2pStunClient();
    private final Map<Integer, P2pKwikClientTunnel> connections = new ConcurrentHashMap<>();
    private final Set<Socket> pendingRetrySockets = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = P2pRuntime.singleScheduler();
    private final Runnable onClose;

    private volatile P2pDatagramTransport transport;
    private ServerSocket proxyServer;
    private InetSocketAddress remoteAddress;
    private SafraRendezvousClient.JoinSession rendezvousSession;
    private int tunnelToken;
    private volatile boolean relayTransportActive;
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
        LOGGER.debug("Safra P2P client proxy listening on {}:{} and dialing {}",
            proxyServer.getInetAddress().getHostAddress(), proxyServer.getLocalPort(), remoteAddress);

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
        connections.values().forEach(P2pKwikClientTunnel::close);
        connections.clear();
        pendingRetrySockets.clear();
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
                    LOGGER.debug("Safra join relay request failed: {}", relayException.toString());
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
        tunnelToken = P2pShareCode.rendezvousTunnelToken(shareCode.rendezvousCode());
        if (remoteAddress == null) {
            throw new IOException(binding.relay()
                ? "Rendezvous server did not return a relay address"
                : "Rendezvous server did not return a host address");
        }
        if (tunnelToken == 0) {
            throw new IOException("Rendezvous server returned an invalid tunnel token");
        }

        if (!binding.relay()) {
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
        while (!closed) {
            Socket localSocket;
            try {
                localSocket = proxyServer.accept();
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.debug("Proxy accept failed: {}", exception.toString());
                    close();
                }
                return;
            }
            startKwikTunnel(localSocket);
        }
    }

    private void startKwikTunnel(Socket localSocket) {
        if (shareCode.isRendezvous()) {
            pendingRetrySockets.add(localSocket);
        }
        if (relayTransportActive) {
            startKwikAttempt(localSocket, P2pConstants.KWIK_RELAY_TIMEOUT_MS, () -> finishKwik(localSocket));
            return;
        }
        AtomicInteger attempt = new AtomicInteger();
        startKwikAttempt(localSocket, P2pConstants.KWIK_DIRECT_FIRST_TIMEOUT_MS, () -> {
            if (shareCode.isRendezvous() && attempt.compareAndSet(0, 1)) {
                P2pRuntime.start("safra-kwik-direct-retry", () -> retryDirectKwik(localSocket, attempt));
            } else if (shareCode.isRendezvous() && attempt.compareAndSet(1, 2)) {
                P2pRuntime.start("safra-kwik-relay-fallback", () -> openRelayKwik(localSocket));
            } else {
                finishKwik(localSocket);
            }
        });
    }

    private void startKwikAttempt(Socket localSocket, long timeoutMs, Runnable failure) {
        int connectionId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        LOGGER.debug("Safra Kwik client local Minecraft connection {} aldı; {} adresine bağlanıyor (timeout={}ms)",
            connectionId, remoteAddress, timeoutMs);
        P2pKwikClientTunnel connection = new P2pKwikClientTunnel(
            LOGGER,
            tunnelToken,
            connectionId,
            localSocket,
            timeoutMs,
            packet -> sendPacket(packet, remoteAddress),
            () -> removeConnection(connectionId),
            failure,
            () -> pendingRetrySockets.remove(localSocket)
        );
        connections.put(connectionId, connection);
        connection.start();
    }

    private void retryDirectKwik(Socket localSocket, AtomicInteger attempt) {
        if (closed || rendezvousSession == null || relayTransportActive) {
            openRelayKwik(localSocket);
            return;
        }

        P2pTransportBinding freshBinding = null;
        try {
            freshBinding = P2pUdpBindingFactory.createDirectJoinBinding(stunClient);
            InetSocketAddress refreshedHostAddress = rendezvousSession.refreshDirect(freshBinding.publicEndpoints());
            if (refreshedHostAddress == null
                || !freshBinding.stunEndpoints().containsKey(P2pSockets.addressFamily(refreshedHostAddress))) {
                throw new IOException("Fresh direct endpoint and host use different IP families");
            }

            P2pDatagramTransport previousTransport = transport;
            transport = freshBinding.transport();
            remoteAddress = refreshedHostAddress;
            relayTransportActive = false;
            freshBinding = null;
            if (previousTransport != null && !previousTransport.isClosed()) {
                previousTransport.close();
            }
            LOGGER.info("Safra direct P2P ikinci Kwik denemesi başlatılıyor: {}", remoteAddress);
            startKwikAttempt(localSocket, P2pConstants.KWIK_DIRECT_SECOND_TIMEOUT_MS,
                () -> {
                    if (attempt.compareAndSet(1, 2)) {
                        P2pRuntime.start("safra-kwik-relay-fallback", () -> openRelayKwik(localSocket));
                    } else {
                        finishKwik(localSocket);
                    }
                });
        } catch (IOException | RuntimeException exception) {
            LOGGER.info("Safra direct P2P ikinci Kwik denemesi hazırlanamadı, TURN kullanılacak: {}", exception.toString());
            openRelayKwik(localSocket);
        } finally {
            if (freshBinding != null) {
                freshBinding.close();
            }
        }
    }

    private void openRelayKwik(Socket localSocket) {
        if (closed || rendezvousSession == null || P2pConstants.neverUseRelayServer()) {
            finishKwik(localSocket);
            return;
        }

        try {
            RelayRoute relayRoute = createRelayRoute();
            P2pDatagramTransport previousTransport = transport;
            transport = relayRoute.binding().transport();
            remoteAddress = relayRoute.address();
            tunnelToken = relayRoute.tunnelToken();
            relayTransportActive = true;
            if (previousTransport != null && !previousTransport.isClosed()) {
                previousTransport.close();
            }
            LOGGER.info("Safra TURN üzerinden Kwik denemesi başlatılıyor: {}", remoteAddress);
            startKwikAttempt(localSocket, P2pConstants.KWIK_RELAY_TIMEOUT_MS, () -> finishKwik(localSocket));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Safra TURN Kwik fallback kurulamadı", exception);
            finishKwik(localSocket);
        }
    }

    private void finishKwik(Socket localSocket) {
        pendingRetrySockets.remove(localSocket);
        closeQuietly(localSocket);
    }

    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[P2pConstants.MAX_DATAGRAM_SIZE];
        while (!closed) {
            P2pDatagramTransport activeTransport = transport;
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                if (activeTransport == null) {
                    return;
                }
                activeTransport.receive(packet);
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.debug("Client UDP receive failed: {}", exception.toString());
                    if (activeTransport != transport) {
                        continue;
                    }
                }
                return;
            }

            P2pPacket decoded = P2pPacket.decode(packet.getData(), packet.getLength());
            if (decoded == null || decoded.token() != tunnelToken) {
                continue;
            }

            if (decoded.type() == P2pPacket.Type.PUNCH) {
                InetSocketAddress senderAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                sendPacket(P2pPacket.punch(tunnelToken), senderAddress);
                continue;
            }

            P2pKwikClientTunnel connection = connections.get(decoded.connectionId());
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

    private RelayRoute createRelayRoute() throws IOException {
        if (rendezvousSession == null) {
            throw new IOException("Rendezvous session is not available for TURN fallback");
        }

        P2pTransportBinding relayBinding = null;
        try {
            SafraRendezvousClient.ResolvedRelay relay = rendezvousSession.requestRelayFallback(java.util.List.of());
            if (relay == null || relay.address() == null) {
                throw new IOException("Rendezvous server did not return a relay address");
            }
            if (relay.credentials() == null) {
                throw new IOException("Rendezvous server did not return TURN credentials");
            }

            relayBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join", relay.credentials());
            relay = rendezvousSession.requestRelayFallback(relayBinding.publicEndpoints());
            if (relay == null || relay.address() == null) {
                throw new IOException("Rendezvous server did not return a host relay address");
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
        if (!closed && connections.isEmpty() && pendingRetrySockets.isEmpty()) {
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

    private static InetSocketAddress preferredEndpoint(Collection<InetSocketAddress> endpoints) {
        if (endpoints == null) {
            return null;
        }
        InetSocketAddress fallback = null;
        for (InetSocketAddress endpoint : endpoints) {
            if (endpoint == null || endpoint.getAddress() == null) {
                continue;
            }
            if (fallback == null) {
                fallback = endpoint;
            }
            if ("ipv4".equals(P2pSockets.addressFamily(endpoint))) {
                return endpoint;
            }
        }
        return fallback;
    }

    private record RelayRoute(P2pTransportBinding binding, InetSocketAddress address, int tunnelToken) {
    }

}
