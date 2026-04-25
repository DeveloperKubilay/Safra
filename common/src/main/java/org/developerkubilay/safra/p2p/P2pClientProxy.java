package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class P2pClientProxy implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2pClientProxy.class);

    private final P2pShareCode shareCode;
    private final P2pStunClient stunClient = new P2pStunClient();
    private final Map<Integer, ReliableTunnelConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());
    private final Runnable onClose;

    private DatagramSocket udpSocket;
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
        udpSocket = P2pSockets.datagramSocket();
        if (shareCode.isRendezvous()) {
            resolveRendezvousShareCode();
        } else {
            InetAddress remoteInetAddress = InetAddress.getByName(shareCode.host());
            remoteAddress = new InetSocketAddress(remoteInetAddress, shareCode.port());
            tunnelToken = shareCode.token();
        }

        proxyServer = new ServerSocket(0, 16, P2pSockets.loopbackAddress());
        LOGGER.debug("Safra P2P client proxy listening on {}:{} and dialing {}",
            proxyServer.getInetAddress().getHostAddress(), proxyServer.getLocalPort(), remoteAddress);

        Thread.ofVirtual().name("safra-p2p-client-recv").start(this::receiveLoop);
        Thread.ofVirtual().name("safra-p2p-client-accept").start(this::acceptLoop);
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
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        if (rendezvousSession != null) {
            rendezvousSession.close();
            rendezvousSession = null;
        }
        LOGGER.debug("Safra P2P client proxy closed for {}", remoteAddress);
        onClose.run();
    }

    private void resolveRendezvousShareCode() throws IOException {
        P2pStunClient.DiscoveredEndpoint endpoint = stunClient.discover(udpSocket).orElse(null);
        if (endpoint == null) {
            throw new IOException("STUN ile joiner genel UDP ucu bulunamadi");
        }

        rendezvousSession = SafraRendezvousClient.join(
            shareCode.rendezvousCode(),
            endpoint.publicAddress(),
            udpSocket
        );
        remoteAddress = rendezvousSession.hostAddress();
        tunnelToken = rendezvousSession.tunnelToken();
        if (tunnelToken == 0) {
            throw new IOException("Rendezvous sunucusu gecersiz tunel token'i dondurdu");
        }
        if (!P2pSockets.sameAddressFamily(endpoint.publicAddress(), remoteAddress)) {
            throw new IOException("Host ve joiner farkli IP ailesi kullaniyor ("
                + P2pSockets.addressFamily(endpoint.publicAddress()) + " / "
                + P2pSockets.addressFamily(remoteAddress) + ")");
        }
        LOGGER.debug("Safra P2P rendezvous code {} resolved to {}", shareCode.rendezvousCode(), remoteAddress);
    }

    private void acceptLoop() {
        try (ServerSocket ignored = proxyServer) {
            Socket localSocket = proxyServer.accept();
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
        } catch (IOException exception) {
            if (!closed) {
                LOGGER.debug("Proxy accept failed: {}", exception.toString());
                close();
            }
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[P2pConstants.MAX_DATAGRAM_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(packet);
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

    private void removeConnection(int connectionId) {
        connections.remove(connectionId);
        scheduler.schedule(this::closeIfIdle, 1L, TimeUnit.SECONDS);
    }

    private void closeIfIdle() {
        if (!closed && connections.isEmpty()) {
            close();
        }
    }

    private void sendPacket(P2pPacket packet, InetSocketAddress remoteAddress) {
        if (closed || udpSocket == null || udpSocket.isClosed()) {
            return;
        }

        byte[] encoded = packet.encode();
        synchronized (udpSocket) {
            try {
                udpSocket.send(new DatagramPacket(encoded, encoded.length, remoteAddress));
            } catch (IOException exception) {
                LOGGER.debug("Client UDP send failed: {}", exception.toString());
            }
        }
    }
}
