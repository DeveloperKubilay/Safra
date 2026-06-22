package org.developerkubilay.safra.p2p;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import org.apache.logging.log4j.Logger;
import org.developerkubilay.safra.util.SafraLogger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SafraVoiceClientSocket implements ClientVoicechatSocket {
    private static final Logger LOGGER = SafraLogger.get(SafraVoiceClientSocket.class);

    private final ScheduledExecutorService scheduler = P2pRuntime.singleScheduler();
    private final P2pStunMappings stunMappings = new P2pStunMappings();

    private DatagramSocket socket;
    private volatile boolean closed = true;
    private volatile InetSocketAddress safraRemoteAddress;
    private volatile SocketAddress logicalRemoteAddress;

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

        Collection<InetSocketAddress> publicEndpoints = stunMappings.discoverPublicEndpoints(discoverySocket);
        if (publicEndpoints.isEmpty()) {
            throw new IOException("Safra voice joiner Could not find a public UDP endpoint");
        }

        return joinSession.resolveVoice(publicEndpoints);
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
