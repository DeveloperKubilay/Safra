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

    private final ScheduledExecutorService scheduler = P2pRuntime.singleScheduler();
    private final P2pStunMappings stunMappings = new P2pStunMappings();

    private DatagramSocket socket;
    private volatile boolean closed = true;
    private volatile InetSocketAddress safraRemoteAddress;

    @Override
    public synchronized void open() throws Exception {
        if (socket != null && !socket.isClosed()) {
            throw new IllegalStateException("Voice socket already opened");
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
        scheduler.scheduleAtFixedRate(this::refreshStunMapping, P2pConstants.STUN_REFRESH_MS,
            P2pConstants.STUN_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    private InetSocketAddress resolveSafraRemote(DatagramSocket discoverySocket) throws IOException {
        SafraRendezvousClient.JoinSession joinSession = SafraVoiceTransportManager.getInstance().joinSession();
        if (joinSession == null) {
            stunMappings.clear();
            return null;
        }

        // Resolve STUN/public candidates before Simple Voice Chat starts its auth loop on this socket.
        if (stunMappings.discoverPublicEndpoints(discoverySocket).isEmpty()) {
            throw new IOException("Safra voice joiner genel UDP ucu bulunamadi");
        }

        InetSocketAddress resolvedRemoteAddress = joinSession.resolveVoice(stunMappings.publicEndpoints());
        LOGGER.debug("Safra voice join resolved to {}", resolvedRemoteAddress);
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

        SocketAddress target = safraRemoteAddress;
        if (target == null) {
            return;
        }
        currentSocket.send(new DatagramPacket(data, data.length, target));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        safraRemoteAddress = null;
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
}
