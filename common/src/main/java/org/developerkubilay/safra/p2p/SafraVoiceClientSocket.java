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
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SafraVoiceClientSocket implements ClientVoicechatSocket {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraVoiceClientSocket.class);

    private final P2pStunClient stunClient = new P2pStunClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final Map<String, P2pStunClient.DiscoveredEndpoint> discoveredEndpoints = new ConcurrentHashMap<>();

    private DatagramSocket socket;
    private volatile boolean closed = true;
    private volatile InetSocketAddress safraRemoteAddress;
    private volatile IOException resolutionFailure;
    private volatile CompletableFuture<Void> resolverTask;

    @Override
    public synchronized void open() throws Exception {
        if (socket != null && !socket.isClosed()) {
            throw new IllegalStateException("Voice socket already opened");
        }

        socket = P2pSockets.datagramSocket();
        socket.setSoTimeout(500);
        closed = false;
        resolutionFailure = null;
        scheduler.scheduleAtFixedRate(this::refreshStunMapping, P2pConstants.STUN_REFRESH_MS,
            P2pConstants.STUN_REFRESH_MS, TimeUnit.MILLISECONDS);
        resolverTask = CompletableFuture.runAsync(() -> {
            try {
                resolveSafraRemote();
            } catch (IOException exception) {
                resolutionFailure = exception;
                LOGGER.warn("Safra voice join could not resolve host UDP endpoint", exception);
            }
        }, runnable -> Thread.ofVirtual().name("safra-voice-join-resolve").start(runnable));
    }

    private void resolveSafraRemote() throws IOException {
        SafraRendezvousClient.JoinSession joinSession = SafraVoiceTransportManager.getInstance().joinSession();
        if (joinSession == null) {
            safraRemoteAddress = null;
            discoveredEndpoints.clear();
            return;
        }

        Map<String, P2pStunClient.DiscoveredEndpoint> candidates = stunClient.discoverCandidates(socket);
        if (candidates.isEmpty()) {
            throw new IOException("Safra voice joiner genel UDP ucu bulunamadi");
        }

        safraRemoteAddress = joinSession.resolveVoice(P2pStunClient.publicEndpoints(candidates));
        discoveredEndpoints.clear();
        discoveredEndpoints.putAll(candidates);
        LOGGER.debug("Safra voice join resolved to {}", safraRemoteAddress);
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
                LOGGER.debug("Safra voice join STUN keepalive failed: {}", exception.toString());
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
        while (!closed) {
            IOException failure = resolutionFailure;
            if (failure != null) {
                throw failure;
            }

            try {
                currentSocket.receive(packet);
                return new SafraRawUdpPacket(
                    Arrays.copyOf(packet.getData(), packet.getLength()),
                    packet.getSocketAddress(),
                    System.currentTimeMillis()
                );
            } catch (SocketTimeoutException ignored) {
            }
        }

        throw new IOException("Safra voice client socket closed");
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        DatagramSocket currentSocket = socket;
        if (currentSocket == null || currentSocket.isClosed()) {
            return;
        }

        IOException failure = resolutionFailure;
        if (failure != null) {
            throw failure;
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
        resolutionFailure = null;
        discoveredEndpoints.clear();
        CompletableFuture<Void> task = resolverTask;
        if (task != null) {
            task.cancel(true);
            resolverTask = null;
        }
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
