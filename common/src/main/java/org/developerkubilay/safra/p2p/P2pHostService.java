package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;
import org.developerkubilay.safra.p2p.transport.P2pDirectDatagramTransport;
import org.developerkubilay.safra.p2p.turn.P2pTurnCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class P2pHostService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pHostService.class);

    private final Map<Integer, ReliableTunnelConnection> connections = new ConcurrentHashMap<>();
    private final P2pStunClient stunClient = new P2pStunClient();
    private final ScheduledExecutorService scheduler = P2pRuntime.schedulerPool(4);
    private final int tcpPort;
    private final int token;
    private final InetAddress targetAddress;
    private final String preferredRendezvousCode;
    private final boolean allowRelayFallback;
    private final Runnable relayReadyHandler;

    private P2pDatagramTransport transport;
    private volatile P2pDatagramTransport relayFallbackTransport;
    private final Map<String, P2pStunClient.DiscoveredEndpoint> discoveredEndpoints = new ConcurrentHashMap<>();
    private SafraRendezvousClient.HostSession rendezvousSession;
    private volatile boolean primaryTransportRelay;
    private boolean relayReadyNotified;
    private volatile boolean closed;

    public P2pHostService(int tcpPort, int token) {
        this(tcpPort, token, P2pSockets.loopbackAddress(), null, true);
    }

    public P2pHostService(int tcpPort, int token, InetAddress targetAddress) {
        this(tcpPort, token, targetAddress, null, true);
    }

    public P2pHostService(int tcpPort, int token, String preferredRendezvousCode) {
        this(tcpPort, token, P2pSockets.loopbackAddress(), preferredRendezvousCode, true);
    }

    public P2pHostService(int tcpPort, int token, String preferredRendezvousCode, Runnable relayReadyHandler) {
        this(tcpPort, token, P2pSockets.loopbackAddress(), preferredRendezvousCode, true, relayReadyHandler);
    }

    public P2pHostService(int tcpPort, int token, InetAddress targetAddress, String preferredRendezvousCode) {
        this(tcpPort, token, targetAddress, preferredRendezvousCode, true);
    }

    public P2pHostService(int tcpPort, int token, InetAddress targetAddress, String preferredRendezvousCode, boolean allowRelayFallback) {
        this(tcpPort, token, targetAddress, preferredRendezvousCode, allowRelayFallback, () -> {
        });
    }

    public P2pHostService(int tcpPort, int token, InetAddress targetAddress, String preferredRendezvousCode, boolean allowRelayFallback,
                          Runnable relayReadyHandler) {
        this.tcpPort = tcpPort;
        this.token = token;
        this.targetAddress = targetAddress;
        this.preferredRendezvousCode = P2pShareCode.normalizeRendezvousCode(preferredRendezvousCode);
        this.allowRelayFallback = allowRelayFallback;
        this.relayReadyHandler = relayReadyHandler == null ? () -> {
        } : relayReadyHandler;
    }

    public P2pShareCode start() throws IOException {
        if (closed) {
            throw new IOException("Safra P2P host service was stopped");
        }

        int preferredUdpPort = P2pOptionalIntegrations.isVoiceChatAvailable() ? 0 : tcpPort;
        P2pTransportBinding binding = P2pUdpBindingFactory.createBestHostBinding(LOGGER, stunClient, preferredUdpPort, allowRelayFallback);
        transport = binding.transport();
        primaryTransportRelay = binding.relay();
        if (closed) {
            binding.close();
            throw new IOException("Safra P2P host service was stopped");
        }

        discoveredEndpoints.clear();
        discoveredEndpoints.putAll(binding.stunEndpoints());
        if (closed) {
            binding.close();
            throw new IOException("Safra P2P host service was stopped");
        }
        InetSocketAddress publishedEndpoint = preferredEndpoint(binding.publicEndpoints());
        if (publishedEndpoint == null && !P2pConstants.useApi30Rendezvous()) {
            binding.close();
            throw new IOException("Could not find a public UDP endpoint");
        }

        P2pRuntime.start("safra-p2p-host-recv", () -> receiveLoop(transport, primaryTransportRelay));
        if (!primaryTransportRelay && !discoveredEndpoints.isEmpty()) {
            scheduler.scheduleAtFixedRate(this::refreshStunMapping, P2pConstants.STUN_REFRESH_MS,
                P2pConstants.STUN_REFRESH_MS, TimeUnit.MILLISECONDS);
        }

        P2pShareCode directShareCode = null;
        if (publishedEndpoint != null) {
            InetAddress address = publishedEndpoint.getAddress();
            String host = address == null ? publishedEndpoint.getHostString() : address.getHostAddress();
            LOGGER.debug("Safra P2P host UDP {} transport local port {}, published endpoint {}:{}",
                primaryTransportRelay ? "TURN" : "direct", transport.getLocalPort(), host, publishedEndpoint.getPort());
            directShareCode = new P2pShareCode(host, publishedEndpoint.getPort(), token);
        } else {
            LOGGER.debug("Safra P2P host UDP {} transport local port {}, no STUN endpoint; relay-required host flow will be attempted",
                primaryTransportRelay ? "TURN" : "direct", transport.getLocalPort());
        }

        Collection<InetSocketAddress> voicePublicEndpoints = P2pOptionalIntegrations.isVoiceChatAvailable()
            ? SafraVoiceTransportManager.getInstance().awaitHostVoiceEndpoints(tcpPort, P2pConstants.VOICE_HOST_WAIT_MS)
            : java.util.List.of();

        try {
            rendezvousSession = SafraRendezvousClient.startHost(
                tcpPort,
                token,
                preferredRendezvousCode,
                binding.publicEndpoints(),
                voicePublicEndpoints,
                this::punchRemoteEndpoint,
                SafraVoiceTransportManager.getInstance()::punchHostVoiceEndpoint,
                this::ensureRelayAvailable
            );
            SafraVoiceTransportManager.getInstance().setHostSession(rendezvousSession);
            LOGGER.debug("Safra P2P rendezvous session registered. Code: {}", rendezvousSession.code());
            return P2pShareCode.rendezvous(rendezvousSession.code());
        } catch (IOException exception) {
            if (primaryTransportRelay) {
                LOGGER.warn("Safra P2P rendezvous registration failed while TURN relay was active", exception);
                throw exception;
            }
            if (directShareCode == null) {
                throw exception;
            }
            LOGGER.warn("Safra P2P rendezvous registration failed; falling back to direct UDP share code", exception);
            return directShareCode;
        }
    }

    public int tcpPort() {
        return tcpPort;
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
        if (rendezvousSession != null) {
            SafraVoiceTransportManager.getInstance().clearHostSession(rendezvousSession);
            rendezvousSession.close();
            rendezvousSession = null;
        }
        if (transport != null && !transport.isClosed()) {
            transport.close();
        }
        if (relayFallbackTransport != null && !relayFallbackTransport.isClosed()) {
            relayFallbackTransport.close();
            relayFallbackTransport = null;
        }
        LOGGER.debug("Safra P2P host UDP transport closed for local Minecraft TCP port {}", tcpPort);
    }

    private void punchRemoteEndpoint(InetSocketAddress remoteAddress) {
        punchRemoteEndpoint(transport, remoteAddress);
    }

    private void punchRemoteEndpoint(P2pDatagramTransport activeTransport, InetSocketAddress remoteAddress) {
        if (closed || remoteAddress == null || remoteAddress.isUnresolved()) {
            return;
        }

        LOGGER.debug("Safra P2P host punching UDP endpoint {}", remoteAddress);
        long[] delays = {0L, 100L, 250L, 500L, 1_000L, 2_000L, 4_000L, 7_000L};
        for (long delay : delays) {
            try {
                scheduler.schedule(() -> sendPacket(activeTransport, P2pPacket.ack(token, 0, 0), remoteAddress), delay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                if (!closed) {
                    LOGGER.debug("Could not schedule UDP punch packet: {}", exception.toString());
                }
            }
        }

    }

    private void refreshStunMapping() {
        if (closed || primaryTransportRelay || discoveredEndpoints.isEmpty()) {
            return;
        }

        for (P2pStunClient.DiscoveredEndpoint endpoint : discoveredEndpoints.values()) {
            if (endpoint.stunServer() == null) {
                continue;
            }
            try {
                if (transport instanceof P2pDirectDatagramTransport directTransport) {
                    stunClient.sendKeepAlive(directTransport.socket(), endpoint.stunServer());
                }
            } catch (IOException exception) {
                LOGGER.debug("STUN keepalive failed: {}", exception.toString());
            }
        }
    }

    private void receiveLoop(P2pDatagramTransport activeTransport, boolean relayTransportActive) {
        byte[] buffer = new byte[P2pConstants.MAX_DATAGRAM_SIZE];
        while (!closed) {
            DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
            try {
                activeTransport.receive(datagramPacket);
            } catch (SocketTimeoutException ignored) {
                continue;
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.debug("Host UDP receive failed: {}", exception.toString());
                }
                return;
            }

            P2pStunClient.DiscoveredEndpoint stunEndpoint = relayTransportActive ? null : matchingStunEndpoint(datagramPacket.getSocketAddress());
            if (!relayTransportActive && stunEndpoint != null) {
                P2pStunClient.DiscoveredEndpoint refreshed = stunClient.tryParseResponse(datagramPacket);
                if (refreshed != null) {
                    discoveredEndpoints.put(refreshed.family(), refreshed.withServer(stunEndpoint.stunServer()));
                }
                continue;
            }

            P2pPacket packet = P2pPacket.decode(datagramPacket.getData(), datagramPacket.getLength());
            if (packet == null) {
                continue;
            }

            if (packet.token() != token) {
                if (packet.type() == P2pPacket.Type.OPEN) {
                    LOGGER.debug("Safra P2P host ignored tunnel open from {} because the share-code token is old or wrong", datagramPacket.getSocketAddress());
                }
                continue;
            }

            InetSocketAddress remoteAddress = new InetSocketAddress(datagramPacket.getAddress(), datagramPacket.getPort());
            if (packet.type() == P2pPacket.Type.OPEN) {
                handleOpen(packet, remoteAddress, activeTransport);
                continue;
            }

            ReliableTunnelConnection connection = connections.get(packet.connectionId());
            if (connection != null) {
                if (activeTransport == relayFallbackTransport) {
                    boolean routeChanged = connection.updateRoute(remoteAddress,
                        (outgoingPacket, outgoingRemoteAddress) -> sendPacket(activeTransport, outgoingPacket, outgoingRemoteAddress));
                    if (routeChanged) {
                        LOGGER.info("Safra existing P2P tunnel moved from direct transport to TURN: {}", remoteAddress);
                    }
                }
                connection.handlePacket(packet);
            }
        }
    }

    private P2pStunClient.DiscoveredEndpoint matchingStunEndpoint(java.net.SocketAddress remoteAddress) {
        for (P2pStunClient.DiscoveredEndpoint endpoint : discoveredEndpoints.values()) {
            if (endpoint.matches(remoteAddress)) {
                return endpoint;
            }
        }
        return null;
    }

    private void handleOpen(P2pPacket packet, InetSocketAddress remoteAddress, P2pDatagramTransport activeTransport) {
        ReliableTunnelConnection existing = connections.get(packet.connectionId());
        if (existing != null) {
            boolean routeChanged = existing.updateRoute(remoteAddress,
                (outgoingPacket, outgoingRemoteAddress) -> sendPacket(activeTransport, outgoingPacket, outgoingRemoteAddress));
            if (routeChanged && activeTransport == relayFallbackTransport) {
                LOGGER.info("Safra existing P2P tunnel moved from direct transport to TURN: {}", remoteAddress);
            }
            existing.sendOpenAck();
            return;
        }

        try {
            LOGGER.debug("Safra P2P host received tunnel open {} from {}", packet.connectionId(), remoteAddress);
            Socket tcpSocket = new Socket(targetAddress, tcpPort);
            ReliableTunnelConnection connection = new ReliableTunnelConnection(
                LOGGER,
                "host",
                token,
                packet.connectionId(),
                remoteAddress,
                tcpSocket,
                (outgoingPacket, outgoingRemoteAddress) -> sendPacket(activeTransport, outgoingPacket, outgoingRemoteAddress),
                connections::remove,
                scheduler,
                false,
                null
            );
            ReliableTunnelConnection raced = connections.putIfAbsent(packet.connectionId(), connection);
            if (raced != null) {
                tcpSocket.close();
                raced.updateRoute(remoteAddress,
                    (outgoingPacket, outgoingRemoteAddress) -> sendPacket(activeTransport, outgoingPacket, outgoingRemoteAddress));
                raced.sendOpenAck();
                return;
            }

            connection.start();
            connection.sendOpenAck();
            LOGGER.debug("Safra P2P host tunnel {} connected to local Minecraft TCP {}:{}", packet.connectionId(), targetAddress.getHostAddress(), tcpPort);
        } catch (IOException exception) {
            LOGGER.warn("Safra P2P host could not open local Minecraft TCP tunnel {}: {}", packet.connectionId(), exception.toString());
            sendPacket(P2pPacket.close(token, packet.connectionId()), remoteAddress);
        }
    }

    private void sendPacket(P2pDatagramTransport activeTransport, P2pPacket packet, InetSocketAddress remoteAddress) {
        if (closed || activeTransport == null || activeTransport.isClosed()) {
            return;
        }

        byte[] encoded = packet.encode();
        try {
            activeTransport.send(new DatagramPacket(encoded, encoded.length, remoteAddress));
        } catch (IOException exception) {
            LOGGER.debug("Host UDP send failed: {}", exception.toString());
        }
    }

    private void sendPacket(P2pPacket packet, InetSocketAddress remoteAddress) {
        sendPacket(transport, packet, remoteAddress);
    }

    private synchronized void ensureRelayAvailable(InetSocketAddress joinerRelayAddress) {
        if (closed || primaryTransportRelay || !allowRelayFallback || P2pConstants.neverUseRelayServer()) {
            return;
        }

        if (relayFallbackTransport != null && !relayFallbackTransport.isClosed()) {
            publishRelayReady();
            notifyRelayReady();
            if (joinerRelayAddress != null) {
                punchRemoteEndpoint(relayFallbackTransport, joinerRelayAddress);
            }
            return;
        }

        try {
            P2pTurnCredentials pendingCredentials = rendezvousSession == null ? null : rendezvousSession.consumePendingRelayCredentials();
            P2pTransportBinding relayBinding = pendingCredentials == null
                ? P2pUdpBindingFactory.createTurnBinding(LOGGER, "host")
                : P2pUdpBindingFactory.createTurnBinding(LOGGER, "host", pendingCredentials);
            relayFallbackTransport = relayBinding.transport();
            P2pRuntime.start("safra-p2p-host-relay-recv", () -> receiveLoop(relayFallbackTransport, true));
            publishRelayReady(relayBinding.publicEndpoints());
            notifyRelayReady();
            LOGGER.info("Safra host TURN fallback ready: {}", preferredEndpoint(relayBinding.publicEndpoints()));
            if (joinerRelayAddress != null) {
                punchRemoteEndpoint(relayFallbackTransport, joinerRelayAddress);
            }
        } catch (IOException exception) {
            LOGGER.warn("Safra P2P host relay provisioning failed", exception);
            if (rendezvousSession != null) {
                rendezvousSession.publishRelayFailure("auto", exception.getMessage());
            }
        }
    }

    private void publishRelayReady() {
        if (!(relayFallbackTransport instanceof org.developerkubilay.safra.p2p.turn.P2pTurnDatagramTransport turnTransport)) {
            return;
        }
        publishRelayReady(java.util.List.of(turnTransport.relayAddress()));
    }

    private void publishRelayReady(Collection<InetSocketAddress> publicEndpoints) {
        if (rendezvousSession == null || publicEndpoints == null || publicEndpoints.isEmpty()) {
            return;
        }

        try {
            rendezvousSession.publishRelay(publicEndpoints, "auto");
        } catch (IOException exception) {
            LOGGER.warn("Safra P2P host relay publish failed", exception);
        }
    }

    private void notifyRelayReady() {
        if (relayReadyNotified || closed) {
            return;
        }
        relayReadyNotified = true;
        try {
            relayReadyHandler.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Safra P2P host relay ready handler failed", exception);
        }
    }
    private InetSocketAddress preferredEndpoint(Collection<InetSocketAddress> endpoints) {
        if (endpoints == null) {
            return null;
        }

        InetSocketAddress ipv4 = null;
        InetSocketAddress fallback = null;
        for (InetSocketAddress endpoint : endpoints) {
            if (endpoint == null || endpoint.getAddress() == null) {
                continue;
            }
            if (fallback == null) {
                fallback = endpoint;
            }
            if ("ipv4".equals(P2pSockets.addressFamily(endpoint))) {
                ipv4 = endpoint;
                break;
            }
        }
        return ipv4 != null ? ipv4 : fallback;
    }
}
