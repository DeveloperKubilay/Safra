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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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
    private final BlockingQueue<ReceivedDatagram> incoming = new ArrayBlockingQueue<>(P2pConstants.DATAGRAM_QUEUE_CAPACITY);
    private final Map<String, CompletableFuture<P2pTurnMessage>> pendingTransactions = new ConcurrentHashMap<>();
    private final Map<String, PeerChannel> channelsByPeer = new ConcurrentHashMap<>();
    private final Map<Integer, PeerChannel> channelsByNumber = new ConcurrentHashMap<>();
    private int nextChannel = 0x4000;
    private final ScheduledExecutorService scheduler = P2pRuntime.schedulerPool(2);
    private final String username;
    private final String credential;

    private volatile boolean closed;
    private volatile InetSocketAddress relayAddress;
    private volatile String realm = "";
    private volatile String nonce = "";
    private volatile ScheduledFuture<?> refreshTask;
    private volatile int grantedLifetimeSeconds = P2pConstants.turnAllocationLifetimeSeconds();
    private volatile long allocationExpiresAtNanos;

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
     * A relayed tunnel still carries QUIC, and QUIC inside a TCP or TLS stream stalls every datagram
     * behind one lost segment while two congestion controllers argue over the same link. So UDP is
     * asked first and the streams are kept for the networks that block UDP outright, which is what
     * they exist for. The UDP attempt keeps a shorter deadline than the streams, which have a
     * connection to establish before they can ask anything.
     */
    public static P2pTurnDatagramTransport open(Logger logger, String role, P2pTurnCredentials credentials) throws IOException {
        List<String> failures = new ArrayList<>();
        for (int attempt = 0; attempt < P2pConstants.TURN_UDP_ATTEMPTS; attempt++) {
            for (P2pTurnCredentials.TurnServer server : credentials.udpServers()) {
                try {
                    return openDatagram(logger, role, credentials, server);
                } catch (IOException exception) {
                    failures.add(refused(logger, role, "udp", server, exception));
                }
            }
        }

        for (P2pTurnCredentials.TurnServer server : preferPort(credentials.tcpServers(), 80)) {
            try {
                P2pTurnDatagramTransport transport = openStream(logger, role, credentials, server, false);
                logger.info("Safra TURN {} transport active via TCP: {}", role, server.host() + ":" + server.port());
                return transport;
            } catch (IOException exception) {
                failures.add(refused(logger, role, "tcp", server, exception));
            }
        }

        for (P2pTurnCredentials.TurnServer server : preferPort(credentials.tlsServers(), 443)) {
            try {
                P2pTurnDatagramTransport transport = openStream(logger, role, credentials, server, true);
                logger.info("Safra TURN {} transport active via TLS: {}", role, server.host() + ":" + server.port());
                return transport;
            } catch (IOException exception) {
                failures.add(refused(logger, role, "tls", server, exception));
            }
        }

        throw new IOException("TURN relay could not be opened: " + String.join(" | ", failures));
    }

    /** Which relay we had to give up on, said once, because the next one succeeding used to hide it. */
    private static String refused(Logger logger, String role, String scheme,
                                  P2pTurnCredentials.TurnServer server, IOException exception) {
        String endpoint = scheme + "://" + server.host() + ":" + server.port();
        logger.warn("Safra TURN {} could not use {}: {}", role, endpoint, exception.toString());
        return endpoint + " -> " + exception.getMessage();
    }

    private static List<P2pTurnCredentials.TurnServer> preferPort(List<P2pTurnCredentials.TurnServer> servers, int preferredPort) {
        List<P2pTurnCredentials.TurnServer> ordered = new ArrayList<>(servers);
        ordered.sort((left, right) -> Boolean.compare(left.port() != preferredPort, right.port() != preferredPort));
        return ordered;
    }

    private static P2pTurnDatagramTransport openDatagram(Logger logger, String role,
                                                          P2pTurnCredentials credentials,
                                                          P2pTurnCredentials.TurnServer server) throws IOException {
        DatagramSocket socket = null;
        P2pTurnDatagramTransport started = null;
        try {
            InetSocketAddress serverAddress = P2pTurnProtocol.resolveServer(server);
            socket = P2pSockets.datagramSocket();
            socket.connect(serverAddress);
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
            logger.info("Safra TURN {} transport active via UDP: {}", role, server.host() + ":" + server.port());
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
                parameters.setApplicationProtocols(new String[]{"stun.turn"});
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
        if (!(packet.getSocketAddress() instanceof InetSocketAddress remoteAddress) || remoteAddress.isUnresolved()) {
            throw new IOException("TURN peer address is invalid");
        }
        PeerChannel channel = ensureChannel(remoteAddress);
        byte[] payload = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
        sendBytes(P2pTurnProtocol.channelData(channel.number, payload));
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
        synchronized (scheduler) {
            if (closed) {
                return;
            }
            closed = true;
            ScheduledFuture<?> task = refreshTask;
            if (task != null) {
                task.cancel(false);
            }
            scheduler.shutdownNow();
        }
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
        channelsByPeer.clear();
        channelsByNumber.clear();
        incoming.clear();
    }

    private void start(int requestedTtlSeconds) throws IOException {
        P2pRuntime.start("safra-turn-recv-" + role, this::receiveLoop);
        allocate(requestedTtlSeconds);
        scheduleRefresh();
        scheduler.scheduleWithFixedDelay(this::renewChannels, 1L, 1L, TimeUnit.SECONDS);
    }

    private void allocate(int requestedTtlSeconds) throws IOException {
        P2pTurnMessage response = sendTurnRequest(P2pTurnProtocol.TURN_ALLOCATE_REQUEST, (out, transactionId) ->
            P2pTurnProtocol.putRequestedTransport(out, P2pTurnProtocol.REQUESTED_TRANSPORT_UDP), true);
        InetSocketAddress resolvedRelayAddress = response.xorAddress(P2pTurnProtocol.ATTR_XOR_RELAYED_ADDRESS);
        if (resolvedRelayAddress == null) {
            throw new IOException("TURN allocate response did not include a relay address");
        }
        relayAddress = resolvedRelayAddress;
        grantedLifetimeSeconds = grantedLifetime(response);
        allocationExpiresAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(grantedLifetimeSeconds);
    }

    private void refreshAllocation() throws IOException {
        P2pTurnMessage response = sendTurnRequest(P2pTurnProtocol.TURN_REFRESH_REQUEST, (out, transactionId) ->
            P2pTurnProtocol.putLifetime(out, P2pConstants.turnAllocationLifetimeSeconds()), false);
        grantedLifetimeSeconds = grantedLifetime(response);
        allocationExpiresAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(grantedLifetimeSeconds);
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
        long lifetimeMs = TimeUnit.SECONDS.toMillis(grantedLifetimeSeconds);
        long delayMs = Math.max(1L, lifetimeMs - Math.min(lifetimeMs / 2,
            TimeUnit.SECONDS.toMillis(P2pConstants.TURN_REFRESH_SAFETY_MARGIN_SECONDS)));
        logger.debug("Safra TURN {} allocation holds for {}s, renewing in {}s",
            role, grantedLifetimeSeconds, TimeUnit.MILLISECONDS.toSeconds(delayMs));
        scheduleAllocationRefresh(TimeUnit.MILLISECONDS.toNanos(delayMs));
    }

    private void scheduleAllocationRefresh(long delayNanos) {
        synchronized (scheduler) {
            if (!closed) {
                refreshTask = scheduler.schedule(this::refreshAllocationSafely, delayNanos, TimeUnit.NANOSECONDS);
            }
        }
    }

    private void refreshAllocationSafely() {
        if (closed) {
            return;
        }
        if (System.nanoTime() - allocationExpiresAtNanos >= 0L) {
            close();
            return;
        }
        try {
            refreshAllocation();
        } catch (IOException exception) {
            if (closed) {
                return;
            }
            logger.warn("Safra TURN allocation refresh failed: {}", exception.toString());
            long remainingNanos = allocationExpiresAtNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                close();
            } else {
                scheduleAllocationRefresh(Math.min(TimeUnit.SECONDS.toNanos(1L), remainingNanos));
            }
        }
    }

    private synchronized PeerChannel ensureChannel(InetSocketAddress address) throws IOException {
        String key = P2pTurnProtocol.permissionKey(address);
        PeerChannel channel = channelsByPeer.get(key);
        if (channel == null) {
            if (nextChannel > 0x4FFF) throw new IOException("TURN channel limit reached");
            channel = new PeerChannel(nextChannel++, address);
            channelsByPeer.put(key, channel);
            channelsByNumber.put(channel.number, channel);
        }
        if (System.nanoTime() - Math.min(channel.expiresAtNanos, channel.permissionExpiresAtNanos) >= 0) {
            bindChannel(channel);
        }
        return channel;
    }

    private synchronized void bindChannel(PeerChannel channel) throws IOException {
        long previousExpiry = channel.expiresAtNanos;
        channel.expiresAtNanos = Math.max(previousExpiry, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs));
        try {
            sendTurnRequest(P2pTurnProtocol.TURN_CHANNEL_BIND_REQUEST, (out, transactionId) -> {
                P2pTurnProtocol.putChannelNumber(out, channel.number);
                P2pTurnProtocol.putXorPeerAddress(out, channel.address, transactionId);
            }, false);
            channel.expiresAtNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            channel.permissionExpiresAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(permissionLifetimeSeconds());
        } catch (IOException exception) {
            channel.expiresAtNanos = previousExpiry;
            throw exception;
        }
    }

    private void receiveChannel(byte[] frame, int length) {
        if (length < 4) return;
        int number = Short.toUnsignedInt(ByteBuffer.wrap(frame, 0, 2).getShort());
        int size = Short.toUnsignedInt(ByteBuffer.wrap(frame, 2, 2).getShort());
        PeerChannel channel = channelsByNumber.get(number);
        if (channel != null && channel.expiresAtNanos - System.nanoTime() > 0 && size <= length - 4
            && length <= 4 + ((size + 3) & ~3)) {
            incoming.offer(new ReceivedDatagram(channel.address, Arrays.copyOfRange(frame, 4, size + 4)));
        }
    }

    private static int permissionLifetimeSeconds() {
        return Math.max(1, Math.min(300, P2pConstants.turnPermissionLifetimeSeconds()));
    }

    private void renewChannels() {
        long renewalNanos = TimeUnit.SECONDS.toNanos(Math.min(P2pConstants.TURN_PERMISSION_REFRESH_MARGIN_SECONDS,
            Math.max(1, permissionLifetimeSeconds() / 2)));
        for (PeerChannel channel : channelsByPeer.values()) {
            if (closed) {
                return;
            }
            if (Math.min(channel.expiresAtNanos, channel.permissionExpiresAtNanos) - System.nanoTime() > renewalNanos) {
                continue;
            }
            try {
                bindChannel(channel);
            } catch (IOException exception) {
                logger.warn("Safra TURN channel renewal failed for {}: {}", channel.address, exception.toString());
            }
        }
    }

    private void receiveLoop() {
        while (!closed) {
            P2pTurnMessage message;
            try {
                message = receiveMessage();
            } catch (IOException exception) {
                if (!closed) {
                    logger.warn("Safra TURN {} receive failed over {}: {}", role, clientTransport, exception.toString());
                    if (datagramSocket != null && !datagramSocket.isClosed()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(100L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            close();
                            return;
                        }
                        continue;
                    }
                    close();
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
            if (packet.getLength() > 0 && (buffer[0] & 0xC0) == 0x40) {
                receiveChannel(buffer, packet.getLength());
                return null;
            }
            return P2pTurnMessage.parse(packet.getData(), packet.getLength());
        }

        byte[] prefix = readFully(4);
        int bodyLength = ((prefix[2] & 0xFF) << 8) | (prefix[3] & 0xFF);
        if ((prefix[0] & 0xC0) == 0x40) {
            int padded = (bodyLength + 3) & ~3;
            byte[] frame = Arrays.copyOf(prefix, 4 + padded);
            System.arraycopy(readFully(padded), 0, frame, 4, padded);
            receiveChannel(frame, frame.length);
            return null;
        }
        byte[] header = Arrays.copyOf(prefix, P2pTurnProtocol.STUN_HEADER_SIZE);
        if ((header[0] & 0xC0) != 0) {
            throw new IOException("TURN stream returned an unsupported channel frame");
        }
        System.arraycopy(readFully(16), 0, header, 4, 16);
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

    /**
     * A stream carries a request once and the transport itself makes sure it arrives. On UDP nothing
     * does, so a single lost datagram used to end the whole attempt and drop the session to a TCP
     * relay, which is how a host and a joiner ended up on different transports and never met. RFC 5389
     * asks for the request to go again after half a second, then a second, doubling until the deadline,
     * and a retransmission repeats the transaction id so a late first answer still counts.
     */
    private P2pTurnMessage sendRequestAwait(byte[] requestBytes) throws IOException {
        byte[] transactionId = Arrays.copyOfRange(requestBytes, 8, 20);
        String key = P2pTurnProtocol.transactionKey(transactionId);
        CompletableFuture<P2pTurnMessage> future = new CompletableFuture<>();
        pendingTransactions.put(key, future);

        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs);
            long waitMs = datagramSocket == null ? requestTimeoutMs : P2pConstants.TURN_RETRANSMIT_FIRST_MS;
            while (true) {
                if (TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) <= 0L) {
                    throw new IOException("TURN request timed out");
                }
                sendBytes(requestBytes);
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0L) {
                    throw new IOException("TURN request timed out");
                }
                try {
                    return future.get(Math.min(waitMs, remainingMs), TimeUnit.MILLISECONDS);
                } catch (TimeoutException retry) {
                    if (datagramSocket == null) {
                        throw new IOException("TURN request timed out", retry);
                    }
                    waitMs *= 2;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("TURN request was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("TURN request failed", cause);
        } finally {
            pendingTransactions.remove(key);
        }
    }

    private record ReceivedDatagram(InetSocketAddress remoteAddress, byte[] data) {
    }

    private static final class PeerChannel {
        final int number;
        final InetSocketAddress address;
        volatile long expiresAtNanos = System.nanoTime();
        volatile long permissionExpiresAtNanos = expiresAtNanos;

        PeerChannel(int number, InetSocketAddress address) {
            this.number = number;
            this.address = address;
        }
    }
}
