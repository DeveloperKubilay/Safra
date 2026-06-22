package org.developerkubilay.safra.p2p;

import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import org.apache.logging.log4j.Logger;
import org.developerkubilay.safra.util.SafraLogger;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SafraVoiceServerSocket implements VoicechatSocket {
    private static final Logger LOGGER = SafraLogger.get(SafraVoiceServerSocket.class);

    private ScheduledExecutorService scheduler = P2pRuntime.singleScheduler();
    private final P2pStunMappings stunMappings = new P2pStunMappings();

    private DatagramSocket socket;
    private volatile boolean closed = true;
    private volatile String publishedCode;

    @Override
    public synchronized void open(int port, String bindAddress) throws Exception {
        boolean reopening = socket != null && !socket.isClosed() && !closed;
        if (reopening) {
            resetCurrentSocket(false);
        } else if (scheduler.isShutdown()) {
            scheduler = P2pRuntime.singleScheduler();
        }

        socket = openSocket(port, bindAddress);
        closed = false;
        requestStunDiscovery();
        if (reopening) {
            P2pRuntime.start("safra-voice-refresh", this::refreshSafraBinding);
        } else {
            SafraVoiceTransportManager.getInstance().registerServerSocket(this);
        }
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
        if (session == null || code == null || code.trim().isEmpty()) {
            publishedCode = null;
            return;
        }

        if (code.equals(publishedCode) && !stunMappings.isEmpty()) {
            return;
        }

        if (stunMappings.isEmpty()) {
            requestStunDiscovery();
            return;
        }

        P2pStunClient.DiscoveredEndpoint preferred = stunMappings.preferredCandidate();
        if (preferred == null || preferred.publicAddress() == null) {
            LOGGER.warn("Safra voice host could not publish UDP candidates for session {}", code);
            return;
        }

        try {
            session.publishVoice(stunMappings.publicEndpoints());
            publishedCode = code;
        } catch (IOException exception) {
            LOGGER.warn("Safra voice host could not publish UDP candidates for session {}", code, exception);
        }
    }

    private void refreshStunMapping() {
        DatagramSocket currentSocket = socket;
        if (closed || currentSocket == null || currentSocket.isClosed()) {
            return;
        }

        if (stunMappings.isEmpty()) {
            requestStunDiscovery();
            return;
        }

        stunMappings.sendKeepAlives(currentSocket, LOGGER, "Safra voice STUN keepalive failed");
    }

    void punchRemoteEndpoint(InetSocketAddress remoteAddress) {
        DatagramSocket currentSocket = socket;
        if (closed || currentSocket == null || currentSocket.isClosed() || remoteAddress == null || remoteAddress.isUnresolved()) {
            return;
        }

        byte[] punch = new byte[] { 0 };
        long[] delays = {0L, 100L, 250L, 500L, 1_000L};
        for (long delay : delays) {
            try {
                scheduler.schedule(() -> {
                    DatagramSocket scheduledSocket = socket;
                    if (closed || scheduledSocket == null || scheduledSocket.isClosed()) {
                        return;
                    }
                    try {
                        scheduledSocket.send(new DatagramPacket(punch, punch.length, remoteAddress));
                    } catch (IOException exception) {
                        LOGGER.debug("Safra voice host punch send failed: {}", exception.toString());
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                LOGGER.debug("Safra voice host punch schedule failed: {}", exception.toString());
            }
        }
    }

    synchronized Collection<InetSocketAddress> publicEndpointsSnapshot() {
        if (closed || socket == null || socket.isClosed() || stunMappings.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return new ArrayList<>(stunMappings.publicEndpoints());
    }

    synchronized int localPortSnapshot() {
        if (closed || socket == null || socket.isClosed()) {
            return -1;
        }
        return socket.getLocalPort();
    }

    @Override
    public RawUdpPacket read() throws Exception {
        DatagramSocket currentSocket = socket;
        if (currentSocket == null) {
            throw new IllegalStateException("Voice socket not opened yet");
        }

        byte[] buffer = new byte[8192];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            currentSocket.receive(packet);
            if (handleStunPacket(packet)) {
                continue;
            }
            return new SafraRawUdpPacket(
                Arrays.copyOf(packet.getData(), packet.getLength()),
                packet.getSocketAddress(),
                System.currentTimeMillis()
            );
        }
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

        resetCurrentSocket(true);
    }

    @Override
    public boolean isClosed() {
        return closed || socket == null || socket.isClosed();
    }

    private boolean handleStunPacket(DatagramPacket packet) {
        if (!stunMappings.rememberResponse(packet)) {
            return false;
        }
        P2pRuntime.start("safra-voice-publish", this::refreshSafraBinding);
        return true;
    }

    private void requestStunDiscovery() {
        DatagramSocket currentSocket = socket;
        if (closed || currentSocket == null || currentSocket.isClosed()) {
            return;
        }
        stunMappings.requestCandidates(currentSocket);
    }

    private static DatagramSocket openSocket(int port, String bindAddress) throws IOException {
        InetAddress address = parseBindAddress(bindAddress);
        try {
            return createSocket(port, address);
        } catch (BindException exception) {
            if (address == null || bindAddress == null || bindAddress.trim().isEmpty()) {
                throw exception;
            }

            LOGGER.debug("Safra voice socket could not bind to {}; falling back to wildcard", bindAddress);
            return createSocket(port, null);
        }
    }

    private static InetAddress parseBindAddress(String bindAddress) throws UnknownHostException {
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
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

    private void resetCurrentSocket(boolean unregister) {
        closed = true;
        publishedCode = null;
        stunMappings.clear();
        if (unregister) {
            SafraVoiceTransportManager.getInstance().unregisterServerSocket(this);
        }
        scheduler.shutdownNow();
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (!unregister) {
            scheduler = P2pRuntime.singleScheduler();
        }
    }
}
