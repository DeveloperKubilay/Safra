package org.developerkubilay.safra.p2p;

import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SafraVoiceServerSocket implements VoicechatSocket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraVoiceServerSocket.class);

    private final P2pStunClient stunClient = new P2pStunClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final Map<String, P2pStunClient.DiscoveredEndpoint> discoveredEndpoints = new ConcurrentHashMap<>();

    private DatagramSocket socket;
    private volatile boolean closed = true;
    private volatile String publishedCode;

    @Override
    public synchronized void open(int port, String bindAddress) throws Exception {
        if (socket != null && !socket.isClosed()) {
            throw new IllegalStateException("Voice socket already opened");
        }

        socket = openSocket(port, bindAddress);
        closed = false;
        SafraVoiceTransportManager.getInstance().registerServerSocket(this);
        scheduler.scheduleAtFixedRate(this::refreshStunMapping, P2pConstants.STUN_REFRESH_MS,
            P2pConstants.STUN_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    synchronized void refreshSafraBinding() {
        DatagramSocket currentSocket = socket;
        if (closed || currentSocket == null || currentSocket.isClosed()) {
            return;
        }

        SafraVoiceTransportManager manager = SafraVoiceTransportManager.getInstance();
        SafraRendezvousClient.HostSession session = manager.hostSession();
        String code = manager.hostCode();
        if (session == null || code == null || code.isBlank()) {
            publishedCode = null;
            discoveredEndpoints.clear();
            return;
        }

        if (code.equals(publishedCode) && !discoveredEndpoints.isEmpty()) {
            return;
        }

        Map<String, P2pStunClient.DiscoveredEndpoint> candidates = stunClient.discoverCandidates(currentSocket);
        P2pStunClient.DiscoveredEndpoint preferred = P2pStunClient.preferredCandidate(candidates);
        if (preferred == null || preferred.publicAddress() == null) {
            LOGGER.warn("Safra voice host could not publish UDP candidates for session {}", code);
            return;
        }

        try {
            session.publishVoice(P2pStunClient.publicEndpoints(candidates));
            discoveredEndpoints.clear();
            discoveredEndpoints.putAll(candidates);
            publishedCode = code;
            LOGGER.debug("Safra voice host published {} UDP candidate(s) for session {}", candidates.size(), code);
        } catch (IOException exception) {
            LOGGER.warn("Safra voice host could not publish UDP candidates for session {}", code, exception);
        }
    }

    private void refreshStunMapping() {
        DatagramSocket currentSocket = socket;
        if (closed || currentSocket == null || currentSocket.isClosed() || discoveredEndpoints.isEmpty()) {
            return;
        }

        for (P2pStunClient.DiscoveredEndpoint endpoint : discoveredEndpoints.values()) {
            if (endpoint.stunServer() == null) {
                continue;
            }
            try {
                stunClient.sendKeepAlive(currentSocket, endpoint.stunServer());
            } catch (IOException exception) {
                LOGGER.debug("Safra voice STUN keepalive failed: {}", exception.toString());
            }
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        DatagramSocket currentSocket = socket;
        if (currentSocket == null) {
            throw new IllegalStateException("Voice socket not opened yet");
        }

        byte[] buffer = new byte[8192];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        currentSocket.receive(packet);
        return new SafraRawUdpPacket(
            Arrays.copyOf(packet.getData(), packet.getLength()),
            packet.getSocketAddress(),
            System.currentTimeMillis()
        );
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        DatagramSocket currentSocket = socket;
        if (currentSocket == null || currentSocket.isClosed()) {
            return;
        }

        currentSocket.send(new DatagramPacket(data, data.length, address));
    }

    @Override
    public int getLocalPort() {
        DatagramSocket currentSocket = socket;
        return currentSocket == null ? -1 : currentSocket.getLocalPort();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        publishedCode = null;
        discoveredEndpoints.clear();
        SafraVoiceTransportManager.getInstance().unregisterServerSocket(this);
        scheduler.shutdownNow();
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed || socket == null || socket.isClosed();
    }

    private static DatagramSocket openSocket(int port, String bindAddress) throws IOException {
        InetAddress address = parseBindAddress(bindAddress);
        try {
            return createSocket(port, address);
        } catch (BindException exception) {
            if (address == null || bindAddress == null || bindAddress.isBlank()) {
                throw exception;
            }

            LOGGER.debug("Safra voice socket could not bind to {}; falling back to wildcard", bindAddress);
            return createSocket(port, null);
        }
    }

    private static InetAddress parseBindAddress(String bindAddress) throws UnknownHostException {
        if (bindAddress == null || bindAddress.isBlank()) {
            return null;
        }
        return InetAddress.getByName(bindAddress);
    }

    private static DatagramSocket createSocket(int port, InetAddress address) throws SocketException {
        DatagramSocket created = new DatagramSocket((SocketAddress) null);
        if (address == null) {
            created.bind(new InetSocketAddress(port));
        } else {
            created.bind(new InetSocketAddress(address, port));
        }
        try {
            created.setReceiveBufferSize(P2pConstants.SOCKET_BUFFER_SIZE);
            created.setSendBufferSize(P2pConstants.SOCKET_BUFFER_SIZE);
            created.setTrafficClass(0x10);
        } catch (SocketException ignored) {
        }
        return created;
    }
}
