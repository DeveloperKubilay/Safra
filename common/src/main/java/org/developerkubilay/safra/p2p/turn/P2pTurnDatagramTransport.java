package org.developerkubilay.safra.p2p.turn;

import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.P2pRuntime;
import org.developerkubilay.safra.p2p.P2pSockets;
import org.developerkubilay.safra.p2p.transport.P2pDatagramTransport;
import org.slf4j.Logger;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
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
    private final DatagramSocket datagramSocket;
    private final Socket streamSocket;
    private final InputStream streamInput;
    private final OutputStream streamOutput;
    private final InetSocketAddress serverAddress;
    private final String clientTransport;
    private final Object sendMonitor = new Object();
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

    private P2pTurnDatagramTransport(Logger logger, String role, DatagramSocket datagramSocket, Socket streamSocket,
                                     InetSocketAddress serverAddress, String clientTransport,
                                     String username, String credential) throws IOException {
        this.logger = logger;
        this.role = role;
        this.datagramSocket = datagramSocket;
        this.streamSocket = streamSocket;
        this.streamInput = streamSocket == null ? null : streamSocket.getInputStream();
        this.streamOutput = streamSocket == null ? null : streamSocket.getOutputStream();
        this.serverAddress = serverAddress;
        this.clientTransport = clientTransport;
        this.username = username;
        this.credential = credential;
    }

    public static P2pTurnDatagramTransport open(Logger logger, String role, P2pTurnCredentials credentials) throws IOException {
        List<String> failures = new ArrayList<>();
        for (P2pTurnCredentials.TurnServer server : preferPort(credentials.tlsServers(), 443)) {
            try {
                P2pTurnDatagramTransport transport = openStream(logger, role, credentials, server, true);
                logger.info("Safra TURN {} transport active via TLS: {}", role, server.host() + ":" + server.port());
                return transport;
            } catch (IOException exception) {
                failures.add("tls://" + server.host() + ":" + server.port() + " -> " + exception.getMessage());
            }
        }

        for (P2pTurnCredentials.TurnServer server : preferPort(credentials.tcpServers(), 80)) {
            try {
                P2pTurnDatagramTransport transport = openStream(logger, role, credentials, server, false);
                logger.info("Safra TURN {} transport active via TCP: {}", role, server.host() + ":" + server.port());
                return transport;
            } catch (IOException exception) {
                failures.add("tcp://" + server.host() + ":" + server.port() + " -> " + exception.getMessage());
            }
        }

        for (P2pTurnCredentials.TurnServer server : credentials.udpServers()) {
            DatagramSocket socket = P2pSockets.datagramSocket();
            InetSocketAddress serverAddress = P2pTurnProtocol.resolveServer(server);
            try {
                socket.connect(serverAddress);
                P2pTurnDatagramTransport transport = new P2pTurnDatagramTransport(
                    logger,
                    role,
                    socket,
                    null,
                    serverAddress,
                    "UDP",
                    credentials.username(),
                    credentials.credential()
                );
                transport.start(credentials.ttlSeconds());
                logger.info("Safra TURN {} transport active via UDP: {}", role, server.host() + ":" + server.port());
                return transport;
            } catch (IOException exception) {
                socket.close();
                failures.add(server.host() + ":" + server.port() + " -> " + exception.getMessage());
            }
        }

        throw new IOException("TURN relay could not be opened: " + String.join(" | ", failures));
    }

    private static List<P2pTurnCredentials.TurnServer> preferPort(List<P2pTurnCredentials.TurnServer> servers, int preferredPort) {
        List<P2pTurnCredentials.TurnServer> ordered = new ArrayList<>(servers);
        ordered.sort((left, right) -> Boolean.compare(left.port() != preferredPort, right.port() != preferredPort));
        return ordered;
    }

    private static P2pTurnDatagramTransport openStream(Logger logger, String role, P2pTurnCredentials credentials,
                                                         P2pTurnCredentials.TurnServer server, boolean tls) throws IOException {
        InetSocketAddress serverAddress = P2pTurnProtocol.resolveServer(server);
        Socket socket = new Socket();
        boolean success = false;
        try {
            socket.connect(serverAddress, P2pConstants.TURN_REQUEST_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            if (tls) {
                SSLSocket sslSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, server.host(), server.port(), true);
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[]{"stun.turn"});
                sslSocket.setSSLParameters(parameters);
                sslSocket.startHandshake();
                socket = sslSocket;
            }

            P2pTurnDatagramTransport transport = new P2pTurnDatagramTransport(
                logger,
                role,
                null,
                socket,
                serverAddress,
                tls ? "TLS" : "TCP",
                credentials.username(),
                credentials.credential()
            );
            transport.start(credentials.ttlSeconds());
            success = true;
            return transport;
        } finally {
            if (!success) {
                socket.close();
            }
        }
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
        if (!(packet.getSocketAddress() instanceof InetSocketAddress remoteAddress)) {
            throw new IOException("TURN peer address is invalid");
        }
        ensurePermission(remoteAddress);
        byte[] payload = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
        byte[] indication = P2pTurnProtocol.buildSendIndication(random, remoteAddress, payload);
        sendBytes(indication);
    }

    @Override
    public int getLocalPort() {
        return datagramSocket != null ? datagramSocket.getLocalPort() : streamSocket.getLocalPort();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return datagramSocket != null ? datagramSocket.getLocalSocketAddress() : streamSocket.getLocalSocketAddress();
    }

    @Override
    public boolean isClosed() {
        return closed
            || (datagramSocket != null && datagramSocket.isClosed())
            || (streamSocket != null && streamSocket.isClosed());
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
        if (datagramSocket != null) {
            datagramSocket.close();
        }
        if (streamSocket != null) {
            try {
                streamSocket.close();
            } catch (IOException ignored) {
            }
        }
        pendingTransactions.values().forEach(future -> future.completeExceptionally(new IOException("TURN transport is closed")));
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
        while (!closed) {
            P2pTurnMessage message;
            try {
                message = receiveMessage();
            } catch (IOException exception) {
                if (!closed) {
                    logger.warn("Safra TURN {} receive failed over {}: {}", role, clientTransport, exception.toString());
                }
                return;
            }

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

    private P2pTurnMessage receiveMessage() throws IOException {
        if (datagramSocket != null) {
            byte[] buffer = new byte[65535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            datagramSocket.receive(packet);
            return P2pTurnMessage.parse(packet.getData(), packet.getLength());
        }

        byte[] header = readFully(P2pTurnProtocol.STUN_HEADER_SIZE);
        if ((header[0] & 0xC0) != 0) {
            throw new IOException("TURN stream returned an unsupported channel frame");
        }
        int bodyLength = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (bodyLength < 0 || bodyLength > 65515) {
            throw new IOException("TURN stream returned an invalid message length");
        }
        byte[] encoded = Arrays.copyOf(header, P2pTurnProtocol.STUN_HEADER_SIZE + bodyLength);
        byte[] body = readFully(bodyLength);
        System.arraycopy(body, 0, encoded, P2pTurnProtocol.STUN_HEADER_SIZE, bodyLength);
        return P2pTurnMessage.parse(encoded, encoded.length);
    }

    private byte[] readFully(int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = streamInput.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException("TURN stream closed");
            }
            offset += read;
        }
        return data;
    }

    private void sendBytes(byte[] bytes) throws IOException {
        synchronized (sendMonitor) {
            if (datagramSocket != null) {
                datagramSocket.send(new DatagramPacket(bytes, bytes.length));
                return;
            }
            streamOutput.write(bytes);
            streamOutput.flush();
        }
    }

    private P2pTurnMessage sendTurnRequest(int requestType, P2pTurnProtocol.AttributeWriter writer, boolean challengeFirst) throws IOException {
        if (challengeFirst || realm.isBlank() || nonce.isBlank()) {
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
        if (newRealm.isBlank() || newNonce.isBlank()) {
            throw new IOException("TURN auth challenge returned missing realm/nonce");
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
            sendBytes(requestBytes);
            return future.get(P2pConstants.TURN_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("TURN request was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("TURN request failed", cause);
        } catch (TimeoutException exception) {
            throw new IOException("TURN request timed out", exception);
        } finally {
            pendingTransactions.remove(key);
        }
    }

    private record ReceivedDatagram(InetSocketAddress remoteAddress, byte[] data) {
    }
}
