package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class P2pHostService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pHostService.class);

    private final Map<Integer, ReliableTunnelConnection> connections = new ConcurrentHashMap<>();
    private final P2pStunClient stunClient = new P2pStunClient();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());
    private final int tcpPort;
    private final int token;
    private final InetAddress targetAddress;

    private DatagramSocket socket;
    private P2pStunClient.DiscoveredEndpoint discoveredEndpoint;
    private SafraRendezvousClient.HostSession rendezvousSession;
    private volatile boolean closed;

    public P2pHostService(int tcpPort, int token) {
        this(tcpPort, token, P2pSockets.loopbackAddress());
    }

    public P2pHostService(int tcpPort, int token, InetAddress targetAddress) {
        this.tcpPort = tcpPort;
        this.token = token;
        this.targetAddress = targetAddress;
    }

    public P2pShareCode start() throws IOException {
        if (closed) {
            throw new IOException("Safra P2P host service was stopped");
        }

        socket = bindSocket(tcpPort);
        if (closed) {
            socket.close();
            throw new IOException("Safra P2P host service was stopped");
        }

        discoveredEndpoint = stunClient.discover(socket).orElse(null);
        if (closed) {
            socket.close();
            throw new IOException("Safra P2P host service was stopped");
        }

        InetSocketAddress publishedEndpoint = discoveredEndpoint != null
            ? discoveredEndpoint.publicAddress()
            : null;
        if (publishedEndpoint == null) {
            socket.close();
            throw new IOException("STUN ile genel UDP ucu bulunamadi");
        }

        Thread.ofVirtual().name("safra-p2p-host-recv").start(this::receiveLoop);
        scheduler.scheduleAtFixedRate(this::refreshStunMapping, P2pConstants.STUN_REFRESH_MS,
            P2pConstants.STUN_REFRESH_MS, TimeUnit.MILLISECONDS);

        InetAddress address = publishedEndpoint.getAddress();
        String host = address == null ? publishedEndpoint.getHostString() : address.getHostAddress();
        LOGGER.debug("Safra P2P host UDP socket bound on local port {}, published endpoint {}:{}", socket.getLocalPort(), host, publishedEndpoint.getPort());
        P2pShareCode directShareCode = new P2pShareCode(host, publishedEndpoint.getPort(), token);

        try {
            rendezvousSession = SafraRendezvousClient.startHost(
                tcpPort,
                token,
                discoveredEndpoint.publicAddress(),
                socket,
                this::punchRemoteEndpoint
            );
            LOGGER.info("Safra P2P rendezvous session registered. Code: {}", rendezvousSession.code());
            return P2pShareCode.rendezvous(rendezvousSession.code());
        } catch (IOException exception) {
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
            rendezvousSession.close();
            rendezvousSession = null;
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        LOGGER.debug("Safra P2P host UDP socket closed for local Minecraft TCP port {}", tcpPort);
    }

    private void punchRemoteEndpoint(InetSocketAddress remoteAddress) {
        if (closed || remoteAddress == null || remoteAddress.isUnresolved()) {
            return;
        }

        LOGGER.debug("Safra P2P host punching UDP endpoint {}", remoteAddress);
        long[] delays = {0L, 100L, 250L, 500L, 1_000L};
        for (long delay : delays) {
            try {
                scheduler.schedule(() -> sendPacket(P2pPacket.ack(token, 0, 0), remoteAddress), delay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                if (!closed) {
                    LOGGER.debug("Could not schedule UDP punch packet: {}", exception.toString());
                }
            }
        }
    }

    private void refreshStunMapping() {
        if (closed || socket == null || socket.isClosed() || discoveredEndpoint == null || discoveredEndpoint.stunServer() == null) {
            return;
        }

        try {
            stunClient.sendKeepAlive(socket, discoveredEndpoint.stunServer());
        } catch (IOException exception) {
            LOGGER.debug("STUN keepalive failed: {}", exception.toString());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[P2pConstants.MAX_DATAGRAM_SIZE];
        while (!closed) {
            DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagramPacket);
            } catch (SocketTimeoutException ignored) {
                continue;
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.debug("Host UDP receive failed: {}", exception.toString());
                }
                return;
            }

            if (discoveredEndpoint != null && discoveredEndpoint.matches(datagramPacket.getSocketAddress())) {
                P2pStunClient.DiscoveredEndpoint refreshed = stunClient.tryParseResponse(datagramPacket);
                if (refreshed != null) {
                    discoveredEndpoint = refreshed.withServer(discoveredEndpoint.stunServer());
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
                handleOpen(packet, remoteAddress);
                continue;
            }

            ReliableTunnelConnection connection = connections.get(packet.connectionId());
            if (connection != null) {
                connection.handlePacket(packet);
            }
        }
    }

    private void handleOpen(P2pPacket packet, InetSocketAddress remoteAddress) {
        ReliableTunnelConnection existing = connections.get(packet.connectionId());
        if (existing != null) {
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
                this::sendPacket,
                connections::remove,
                scheduler,
                false
            );
            ReliableTunnelConnection raced = connections.putIfAbsent(packet.connectionId(), connection);
            if (raced != null) {
                tcpSocket.close();
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

    private void sendPacket(P2pPacket packet, InetSocketAddress remoteAddress) {
        if (closed || socket == null || socket.isClosed()) {
            return;
        }

        byte[] encoded = packet.encode();
        synchronized (socket) {
            try {
                socket.send(new DatagramPacket(encoded, encoded.length, remoteAddress));
            } catch (IOException exception) {
                LOGGER.debug("Host UDP send failed: {}", exception.toString());
            }
        }
    }

    private DatagramSocket bindSocket(int preferredPort) throws IOException {
        try {
            return P2pSockets.datagramSocket(preferredPort);
        } catch (BindException ignored) {
            return P2pSockets.datagramSocket();
        }
    }
}
