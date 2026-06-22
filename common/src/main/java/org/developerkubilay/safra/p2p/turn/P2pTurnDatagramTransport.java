package org.developerkubilay.safra.p2p.turn;

import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.P2pRuntime;
import org.developerkubilay.safra.p2p.P2pSockets;
import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class P2pTurnDatagramTransport implements P2pDatagramTransport {
    private final Logger logger;
    private final String role;
    private final DatagramSocket socket;
    private final InetSocketAddress serverAddress;
    private final SecureRandom random = new SecureRandom();
    private final BlockingQueue<ReceivedDatagram> incoming = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<P2pTurnMessage>> pendingTransactions = new ConcurrentHashMap<>();
    private final Map<String, Long> permissionExpirations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = P2pRuntime.singleScheduler();
    private final String username;
    private final String credential;

    private volatile boolean closed;
    private volatile InetSocketAddress relayAddress;
    private volatile String realm = "";
    private volatile String nonce = "";
    private volatile ScheduledFuture<?> refreshTask;

    private P2pTurnDatagramTransport(Logger logger, String role, DatagramSocket socket, InetSocketAddress serverAddress,
                                     String username, String credential) {
        this.logger = logger;
        this.role = role;
        this.socket = socket;
        this.serverAddress = serverAddress;
        this.username = username;
        this.credential = credential;
    }

    public static P2pTurnDatagramTransport open(Logger logger, String role, P2pTurnCredentials credentials) throws IOException {
        List<String> failures = new ArrayList<>();
        for (P2pTurnCredentials.TurnServer server : credentials.udpServers()) {
            DatagramSocket socket = P2pSockets.datagramSocket();
            InetSocketAddress serverAddress = P2pTurnProtocol.resolveServer(server);
            try {
                socket.connect(serverAddress);
                P2pTurnDatagramTransport transport = new P2pTurnDatagramTransport(
                    logger,
                    role,
                    socket,
                    serverAddress,
                    credentials.username(),
                    credentials.credential()
                );
                transport.start(credentials.ttlSeconds());
                logger.debug("Safra TURN {} relay aktif {} uzerinden {}", role, transport.relayAddress, serverAddress);
                return transport;
            } catch (IOException exception) {
                socket.close();
                failures.add(server.host() + ":" + server.port() + " -> " + exception.getMessage());
            }
        }

        throw new IOException("Could not open TURN relay: " + String.join(" | ", failures));
    }

    public InetSocketAddress relayAddress() {
        return relayAddress;
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        while (!closed) {
            ReceivedDatagram datagram;
            try {
                datagram = incoming.poll(1L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("TURN receive was interrupted", exception);
            }
            if (datagram == null) {
                continue;
            }

            byte[] target = packet.getData();
            int offset = packet.getOffset();
            int writable = Math.max(0, target.length - offset);
            int length = Math.min(writable, datagram.data().length);
            System.arraycopy(datagram.data(), 0, target, offset, length);
            packet.setSocketAddress(datagram.remoteAddress());
            packet.setLength(length);
            return;
        }

        throw new IOException("TURN transport is closed");
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        if (closed) {
            throw new IOException("TURN transport is closed");
        }
        if (!(packet.getSocketAddress() instanceof InetSocketAddress)) {
            throw new IOException("TURN peer address is invalid");
        }
        InetSocketAddress remoteAddress = (InetSocketAddress) packet.getSocketAddress();
        ensurePermission(remoteAddress);
        byte[] payload = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
        byte[] indication = P2pTurnProtocol.buildSendIndication(random, remoteAddress, payload);
        synchronized (socket) {
            socket.send(new DatagramPacket(indication, indication.length));
        }
    }

    @Override
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return socket.getLocalSocketAddress();
    }

    @Override
    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> task = refreshTask;
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdownNow();
        socket.close();
        pendingTransactions.values().forEach(future -> future.completeExceptionally(new IOException("TURN transport was closed")));
        pendingTransactions.clear();
        incoming.clear();
    }

    private void start(int requestedTtlSeconds) throws IOException {
        P2pRuntime.start("safra-turn-recv-" + role, this::receiveLoop);
        allocate(requestedTtlSeconds);
        scheduleRefresh();
    }

    private void allocate(int requestedTtlSeconds) throws IOException {
        P2pTurnMessage response = sendTurnRequest(P2pTurnProtocol.TURN_ALLOCATE_REQUEST, (out, transactionId) ->
            P2pTurnProtocol.putRequestedTransport(out, P2pTurnProtocol.REQUESTED_TRANSPORT_UDP), true);
        InetSocketAddress resolvedRelayAddress = response.xorAddress(P2pTurnProtocol.ATTR_XOR_RELAYED_ADDRESS);
        if (resolvedRelayAddress == null) {
            throw new IOException("TURN allocate response did not include a relay address");
        }
        relayAddress = resolvedRelayAddress;
    }

    private void refreshAllocation() throws IOException {
        sendTurnRequest(P2pTurnProtocol.TURN_REFRESH_REQUEST, (out, transactionId) ->
            P2pTurnProtocol.putLifetime(out, P2pConstants.turnAllocationLifetimeSeconds()), false);
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        ScheduledFuture<?> currentTask = refreshTask;
        if (currentTask != null) {
            currentTask.cancel(false);
        }
        long delayMs = Math.max(
            TimeUnit.SECONDS.toMillis(30L),
            TimeUnit.SECONDS.toMillis(P2pConstants.turnAllocationLifetimeSeconds())
                - TimeUnit.SECONDS.toMillis(P2pConstants.TURN_REFRESH_SAFETY_MARGIN_SECONDS)
        );
        refreshTask = scheduler.schedule(this::refreshAllocationSafely, delayMs, TimeUnit.MILLISECONDS);
    }

    private void refreshAllocationSafely() {
        if (closed) {
            return;
        }
        try {
            refreshAllocation();
        } catch (IOException exception) {
            logger.warn("Safra TURN allocation refresh failed: {}", exception.toString());
            close();
        }
    }

    private synchronized void ensurePermission(InetSocketAddress remoteAddress) throws IOException {
        String key = P2pTurnProtocol.permissionKey(remoteAddress);
        long now = System.currentTimeMillis();
        Long expiresAt = permissionExpirations.get(key);
        if (expiresAt != null && expiresAt - now > TimeUnit.SECONDS.toMillis(P2pConstants.TURN_PERMISSION_REFRESH_MARGIN_SECONDS)) {
            return;
        }

        sendTurnRequest(P2pTurnProtocol.TURN_CREATE_PERMISSION_REQUEST, (out, transactionId) ->
            P2pTurnProtocol.putXorPeerAddress(out, remoteAddress, transactionId), false);
        permissionExpirations.put(
            key,
            now + TimeUnit.SECONDS.toMillis(P2pConstants.turnPermissionLifetimeSeconds())
        );
    }

    private void receiveLoop() {
        byte[] buffer = new byte[65535];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException exception) {
                if (!closed) {
                    logger.debug("TURN UDP receive failed: {}", exception.toString());
                }
                return;
            }

            P2pTurnMessage message = P2pTurnMessage.parse(packet.getData(), packet.getLength());
            if (message == null) {
                continue;
            }

            if (message.type() == P2pTurnProtocol.TURN_DATA_INDICATION) {
                InetSocketAddress remoteAddress = message.xorAddress(P2pTurnProtocol.ATTR_XOR_PEER_ADDRESS);
                byte[] data = message.attribute(P2pTurnProtocol.ATTR_DATA);
                if (remoteAddress != null && data != null) {
                    incoming.offer(new ReceivedDatagram(remoteAddress, data));
                }
                continue;
            }

            CompletableFuture<P2pTurnMessage> future = pendingTransactions.remove(P2pTurnProtocol.transactionKey(message.transactionId()));
            if (future != null) {
                future.complete(message);
            }
        }
    }

    private P2pTurnMessage sendTurnRequest(int requestType, P2pTurnProtocol.AttributeWriter writer, boolean challengeFirst) throws IOException {
        if (challengeFirst || realm.trim().isEmpty() || nonce.trim().isEmpty()) {
            P2pTurnMessage challenge = sendRequestAwait(P2pTurnProtocol.buildRequest(
                random,
                username,
                realm,
                nonce,
                credential,
                requestType,
                writer,
                P2pTurnProtocol.AuthMode.NONE
            ));
            if (challenge.type() == P2pTurnProtocol.expectedSuccessType(requestType)) {
                return challenge;
            }
            handleAuthChallenge(challenge, requestType);
        }

        P2pTurnMessage response = sendRequestAwait(P2pTurnProtocol.buildRequest(
            random,
            username,
            realm,
            nonce,
            credential,
            requestType,
            writer,
            P2pTurnProtocol.AuthMode.LONG_TERM
        ));
        if (response.errorCode() == P2pTurnProtocol.ERROR_STALE_NONCE) {
            handleAuthChallenge(response, requestType);
            response = sendRequestAwait(P2pTurnProtocol.buildRequest(
                random,
                username,
                realm,
                nonce,
                credential,
                requestType,
                writer,
                P2pTurnProtocol.AuthMode.LONG_TERM
            ));
        }
        if (response.type() != P2pTurnProtocol.expectedSuccessType(requestType)) {
            throw P2pTurnProtocol.turnError(requestType, response);
        }
        return response;
    }

    private void handleAuthChallenge(P2pTurnMessage response, int requestType) throws IOException {
        int errorCode = response.errorCode();
        if (errorCode != P2pTurnProtocol.ERROR_UNAUTHORIZED && errorCode != P2pTurnProtocol.ERROR_STALE_NONCE) {
            throw P2pTurnProtocol.turnError(requestType, response);
        }

        String newRealm = response.stringAttribute(P2pTurnProtocol.ATTR_REALM);
        String newNonce = response.stringAttribute(P2pTurnProtocol.ATTR_NONCE);
        if (newRealm.trim().isEmpty() || newNonce.trim().isEmpty()) {
            throw new IOException("TURN auth challenge returned missing realm or nonce");
        }

        realm = newRealm;
        nonce = newNonce;
    }

    private P2pTurnMessage sendRequestAwait(byte[] requestBytes) throws IOException {
        byte[] transactionId = Arrays.copyOfRange(requestBytes, 8, 20);
        String key = P2pTurnProtocol.transactionKey(transactionId);
        CompletableFuture<P2pTurnMessage> future = new CompletableFuture<>();
        pendingTransactions.put(key, future);

        try {
            synchronized (socket) {
                socket.send(new DatagramPacket(requestBytes, requestBytes.length));
            }
            return future.get(P2pConstants.TURN_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("TURN request was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("TURN request failed", cause);
        } catch (TimeoutException exception) {
            throw new IOException("TURN request timed out", exception);
        } finally {
            pendingTransactions.remove(key);
        }
    }

    private static final class ReceivedDatagram {
        private final InetSocketAddress remoteAddress;
        private final byte[] data;

        ReceivedDatagram(InetSocketAddress remoteAddress, byte[] data) {
            this.remoteAddress = remoteAddress;
            this.data = data;
        }

        InetSocketAddress remoteAddress() {
            return remoteAddress;
        }

        byte[] data() {
            return data;
        }
    }
}
