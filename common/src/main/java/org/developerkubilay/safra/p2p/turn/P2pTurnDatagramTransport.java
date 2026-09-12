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
import java.nio.ByteBuffer;
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
    private final int requestTimeoutMs;
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
    private volatile int grantedLifetimeSeconds = P2pConstants.turnAllocationLifetimeSeconds();

    private P2pTurnDatagramTransport(Logger logger, String role, DatagramSocket datagramSocket, Socket streamSocket,
                                     InetSocketAddress serverAddress, String clientTransport, int requestTimeoutMs,
                                     String username, String credential) throws IOException {
        this.logger = logger;
        this.role = role;
        this.datagramSocket = datagramSocket;
        this.streamSocket = streamSocket;
        this.streamInput = streamSocket == null ? null : streamSocket.getInputStream();
        this.streamOutput = streamSocket == null ? null : streamSocket.getOutputStream();
        this.serverAddress = serverAddress;
        this.clientTransport = clientTransport;
        this.requestTimeoutMs = requestTimeoutMs;
        this.username = username;
        this.credential = credential;
    }

    /**
     * A relayed tunnel carries its own retransmissions, and putting those inside a TCP or TLS stream
     * stalls every datagram behind one lost segment while two retransmit timers argue over the same
     * link. So UDP is asked first and the streams are kept for the networks that block UDP outright,
     * which is what they exist for. The UDP list is walked twice before it is given up on: only one
     * relay offers UDP, so a single transient timeout used to sentence the whole session to TCP.
     */
    public static P2pTurnDatagramTransport open(Logger logger, String role, P2pTurnCredentials credentials) throws IOException {
        List<String> failures = new ArrayList<>();
        trace("turn " + role + " open start servers=" + describeServers(credentials)
            + " timeoutMs=" + P2pConstants.TURN_REQUEST_TIMEOUT_MS);
        for (int attempt = 0; attempt < P2pConstants.TURN_UDP_ATTEMPTS; attempt++) {
            for (P2pTurnCredentials.TurnServer server : credentials.udpServers()) {
                try {
                    return openDatagram(logger, role, credentials, server);
                } catch (IOException exception) {
                    failures.add("udp://" + describeServer(server) + " -> " + exception.getMessage());
                    trace("turn " + role + " failed server=" + describeServer(server) + " error=" + exception.toString());
                }
            }
        }

        for (P2pTurnCredentials.TurnServer server : preferPort(credentials.tcpServers(), 80)) {
            try {
                return openStream(logger, role, credentials, server, false);
            } catch (IOException exception) {
                failures.add("tcp://" + describeServer(server) + " -> " + exception.getMessage());
            }
        }

        for (P2pTurnCredentials.TurnServer server : preferPort(credentials.tlsServers(), 443)) {
            try {
                return openStream(logger, role, credentials, server, true);
            } catch (IOException exception) {
                failures.add("tls://" + describeServer(server) + " -> " + exception.getMessage());
            }
        }

        throw new IOException("TURN relay acilamadi: " + String.join(" | ", failures));
    }

    private static P2pTurnDatagramTransport openDatagram(Logger logger, String role, P2pTurnCredentials credentials,
                                                         P2pTurnCredentials.TurnServer server) throws IOException {
        DatagramSocket socket = null;
        P2pTurnDatagramTransport started = null;
        try {
            trace("turn " + role + " trying server=" + describeServer(server));
            InetSocketAddress serverAddress = P2pTurnProtocol.resolveServer(server);
            trace("turn " + role + " resolved server=" + describeServer(server) + " -> " + serverAddress);
            socket = P2pSockets.datagramSocket();
            socket.connect(serverAddress);
            trace("turn " + role + " connected local=" + socket.getLocalSocketAddress() + " remote=" + serverAddress);
            P2pTurnDatagramTransport transport = new P2pTurnDatagramTransport(
                logger,
                role,
                socket,
                null,
                serverAddress,
                "UDP",
                P2pConstants.TURN_UDP_REQUEST_TIMEOUT_MS,
                credentials.username(),
                credentials.credential()
            );
            started = transport;
            transport.start(credentials.ttlSeconds());
            trace("turn " + role + " ready relay=" + transport.relayAddress + " via=" + serverAddress);
            logger.info("Safra TURN {} transport active via UDP: {}", role, describeServer(server));
            return transport;
        } catch (IOException exception) {
            if (started != null) {
                started.close();
            } else if (socket != null) {
                socket.close();
            }
            throw exception;
        }
    }

    private static List<P2pTurnCredentials.TurnServer> preferPort(List<P2pTurnCredentials.TurnServer> servers, int preferredPort) {
        List<P2pTurnCredentials.TurnServer> ordered = new ArrayList<P2pTurnCredentials.TurnServer>(servers);
        java.util.Collections.sort(ordered, (left, right) -> Boolean.compare(left.port() != preferredPort, right.port() != preferredPort));
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
            socket.setSoTimeout(P2pConstants.TURN_REQUEST_TIMEOUT_MS);
            if (tls) {
                SSLSocket sslSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, server.host(), server.port(), true);
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                sslSocket.setSSLParameters(parameters);
                sslSocket.startHandshake();
                socket = sslSocket;
            }
            socket.setSoTimeout(0);

            P2pTurnDatagramTransport transport = new P2pTurnDatagramTransport(
                logger,
                role,
                null,
                socket,
                serverAddress,
                tls ? "TLS" : "TCP",
                P2pConstants.TURN_REQUEST_TIMEOUT_MS,
                credentials.username(),
                credentials.credential()
            );
            transport.start(credentials.ttlSeconds());
            success = true;
            logger.info("Safra TURN {} transport active via {}: {}", role, tls ? "TLS" : "TCP", describeServer(server));
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
                throw new IOException("TURN receive yarida kesildi", exception);
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

        throw new IOException("TURN transport kapali");
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        if (closed) {
            throw new IOException("TURN transport kapali");
        }
        if (!(packet.getSocketAddress() instanceof InetSocketAddress)) {
            throw new IOException("TURN peer adresi gecersiz");
        }
        InetSocketAddress remoteAddress = (InetSocketAddress) packet.getSocketAddress();
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
        return closed || (datagramSocket != null && datagramSocket.isClosed())
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
        pendingTransactions.values().forEach(future -> future.completeExceptionally(new IOException("TURN transport kapandi")));
        pendingTransactions.clear();
        incoming.clear();
    }

    private void start(int requestedTtlSeconds) throws IOException {
        trace("turn " + role + " start ttl=" + requestedTtlSeconds + " server=" + serverAddress);
        P2pRuntime.start("safra-turn-recv-" + role, this::receiveLoop);
        allocate(requestedTtlSeconds);
        scheduleRefresh();
    }

    private void allocate(int requestedTtlSeconds) throws IOException {
        trace("turn " + role + " allocate begin server=" + serverAddress + " ttl=" + requestedTtlSeconds);
        P2pTurnMessage response = sendTurnRequest(P2pTurnProtocol.TURN_ALLOCATE_REQUEST, (out, transactionId) ->
            P2pTurnProtocol.putRequestedTransport(out, P2pTurnProtocol.REQUESTED_TRANSPORT_UDP), true);
        InetSocketAddress resolvedRelayAddress = response.xorAddress(P2pTurnProtocol.ATTR_XOR_RELAYED_ADDRESS);
        if (resolvedRelayAddress == null) {
            throw new IOException("TURN allocate cevabinda relay adresi yok");
        }
        relayAddress = resolvedRelayAddress;
        grantedLifetimeSeconds = grantedLifetime(response);
        trace("turn " + role + " allocate ok relay=" + resolvedRelayAddress + " server=" + serverAddress
            + " lifetime=" + grantedLifetimeSeconds);
    }

    private void refreshAllocation() throws IOException {
        P2pTurnMessage response = sendTurnRequest(P2pTurnProtocol.TURN_REFRESH_REQUEST, (out, transactionId) ->
            P2pTurnProtocol.putLifetime(out, P2pConstants.turnAllocationLifetimeSeconds()), false);
        grantedLifetimeSeconds = grantedLifetime(response);
        scheduleRefresh();
    }

    /**
     * What we asked for is a request; the server answers with what it is willing to hold, and RFC 8656
     * says that answer is the one to keep time by. Renewing on our own figure works right up until a
     * server offers less than it was asked for, and then the allocation dies mid-session while the
     * refresh is still waiting its turn.
     */
    private int grantedLifetime(P2pTurnMessage response) {
        byte[] lifetime = response.attribute(P2pTurnProtocol.ATTR_LIFETIME);
        if (lifetime == null || lifetime.length < 4) {
            return P2pConstants.turnAllocationLifetimeSeconds();
        }

        long seconds = ByteBuffer.wrap(lifetime, 0, 4).getInt() & 0xFFFFFFFFL;
        return seconds <= 0 ? P2pConstants.turnAllocationLifetimeSeconds() : (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    private void scheduleRefresh() {
        ScheduledFuture<?> currentTask = refreshTask;
        if (currentTask != null) {
            currentTask.cancel(false);
        }
        long delayMs = Math.max(
            TimeUnit.SECONDS.toMillis(30L),
            TimeUnit.SECONDS.toMillis(grantedLifetimeSeconds)
                - TimeUnit.SECONDS.toMillis(P2pConstants.TURN_REFRESH_SAFETY_MARGIN_SECONDS)
        );
        logger.debug("Safra TURN {} allocation holds for {}s, renewing in {}s",
            role, grantedLifetimeSeconds, TimeUnit.MILLISECONDS.toSeconds(delayMs));
        refreshTask = scheduler.schedule(this::refreshAllocationSafely, delayMs, TimeUnit.MILLISECONDS);
    }

    private void refreshAllocationSafely() {
        if (closed) {
            return;
        }
        try {
            refreshAllocation();
        } catch (IOException exception) {
            logger.warn("Safra TURN allocation refresh patladi: {}", exception.toString());
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

    private P2pTurnMessage sendTurnRequest(int requestType, P2pTurnProtocol.AttributeWriter writer, boolean challengeFirst) throws IOException {
        String requestName = requestName(requestType);
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
                trace("turn " + role + " " + requestName + " success-without-auth server=" + serverAddress);
                return challenge;
            }
            trace("turn " + role + " " + requestName + " auth-challenge code=" + challenge.errorCode()
                + " reason=" + challenge.errorReason());
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
            trace("turn " + role + " " + requestName + " stale-nonce server=" + serverAddress);
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
            trace("turn " + role + " " + requestName + " failed code=" + response.errorCode()
                + " reason=" + response.errorReason());
            throw P2pTurnProtocol.turnError(requestType, response);
        }
        trace("turn " + role + " " + requestName + " success server=" + serverAddress);
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
            throw new IOException("TURN auth challenge eksik realm/nonce dondu");
        }

        realm = newRealm;
        nonce = newNonce;
    }

    /**
     * A stream carries a request once and the transport itself makes sure it arrives. On UDP nothing
     * does, so a single lost datagram ended the whole attempt and dropped the session to a stream
     * relay, which is how a host and a joiner ended up on different transports and never met. RFC 5389
     * asks for the request to go again after half a second, then a second, doubling until the deadline,
     * and a retransmission repeats the transaction id so a late first answer still counts.
     */
    private P2pTurnMessage sendRequestAwait(byte[] requestBytes) throws IOException {
        int requestType = messageType(requestBytes);
        byte[] transactionId = Arrays.copyOfRange(requestBytes, 8, 20);
        String key = P2pTurnProtocol.transactionKey(transactionId);
        String shortKey = key.length() > 8 ? key.substring(0, 8) : key;
        CompletableFuture<P2pTurnMessage> future = new CompletableFuture<>();
        pendingTransactions.put(key, future);

        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs);
            long waitMs = datagramSocket == null ? requestTimeoutMs : P2pConstants.TURN_RETRANSMIT_FIRST_MS;
            while (true) {
                trace("turn " + role + " send " + requestName(requestType) + " tx=" + shortKey + " server=" + serverAddress);
                sendBytes(requestBytes);
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0L) {
                    trace("turn " + role + " timeout " + requestName(requestType) + " tx=" + shortKey + " server=" + serverAddress);
                    throw new IOException("TURN istegi zaman asimina ugradi");
                }
                try {
                    P2pTurnMessage response = future.get(Math.min(waitMs, remainingMs), TimeUnit.MILLISECONDS);
                    trace("turn " + role + " recv " + requestName(response.type()) + " tx=" + shortKey
                        + " code=" + response.errorCode() + " server=" + serverAddress);
                    return response;
                } catch (TimeoutException retry) {
                    if (datagramSocket == null) {
                        trace("turn " + role + " timeout " + requestName(requestType) + " tx=" + shortKey + " server=" + serverAddress);
                        throw new IOException("TURN istegi zaman asimina ugradi", retry);
                    }
                    waitMs *= 2L;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("TURN istegi yarida kesildi", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("TURN istegi basarisiz", cause);
        } finally {
            pendingTransactions.remove(key);
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
        if (bodyLength > 65515) {
            throw new IOException("TURN stream returned an invalid message length");
        }
        byte[] encoded = Arrays.copyOf(header, P2pTurnProtocol.STUN_HEADER_SIZE + bodyLength);
        System.arraycopy(readFully(bodyLength), 0, encoded, P2pTurnProtocol.STUN_HEADER_SIZE, bodyLength);
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

    private static boolean forceDirectThenTurnRelay() {
        String property = System.getProperty("safra.p2p.forceDirectThenTurn");
        if (property != null && !property.trim().isEmpty()) {
            return Boolean.parseBoolean(property.trim());
        }

        String environment = System.getenv("SAFRA_FORCE_DIRECT_THEN_TURN");
        return environment != null && !environment.trim().isEmpty() && Boolean.parseBoolean(environment.trim());
    }

    private static String describeServers(P2pTurnCredentials credentials) {
        if (credentials == null || credentials.udpServers() == null || credentials.udpServers().isEmpty()) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < credentials.udpServers().size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(describeServer(credentials.udpServers().get(index)));
        }
        builder.append(']');
        return builder.toString();
    }

    private static String describeServer(P2pTurnCredentials.TurnServer server) {
        return server.host() + ":" + server.port();
    }

    private static int messageType(byte[] requestBytes) {
        if (requestBytes == null || requestBytes.length < 2) {
            return 0;
        }
        return ((requestBytes[0] & 0xFF) << 8) | (requestBytes[1] & 0xFF);
    }

    private static String requestName(int requestType) {
        switch (requestType) {
            case P2pTurnProtocol.TURN_ALLOCATE_REQUEST:
                return "allocate";
            case P2pTurnProtocol.TURN_ALLOCATE_RESPONSE:
                return "allocate-response";
            case P2pTurnProtocol.TURN_REFRESH_REQUEST:
                return "refresh";
            case P2pTurnProtocol.TURN_REFRESH_RESPONSE:
                return "refresh-response";
            case P2pTurnProtocol.TURN_CREATE_PERMISSION_REQUEST:
                return "create-permission";
            case P2pTurnProtocol.TURN_CREATE_PERMISSION_RESPONSE:
                return "create-permission-response";
            default:
                return "type-" + requestType;
        }
    }

    private static void trace(String message) {
        if (P2pConstants.traceLoggingEnabled()) {
            System.out.println("[Safra P2P] " + message);
        }
    }

    private static final class ReceivedDatagram {
        private final InetSocketAddress remoteAddress;
        private final byte[] data;

        private ReceivedDatagram(InetSocketAddress remoteAddress, byte[] data) {
            this.remoteAddress = remoteAddress;
            this.data = data;
        }

        private InetSocketAddress remoteAddress() {
            return remoteAddress;
        }

        private byte[] data() {
            return data;
        }
    }
}
