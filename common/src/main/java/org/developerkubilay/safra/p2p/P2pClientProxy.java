package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;
import org.developerkubilay.safra.p2p.transport.P2pDirectDatagramTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class P2pClientProxy implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pClientProxy.class);
    private static final long SILENCE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(7L);
    private static final long PING_SILENCE_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long MIGRATE_PROBE_TIMEOUT_MS = 4_000L;

    private static volatile long activeConnectStartedAtNanos;
    private static volatile P2pClientProxy currentConnectingProxy;

    private final P2pShareCode shareCode;
    private final P2pStunClient stunClient = new P2pStunClient();
    private final Map<Integer, P2pKwikClientTunnel> connections = new ConcurrentHashMap<>();
    private final Set<Socket> pendingRetrySockets = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = P2pRuntime.singleScheduler();
    private final AtomicBoolean migratingEndpoint = new AtomicBoolean();
    private final Runnable onClose;

    private volatile P2pDatagramTransport transport;
    private ServerSocket proxyServer;
    private volatile InetSocketAddress remoteAddress;
    private volatile SafraRendezvousClient.JoinSession rendezvousSession;
    private volatile int tunnelToken;
    private volatile boolean relayTransportActive;
    private volatile boolean tunnelEstablished;
    private volatile boolean closed;
    private volatile long lastPacketReceivedNanos;
    private volatile Collection<InetSocketAddress> directPublicEndpoints = List.of();
    private volatile P2pErrorKind failureKind = P2pErrorKind.OTHER;
    private volatile String worldCode;

    public P2pClientProxy(P2pShareCode shareCode, Runnable onClose) {
        this.shareCode = shareCode;
        this.onClose = onClose;
        if (activeConnectStartedAtNanos == 0L) {
            activeConnectStartedAtNanos = System.nanoTime();
        }
        currentConnectingProxy = this;
    }

    public static void beginConnectAttempt() {
        activeConnectStartedAtNanos = System.nanoTime();
        currentConnectingProxy = null;
    }

    public static void clearConnectAttempt() {
        activeConnectStartedAtNanos = 0L;
        currentConnectingProxy = null;
    }

    public static String currentConnectingStageTranslationKey() {
        long startedAt = activeConnectStartedAtNanos;
        if (startedAt == 0L) {
            return null;
        }
        P2pClientProxy proxy = currentConnectingProxy;
        if (proxy != null && (proxy.closed || proxy.tunnelEstablished)) {
            return null;
        }
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
        if (elapsedSeconds >= 12L) {
            return "safra.p2p.connect.wait_longer";
        }
        if (elapsedSeconds >= 5L) {
            return "safra.p2p.connect.wait";
        }
        return null;
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

        lastPacketReceivedNanos = System.nanoTime();
        P2pDatagramTransport initialTransport = transport;
        P2pRuntime.start("safra-p2p-client-recv", () -> receiveLoop(initialTransport));
        P2pRuntime.start("safra-p2p-client-accept", this::acceptLoop);
        if (SafraBuildInfo.diagnostics()) {
            scheduler.scheduleAtFixedRate(this::logLinkQuality, 5L, 5L, TimeUnit.SECONDS);
        }
        if (shareCode.isRendezvous()) {
            scheduler.scheduleWithFixedDelay(this::checkConnectionLiveness, 2000L, 1000L, TimeUnit.MILLISECONDS);
        }
        return proxyServer.getLocalPort();
    }

    public boolean usesRendezvousShareCode() {
        return shareCode.isRendezvous();
    }

    public String worldCode() {
        return worldCode;
    }

    /** Why the last join attempt gave up, when the reason was clear enough to tell the player. */
    public P2pErrorKind failureKind() {
        return failureKind;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (currentConnectingProxy == this) {
            clearConnectAttempt();
        }
        connections.values().forEach(P2pKwikClientTunnel::close);
        connections.clear();
        pendingRetrySockets.forEach(this::closeQuietly);
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
            directPublicEndpoints = binding.relay() ? List.of() : binding.publicEndpoints();
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
        worldCode = rendezvousSession.worldCode();
        remoteAddress = rendezvousSession.hostAddress(binding.relay());
        tunnelToken = rendezvousSession.tunnelToken();
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

    private void logLinkQuality() {
        for (P2pKwikClientTunnel connection : connections.values()) {
            try {
                connection.logLinkQuality();
            } catch (RuntimeException exception) {
                LOGGER.debug("Safra could not read the Kwik statistics: {}", exception.toString());
            }
        }
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
            MonitoredClientSocket monitored = new MonitoredClientSocket(localSocket, this::handleLocalClientCancel);
            monitored.startWatching();
            startKwikTunnel(monitored);
        }
    }

    private void handleLocalClientCancel(Socket localSocket) {
        for (P2pKwikClientTunnel tunnel : connections.values()) {
            if (tunnel.ownsSocket(localSocket)) {
                tunnel.close();
            }
        }
        finishKwik(localSocket);
        closeIfIdle();
    }

    private static boolean isLocalSocketClosed(Socket localSocket) {
        return localSocket == null
            || localSocket.isClosed()
            || (localSocket instanceof MonitoredClientSocket monitored && monitored.isRemoteClosed());
    }

    private void startKwikTunnel(Socket localSocket) {
        if (!shareCode.isRendezvous()) {
            startKwikAttempt(localSocket, P2pConstants.KWIK_DIRECT_ATTEMPT_TIMEOUT_MS, () -> finishKwik(localSocket));
            return;
        }

        pendingRetrySockets.add(localSocket);
        if (relayTransportActive) {
            startKwikAttempt(localSocket, P2pConstants.KWIK_RELAY_TIMEOUT_MS, () -> finishKwik(localSocket));
            return;
        }
        startKwikAttempt(localSocket, P2pConstants.KWIK_DIRECT_ATTEMPT_TIMEOUT_MS,
            () -> P2pRuntime.start("safra-kwik-direct-retry", () -> retryDirectKwik(localSocket)));
    }

    private synchronized void startKwikAttempt(Socket localSocket, long timeoutMs, Runnable failure) {
        if (closed) {
            finishKwik(localSocket);
            return;
        }
        int connectionId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        LOGGER.debug("Safra Kwik client accepted local Minecraft connection {}; dialing {} (timeout={}ms)",
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
            () -> {
                tunnelEstablished = true;
                activeConnectStartedAtNanos = 0L;
                pendingRetrySockets.remove(localSocket);
            }
        );
        connections.put(connectionId, connection);
        connection.start();
    }

    private void retryDirectKwik(Socket localSocket) {
        if (closed || isLocalSocketClosed(localSocket)) {
            finishKwik(localSocket);
            return;
        }
        if (rendezvousSession == null || relayTransportActive) {
            openRelayKwik(localSocket);
            return;
        }

        P2pTransportBinding freshBinding = null;
        try {
            freshBinding = P2pUdpBindingFactory.createDirectJoinBinding(stunClient);
            if (closed || isLocalSocketClosed(localSocket)) {
                finishKwik(localSocket);
                return;
            }
            InetSocketAddress refreshedHostAddress = rendezvousSession.refreshDirect(freshBinding.publicEndpoints());
            if (refreshedHostAddress == null
                || !freshBinding.stunEndpoints().containsKey(P2pSockets.addressFamily(refreshedHostAddress))) {
                throw new IOException("Fresh direct endpoint and host use different IP families");
            }

            activateRoute(new RelayRoute(freshBinding, refreshedHostAddress, tunnelToken));
            directPublicEndpoints = freshBinding.publicEndpoints();
            freshBinding = null;
            if (SafraBuildInfo.diagnostics()) {
                LOGGER.info("Safra starting the second direct Kwik attempt against {}", remoteAddress);
            } else {
                LOGGER.info("Safra starting the second direct Kwik attempt");
            }
            startKwikAttempt(localSocket, P2pConstants.KWIK_DIRECT_ATTEMPT_TIMEOUT_MS,
                () -> P2pRuntime.start("safra-kwik-relay-fallback", () -> openRelayKwik(localSocket)));
        } catch (IOException | RuntimeException exception) {
            LOGGER.info("Safra could not prepare the second direct Kwik attempt, falling back to TURN: {}", exception.toString());
            openRelayKwik(localSocket);
        } finally {
            if (freshBinding != null) {
                freshBinding.close();
            }
        }
    }

    private void openRelayKwik(Socket localSocket) {
        if (closed || isLocalSocketClosed(localSocket) || rendezvousSession == null || P2pConstants.neverUseRelayServer()) {
            finishKwik(localSocket);
            return;
        }

        try {
            SafraRendezvousClient.ResolvedRelay relay = rendezvousSession.requestRelayFallback(List.of());
            if (relay == null || relay.address() == null) {
                throw new IOException("Rendezvous server did not return a relay address");
            }
            if (closed || isLocalSocketClosed(localSocket)) {
                finishKwik(localSocket);
                return;
            }
            if (tryStartDirectToRelayKwik(localSocket, relay)) {
                return;
            }
            openClientTurnKwik(localSocket, relay);
        } catch (IOException | RuntimeException exception) {
            if (closed || isLocalSocketClosed(localSocket)) {
                finishKwik(localSocket);
                return;
            }
            LOGGER.warn("Safra could not set up the TURN Kwik fallback", exception);
            failureKind = P2pErrorKind.classify(exception);
            finishKwik(localSocket);
        }
    }

    private boolean tryStartDirectToRelayKwik(Socket localSocket, SafraRendezvousClient.ResolvedRelay relay) throws IOException {
        InetSocketAddress relayAddress = relay.address();
        P2pDatagramTransport active = transport;
        Collection<InetSocketAddress> endpoints = directPublicEndpoints;
        boolean canReuseActiveDirect = !relayTransportActive
            && active != null
            && !active.isClosed()
            && hasMatchingAddressFamily(endpoints, relayAddress);
        P2pTransportBinding freshBinding = null;
        try {
            if (!canReuseActiveDirect) {
                try {
                    freshBinding = P2pUdpBindingFactory.createDirectJoinBinding(stunClient);
                } catch (IOException exception) {
                    LOGGER.debug("Safra could not prepare direct UDP socket for host TURN: {}", exception.toString());
                    return false;
                }
                endpoints = freshBinding.publicEndpoints();
                if (!hasMatchingAddressFamily(endpoints, relayAddress)) {
                    return false;
                }
            }

            if (closed || isLocalSocketClosed(localSocket)) {
                finishKwik(localSocket);
                return true;
            }

            SafraRendezvousClient.ResolvedRelay updated = rendezvousSession.requestRelayFallback(endpoints);
            InetSocketAddress targetAddress = updated != null && updated.address() != null ? updated.address() : relayAddress;
            int relayTunnelToken = updated != null && updated.tunnelToken() != 0
                ? updated.tunnelToken()
                : (relay.tunnelToken() == 0 ? tunnelToken : relay.tunnelToken());

            if (freshBinding != null) {
                activateRoute(new RelayRoute(freshBinding, targetAddress, relayTunnelToken));
                directPublicEndpoints = endpoints;
                freshBinding = null;
            } else {
                remoteAddress = targetAddress;
                tunnelToken = relayTunnelToken;
            }
            relayTransportActive = true;

            if (SafraBuildInfo.diagnostics()) {
                LOGGER.info("Safra starting a Kwik attempt directly to host TURN {}", remoteAddress);
            } else {
                LOGGER.info("Safra starting a Kwik attempt directly to host TURN");
            }
            startKwikAttempt(localSocket, P2pConstants.KWIK_DIRECT_TO_RELAY_TIMEOUT_MS,
                () -> P2pRuntime.start("safra-kwik-client-turn-fallback", () -> openClientTurnKwik(localSocket, relay)));
            return true;
        } finally {
            if (freshBinding != null) {
                freshBinding.close();
            }
        }
    }

    private void openClientTurnKwik(Socket localSocket, SafraRendezvousClient.ResolvedRelay relay) {
        if (closed || isLocalSocketClosed(localSocket) || rendezvousSession == null || P2pConstants.neverUseRelayServer()) {
            finishKwik(localSocket);
            return;
        }

        try {
            RelayRoute relayRoute = createClientTurnRoute(relay);
            if (closed || isLocalSocketClosed(localSocket)) {
                relayRoute.binding().close();
                finishKwik(localSocket);
                return;
            }
            activateRoute(relayRoute);
            if (SafraBuildInfo.diagnostics()) {
                LOGGER.info("Safra starting a Kwik attempt over client TURN against {}", remoteAddress);
            } else {
                LOGGER.info("Safra starting a Kwik attempt over client TURN");
            }
            startKwikAttempt(localSocket, P2pConstants.KWIK_RELAY_TIMEOUT_MS, () -> finishKwik(localSocket));
        } catch (IOException | RuntimeException exception) {
            if (closed || isLocalSocketClosed(localSocket)) {
                finishKwik(localSocket);
                return;
            }
            LOGGER.warn("Safra could not set up the client TURN Kwik fallback", exception);
            failureKind = P2pErrorKind.classify(exception);
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

    private void checkConnectionLiveness() {
        if (!closed && pendingRetrySockets.isEmpty()
            && System.nanoTime() - lastPacketReceivedNanos >= PING_SILENCE_NANOS) {
            connections.values().forEach(P2pKwikClientTunnel::ping);
        }
        if (!needsRecovery()) {
            return;
        }
        if (!migratingEndpoint.compareAndSet(false, true)) {
            return;
        }
        P2pRuntime.start("safra-p2p-client-migrate", this::migrateEndpoint);
    }

    private void migrateEndpoint() {
        try {
            if (!relayTransportActive && needsRecovery()) {
                P2pTransportBinding binding = null;
                try {
                    binding = P2pUdpBindingFactory.createDirectJoinBinding(stunClient);
                    if (!needsRecovery()) {
                        return;
                    }
                    InetSocketAddress address = rendezvousSession.refreshDirect(binding.publicEndpoints());
                    if (address != null && binding.stunEndpoints().containsKey(P2pSockets.addressFamily(address))
                        && probeDirectRoute(binding, address, MIGRATE_PROBE_TIMEOUT_MS) && needsRecovery()) {
                        activateRoute(new RelayRoute(binding, address, tunnelToken));
                        directPublicEndpoints = binding.publicEndpoints();
                        announceRoute();
                        return;
                    }
                } catch (IOException | RuntimeException exception) {
                    LOGGER.debug("Safra direct recovery attempt failed: {}", exception.toString());
                } finally {
                    if (binding != null && binding.transport() != transport) {
                        binding.close();
                    }
                }
            }
            if (needsRecovery() && !P2pConstants.neverUseRelayServer()) {
                SafraRendezvousClient.ResolvedRelay relay = rendezvousSession.requestRelayFallback(List.of());
                if (relay != null && relay.address() != null && needsRecovery()) {
                    P2pTransportBinding directRelayBinding = null;
                    try {
                        directRelayBinding = P2pUdpBindingFactory.createDirectJoinBinding(stunClient);
                        if (needsRecovery() && hasMatchingAddressFamily(directRelayBinding.publicEndpoints(), relay.address())) {
                            SafraRendezvousClient.ResolvedRelay updated =
                                rendezvousSession.requestRelayFallback(directRelayBinding.publicEndpoints());
                            InetSocketAddress targetAddress = updated != null && updated.address() != null
                                ? updated.address() : relay.address();
                            int relayToken = updated != null && updated.tunnelToken() != 0
                                ? updated.tunnelToken() : (relay.tunnelToken() == 0 ? tunnelToken : relay.tunnelToken());
                            if (probeDirectRoute(directRelayBinding, targetAddress, MIGRATE_PROBE_TIMEOUT_MS) && needsRecovery()) {
                                activateRoute(new RelayRoute(directRelayBinding, targetAddress, relayToken));
                                relayTransportActive = true;
                                directPublicEndpoints = directRelayBinding.publicEndpoints();
                                announceRoute();
                                return;
                            }
                        }
                    } catch (IOException | RuntimeException exception) {
                        LOGGER.debug("Safra direct-to-host TURN recovery failed: {}", exception.toString());
                    } finally {
                        if (directRelayBinding != null && directRelayBinding.transport() != transport) {
                            directRelayBinding.close();
                        }
                    }
                }
                if (needsRecovery()) {
                    RelayRoute route = relay != null && relay.credentials() != null
                        ? createClientTurnRoute(relay)
                        : createRelayRoute();
                    try {
                        if (needsRecovery()) {
                            activateRoute(route);
                            announceRoute();
                        }
                    } finally {
                        if (route.binding().transport() != transport) {
                            route.binding().close();
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Safra could not recover its active route: {}", exception.toString());
        } finally {
            migratingEndpoint.set(false);
        }
    }

    private boolean needsRecovery() {
        P2pDatagramTransport active = transport;
        long timeout = connections.values().stream().mapToLong(P2pKwikClientTunnel::recoveryTimeoutNanos)
            .max().orElse(SILENCE_TIMEOUT_NANOS);
        return !closed && rendezvousSession != null && !connections.isEmpty() && pendingRetrySockets.isEmpty()
            && active != null && (active.isClosed() || System.nanoTime() - lastPacketReceivedNanos >= timeout);
    }

    private boolean probeDirectRoute(P2pTransportBinding binding, InetSocketAddress address) throws IOException {
        return probeDirectRoute(binding, address, P2pConstants.KWIK_DIRECT_ATTEMPT_TIMEOUT_MS);
    }

    private boolean probeDirectRoute(P2pTransportBinding binding, InetSocketAddress address, long timeoutMs) throws IOException {
        P2pDirectDatagramTransport direct = (P2pDirectDatagramTransport) binding.transport();
        direct.socket().setSoTimeout(500);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        byte[] buffer = new byte[P2pConstants.MAX_DATAGRAM_SIZE];
        try {
            while (needsRecovery() && System.nanoTime() - deadline < 0L) {
                for (int connectionId : connections.keySet()) {
                    byte[] request = P2pPacket.quicOpen(tunnelToken, connectionId).encode();
                    direct.send(new DatagramPacket(request, request.length, address));
                }
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    direct.receive(response);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                P2pPacket packet = P2pPacket.decode(buffer, response.getLength());
                if (packet != null && packet.token() == tunnelToken && address.equals(response.getSocketAddress())
                    && packet.type() == P2pPacket.Type.QUIC_CERTIFICATE) {
                    P2pKwikClientTunnel connection = connections.get(packet.connectionId());
                    if (connection != null && connection.matchesCertificate(packet.payload())) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            direct.socket().setSoTimeout(0);
        }
    }

    private synchronized void activateRoute(RelayRoute route) throws IOException {
        if (closed) {
            route.binding().close();
            throw new IOException("Safra client proxy is closed");
        }
        P2pDatagramTransport previous = transport;
        transport = route.binding().transport();
        remoteAddress = route.address();
        tunnelToken = route.tunnelToken();
        relayTransportActive = route.binding().relay();
        if (relayTransportActive) {
            directPublicEndpoints = List.of();
        }
        lastPacketReceivedNanos = System.nanoTime();
        P2pDatagramTransport active = transport;
        P2pRuntime.start("safra-p2p-client-recv", () -> receiveLoop(active));
        if (previous != null && previous != active) {
            previous.close();
        }
    }

    private void announceRoute() {
        for (int connectionId : connections.keySet()) {
            sendPacket(P2pPacket.quicOpen(tunnelToken, connectionId), remoteAddress);
            scheduler.schedule(() -> sendPacket(P2pPacket.quicOpen(tunnelToken, connectionId), remoteAddress), 250L, TimeUnit.MILLISECONDS);
        }
        LOGGER.info("Safra recovered its tunnel over {}", relayTransportActive ? "TURN" : "direct UDP");
    }

    private void receiveLoop(P2pDatagramTransport activeTransport) {
        byte[] buffer = new byte[65535];
        while (!closed && activeTransport == transport) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                if (activeTransport == null) {
                    return;
                }
                activeTransport.receive(packet);
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.debug("Client UDP receive failed: {}", exception.toString());
                    if (activeTransport == transport && activeTransport != null && !activeTransport.isClosed()) {
                        continue;
                    }
                }
                return;
            }

            if (activeTransport != transport) {
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
                if (decoded.type() == P2pPacket.Type.QUIC_DATA) {
                    lastPacketReceivedNanos = System.nanoTime();
                }
                connection.handlePacket(decoded);
            }
        }
    }

    private void sendPacket(P2pPacket packet, InetSocketAddress remoteAddress) {
        P2pDatagramTransport active = transport;
        if (closed || active == null || active.isClosed()) {
            return;
        }

        byte[] encoded = packet.encode();
        try {
            active.send(new DatagramPacket(encoded, encoded.length, remoteAddress));
        } catch (IOException exception) {
            LOGGER.debug("Client UDP send failed: {}", exception.toString());
        }
    }

    private synchronized void removeConnection(int connectionId) {
        connections.remove(connectionId);
        if (!closed) {
            scheduler.schedule(this::closeIfIdle, 1L, TimeUnit.SECONDS);
        }
    }

    private RelayRoute createRelayRoute() throws IOException {
        if (rendezvousSession == null) {
            throw new IOException("Rendezvous session is not available for TURN fallback");
        }

        SafraRendezvousClient.ResolvedRelay relay = rendezvousSession.requestRelayFallback(List.of());
        if (relay == null || relay.address() == null) {
            throw new IOException("Rendezvous server did not return a relay address");
        }
        return createClientTurnRoute(relay);
    }

    private RelayRoute createClientTurnRoute(SafraRendezvousClient.ResolvedRelay relay) throws IOException {
        if (rendezvousSession == null) {
            throw new IOException("Rendezvous session is not available for TURN fallback");
        }
        if (relay == null || relay.credentials() == null) {
            throw new IOException("Rendezvous server did not return TURN credentials");
        }

        P2pTransportBinding relayBinding = null;
        try {
            relayBinding = P2pUdpBindingFactory.createTurnBinding(LOGGER, "join", relay.credentials());
            SafraRendezvousClient.ResolvedRelay updated = rendezvousSession.requestRelayFallback(relayBinding.publicEndpoints());
            if (updated == null || updated.address() == null) {
                throw new IOException("Rendezvous server did not return a host relay address");
            }

            int relayTunnelToken = updated.tunnelToken() == 0 ? tunnelToken : updated.tunnelToken();
            RelayRoute route = new RelayRoute(relayBinding, updated.address(), relayTunnelToken);
            relayBinding = null;
            return route;
        } finally {
            if (relayBinding != null) {
                relayBinding.close();
            }
        }
    }

    private static boolean hasMatchingAddressFamily(Collection<InetSocketAddress> endpoints, InetSocketAddress target) {
        if (endpoints == null || endpoints.isEmpty() || target == null) {
            return false;
        }
        P2pSockets.AddressFamily targetFamily = P2pSockets.addressFamily(target);
        for (InetSocketAddress endpoint : endpoints) {
            if (P2pSockets.addressFamily(endpoint) == targetFamily) {
                return true;
            }
        }
        return false;
    }

    private synchronized void closeIfIdle() {
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

    private record RelayRoute(P2pTransportBinding binding, InetSocketAddress address, int tunnelToken) {
    }

    static final class MonitoredClientSocket extends Socket {
        private final Socket delegate;
        private final java.util.function.Consumer<Socket> onRemoteDisconnect;
        private final ByteArrayOutputStream prefetched = new ByteArrayOutputStream();
        private final Object streamLock = new Object();
        private volatile boolean watching = true;
        private volatile boolean remoteClosed;
        private Thread watcherThread;
        private InputStream exposedInputStream;

        MonitoredClientSocket(Socket delegate, java.util.function.Consumer<Socket> onRemoteDisconnect) {
            this.delegate = delegate;
            this.onRemoteDisconnect = onRemoteDisconnect;
        }

        void startWatching() {
            watcherThread = P2pRuntime.start("safra-p2p-local-watch", this::watchLoop);
        }

        boolean isRemoteClosed() {
            return remoteClosed;
        }

        private void watchLoop() {
            byte[] buffer = new byte[1024];
            try {
                delegate.setSoTimeout(50);
                InputStream in = delegate.getInputStream();
                while (watching && !delegate.isClosed()) {
                    try {
                        int read = in.read(buffer);
                        if (read < 0) {
                            remoteClosed = true;
                            if (watching) {
                                onRemoteDisconnect.accept(this);
                            }
                            return;
                        }
                        if (read > 0) {
                            synchronized (streamLock) {
                                if (!watching) {
                                    prefetched.write(buffer, 0, read);
                                    return;
                                }
                                prefetched.write(buffer, 0, read);
                                if (prefetched.size() >= 64 * 1024) {
                                    return;
                                }
                            }
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (IOException exception) {
                if (watching) {
                    remoteClosed = true;
                    onRemoteDisconnect.accept(this);
                }
            }
        }

        @Override
        public InputStream getInputStream() throws IOException {
            synchronized (streamLock) {
                if (exposedInputStream != null) {
                    return exposedInputStream;
                }
                watching = false;
            }
            Thread thread = watcherThread;
            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(250L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                delegate.setSoTimeout(0);
            } catch (SocketException ignored) {
            }
            synchronized (streamLock) {
                if (exposedInputStream == null) {
                    byte[] initial = prefetched.toByteArray();
                    InputStream base = delegate.getInputStream();
                    exposedInputStream = initial.length == 0
                        ? base
                        : new SequenceInputStream(new ByteArrayInputStream(initial), base);
                }
                return exposedInputStream;
            }
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public synchronized void close() throws IOException {
            watching = false;
            delegate.close();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public boolean isInputShutdown() {
            return delegate.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return delegate.isOutputShutdown();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return delegate.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return delegate.getLocalSocketAddress();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public int getLocalPort() {
            return delegate.getLocalPort();
        }

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            delegate.setTcpNoDelay(on);
        }

        @Override
        public void setKeepAlive(boolean on) throws SocketException {
            delegate.setKeepAlive(on);
        }

        @Override
        public void setReceiveBufferSize(int size) throws SocketException {
            delegate.setReceiveBufferSize(size);
        }

        @Override
        public void setSendBufferSize(int size) throws SocketException {
            delegate.setSendBufferSize(size);
        }
    }

}
