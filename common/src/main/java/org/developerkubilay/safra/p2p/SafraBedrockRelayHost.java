package org.developerkubilay.safra.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class SafraBedrockRelayHost implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SafraBedrockRelayHost.class);
    private static final byte[] HOST_HELLO = "BRLY_HOST".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HOST_OK = "BRLY_OK".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_DATAGRAM_SIZE = 65_507;
    private static final long PLAYER_IDLE_MS = 150_000L;
    private static final long HOST_HEARTBEAT_SECONDS = 15L;
    private final String sessionCode;
    private final Consumer<String> readyHandler;
    private final Runnable unavailableHandler;
    private final ScheduledExecutorService scheduler = P2pRuntime.schedulerPool(1);
    private final Map<Integer, PlayerTunnel> players = new ConcurrentHashMap<>();

    private volatile DatagramSocket relaySocket;
    private volatile InetSocketAddress relayAddress;
    private volatile InetSocketAddress geyserAddress;
    private volatile boolean closed;

    SafraBedrockRelayHost(String sessionCode, Consumer<String> readyHandler, Runnable unavailableHandler) {
        this.sessionCode = sessionCode;
        this.readyHandler = readyHandler;
        this.unavailableHandler = unavailableHandler;
    }

    void start() {
        P2pRuntime.start("safra-bedrock-relay-start", this::startRelay);
    }

    private void startRelay() {
        try {
            P2pOptionalIntegrations.GeyserListener listener = awaitGeyserListener();
            if (listener == null || closed) {
                return;
            }
            geyserAddress = resolveGeyserAddress(listener);

            SafraRendezvousClient.BedrockRelay relayRequest = SafraRendezvousClient.requestBedrockRelay(sessionCode);
            if (relayRequest == null || closed) {
                notifyUnavailable();
                return;
            }

            relayAddress = new InetSocketAddress(InetAddress.getByName(stripBrackets(relayRequest.host())), relayRequest.port());
            DatagramSocket socket = new DatagramSocket();
            socket.connect(relayAddress);
            socket.setSoTimeout(1_000);
            relaySocket = socket;

            if (!registerHost(socket) || closed) {
                notifyUnavailable();
                return;
            }

            readyHandler.accept(displayAddress(relayAddress));
            scheduler.scheduleAtFixedRate(this::sendHeartbeat, HOST_HEARTBEAT_SECONDS, HOST_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(this::removeIdlePlayers, 30, 30, TimeUnit.SECONDS);
            receiveRelayPackets(socket);
        } catch (IOException | RuntimeException exception) {
            if (!closed) {
                LOGGER.warn("Safra Bedrock relay could not start: {}", exception.toString());
                notifyUnavailable();
            }
        }
    }

    private P2pOptionalIntegrations.GeyserListener awaitGeyserListener() {
        for (int attempt = 0; attempt < 20 && !closed; attempt++) {
            P2pOptionalIntegrations.GeyserListener listener = P2pOptionalIntegrations.geyserListener();
            if (listener != null) {
                return listener;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (!closed) {
            LOGGER.warn("Safra found Geyser but its Bedrock listener was not ready");
        }
        return null;
    }

    private boolean registerHost(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[32];
        for (int attempt = 0; attempt < 10 && !closed; attempt++) {
            socket.send(new DatagramPacket(HOST_HELLO, HOST_HELLO.length));
            try {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                if (response.getLength() == HOST_OK.length
                    && Arrays.equals(HOST_OK, Arrays.copyOf(response.getData(), response.getLength()))) {
                    return true;
                }
            } catch (SocketTimeoutException ignored) {
            }
        }
        return false;
    }

    private void receiveRelayPackets(DatagramSocket socket) {
        byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (packet.getLength() == HOST_OK.length
                    && Arrays.equals(HOST_OK, Arrays.copyOf(packet.getData(), packet.getLength()))) {
                    continue;
                }
                if (packet.getLength() < 3) {
                    continue;
                }
                int playerId = (packet.getData()[0] & 0xff) << 8 | packet.getData()[1] & 0xff;
                PlayerTunnel player = players.computeIfAbsent(playerId, this::createPlayerTunnel);
                if (player != null) {
                    try {
                        player.sendToGeyser(packet.getData(), 2, packet.getLength() - 2);
                    } catch (IOException exception) {
                        if (players.remove(playerId, player)) {
                            player.close();
                        }
                        LOGGER.debug("Safra local Geyser tunnel {} send failed: {}", playerId, exception.toString());
                    }
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException exception) {
                if (!closed) {
                    LOGGER.warn("Safra Bedrock relay receive failed: {}", exception.toString());
                }
                return;
            }
        }
    }

    private PlayerTunnel createPlayerTunnel(int playerId) {
        try {
            PlayerTunnel player = new PlayerTunnel(playerId, geyserAddress);
            player.start();
            return player;
        } catch (IOException exception) {
            LOGGER.warn("Safra could not open local Geyser tunnel for Bedrock player {}: {}", playerId, exception.toString());
            return null;
        }
    }

    private void sendHeartbeat() {
        DatagramSocket socket = relaySocket;
        if (closed || socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.send(new DatagramPacket(HOST_HELLO, HOST_HELLO.length));
        } catch (IOException exception) {
            if (!closed) {
                LOGGER.warn("Safra Bedrock relay heartbeat failed: {}", exception.toString());
            }
        }
    }

    private void removeIdlePlayers() {
        long cutoff = System.currentTimeMillis() - PLAYER_IDLE_MS;
        players.forEach((playerId, player) -> {
            if (player.lastSeen < cutoff && players.remove(playerId, player)) {
                player.close();
            }
        });
    }

    private void notifyUnavailable() {
        if (!closed) {
            close();
            unavailableHandler.run();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdownNow();
        DatagramSocket socket = relaySocket;
        if (socket != null) {
            socket.close();
        }
        players.values().forEach(PlayerTunnel::close);
        players.clear();
    }

    private InetSocketAddress resolveGeyserAddress(P2pOptionalIntegrations.GeyserListener listener) throws IOException {
        String host = stripBrackets(listener.address());
        InetAddress address = InetAddress.getByName(host);
        if (address.isAnyLocalAddress()) {
            address = InetAddress.getByName(host.contains(":") ? "::1" : "127.0.0.1");
        }
        return new InetSocketAddress(address, listener.port());
    }

    private String displayAddress(InetSocketAddress address) {
        String host = address.getAddress().getHostAddress();
        return host.contains(":") ? "[" + host + "]:" + address.getPort() : host + ":" + address.getPort();
    }

    private String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private final class PlayerTunnel implements AutoCloseable {
        private final int playerId;
        private final DatagramSocket socket;
        private volatile long lastSeen = System.currentTimeMillis();

        private PlayerTunnel(int playerId, InetSocketAddress localGeyserAddress) throws IOException {
            this.playerId = playerId;
            socket = new DatagramSocket();
            socket.connect(localGeyserAddress);
            socket.setSoTimeout(1_000);
        }

        private void start() {
            P2pRuntime.start("safra-bedrock-player-" + playerId, this::receiveFromGeyser);
        }

        private void sendToGeyser(byte[] data, int offset, int length) throws IOException {
            socket.send(new DatagramPacket(data, offset, length));
            lastSeen = System.currentTimeMillis();
        }

        private void receiveFromGeyser() {
            byte[] payload = new byte[MAX_DATAGRAM_SIZE - 2];
            try {
                while (!closed && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(payload, payload.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    }
                    byte[] frame = new byte[packet.getLength() + 2];
                    frame[0] = (byte) (playerId >>> 8);
                    frame[1] = (byte) playerId;
                    System.arraycopy(packet.getData(), packet.getOffset(), frame, 2, packet.getLength());
                    DatagramSocket activeRelaySocket = relaySocket;
                    if (activeRelaySocket != null && !activeRelaySocket.isClosed()) {
                        activeRelaySocket.send(new DatagramPacket(frame, frame.length));
                        lastSeen = System.currentTimeMillis();
                    }
                }
            } catch (IOException exception) {
                if (!closed && !socket.isClosed()) {
                    LOGGER.debug("Safra local Geyser tunnel {} closed: {}", playerId, exception.toString());
                }
            } finally {
                players.remove(playerId, this);
                close();
            }
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
