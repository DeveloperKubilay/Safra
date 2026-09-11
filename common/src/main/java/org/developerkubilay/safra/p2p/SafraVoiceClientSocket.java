package org.developerkubilay.safra.p2p;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SafraVoiceClientSocket implements ClientVoicechatSocket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraVoiceClientSocket.class);

    private ScheduledExecutorService scheduler = P2pRuntime.singleScheduler();
    private final P2pStunMappings stunMappings = new P2pStunMappings();

    private DatagramSocket socket;
    private final java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed = true;
    private volatile InetSocketAddress safraRemoteAddress;
    private volatile SocketAddress logicalRemoteAddress;

    @Override
    public synchronized void open() throws Exception {
        if (socket != null && !socket.isClosed()) {
            throw new IllegalStateException("Voice socket already opened");
        }
        if (scheduler.isShutdown()) {
            scheduler = P2pRuntime.singleScheduler();
        }

        DatagramSocket createdSocket = P2pSockets.datagramSocket();
        try {
            InetSocketAddress resolvedRemoteAddress = resolveSafraRemote(createdSocket);
            socket = createdSocket;
            safraRemoteAddress = resolvedRemoteAddress;
            closed = false;
        } catch (Exception exception) {
            createdSocket.close();
            throw exception;
        }
        LOGGER.debug("Safra voice socket open on local port {}, sending to host voice endpoint {}",
            socket.getLocalPort(), safraRemoteAddress);
        scheduler.scheduleAtFixedRate(this::refreshStunMapping, P2pConstants.STUN_REFRESH_MS,
            P2pConstants.STUN_REFRESH_MS, TimeUnit.MILLISECONDS);
        if (SafraBuildInfo.diagnostics()) {
            scheduler.scheduleAtFixedRate(this::logTraffic, 10L, 10L, TimeUnit.SECONDS);
        }
    }

    private InetSocketAddress resolveSafraRemote(DatagramSocket discoverySocket) throws IOException {
        SafraRendezvousClient.JoinSession joinSession = SafraVoiceTransportManager.getInstance().joinSession();
        if (joinSession == null) {
            stunMappings.clear();
            throw new IOException("Safra voice session is not ready");
        }

        java.util.Collection<InetSocketAddress> publicEndpoints = stunMappings.discoverPublicEndpoints(discoverySocket);
        if (publicEndpoints.isEmpty()) {
            throw new IOException("Safra voice joiner public UDP endpoint could not be found");
        }

        InetSocketAddress resolvedRemoteAddress = joinSession.resolveVoice(publicEndpoints);
        return resolvedRemoteAddress;
    }

    private void refreshStunMapping() {
        DatagramSocket currentSocket = socket;
        if (closed || currentSocket == null || currentSocket.isClosed() || stunMappings.isEmpty()) {
            return;
        }

        stunMappings.sendKeepAlives(currentSocket, LOGGER, "Safra voice join STUN keepalive failed");
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
            if (isPunchPacket(packet)) {
                continue;
            }
            received.incrementAndGet();
            return new SafraRawUdpPacket(
                Arrays.copyOf(packet.getData(), packet.getLength()),
                logicalRemoteAddress != null ? logicalRemoteAddress : packet.getSocketAddress(),
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

        if (address != null) {
            logicalRemoteAddress = address;
        }

        SocketAddress target = safraRemoteAddress;
        if (target == null) {
            return;
        }
        currentSocket.send(new DatagramPacket(data, data.length, target));
        sent.incrementAndGet();
    }

    /** Voice carries no error of its own when it goes nowhere, so it says how much went each way. */
    private void logTraffic() {
        LOGGER.info("Safra voice: {} packets sent to {}, {} received", sent.get(), safraRemoteAddress, received.get());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        safraRemoteAddress = null;
        logicalRemoteAddress = null;
        stunMappings.clear();
        scheduler.shutdownNow();
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed || socket == null || socket.isClosed();
    }

    private boolean handleStunPacket(DatagramPacket packet) {
        return stunMappings.rememberResponse(packet);
    }

    private boolean isPunchPacket(DatagramPacket packet) {
        return packet.getLength() == 1 && packet.getData()[packet.getOffset()] == 0;
    }
}
