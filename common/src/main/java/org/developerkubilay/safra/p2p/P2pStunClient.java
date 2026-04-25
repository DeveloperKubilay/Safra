package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class P2pStunClient {
    private static final int DISCOVERY_ATTEMPTS_PER_SERVER = 2;
    private static final int DISCOVERY_TIMEOUT_MS = 2_500;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_SUCCESS_RESPONSE = 0x0101;
    private static final int MAPPED_ADDRESS = 0x0001;
    private static final int XOR_MAPPED_ADDRESS = 0x0020;
    private static final int MAGIC_COOKIE = 0x2112A442;

    private final SecureRandom random = new SecureRandom();

    Map<String, DiscoveredEndpoint> discoverCandidates(DatagramSocket socket) {
        Map<String, DiscoveredEndpoint> discovered = new LinkedHashMap<>();
        for (String serverSpec : P2pConstants.STUN_SERVERS) {
            for (InetSocketAddress server : parseServerCandidates(serverSpec)) {
                for (int attempt = 0; attempt < DISCOVERY_ATTEMPTS_PER_SERVER; attempt++) {
                    try {
                        byte[] transactionId = new byte[12];
                        random.nextBytes(transactionId);
                        sendBindingRequest(socket, server, transactionId);
                        DiscoveredEndpoint endpoint = readBindingResponse(socket, transactionId, DISCOVERY_TIMEOUT_MS);
                        if (endpoint != null) {
                            discovered.putIfAbsent(endpoint.family(), endpoint.withServer(server));
                            if (discovered.containsKey("ipv4") && discovered.containsKey("ipv6")) {
                                return discovered;
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return discovered;
    }

    void sendKeepAlive(DatagramSocket socket, InetSocketAddress server) throws IOException {
        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);
        sendBindingRequest(socket, server, transactionId);
    }

    List<PendingRequest> requestCandidates(DatagramSocket socket) {
        List<PendingRequest> pendingRequests = new ArrayList<>();
        for (String serverSpec : P2pConstants.STUN_SERVERS) {
            for (InetSocketAddress server : parseServerCandidates(serverSpec)) {
                try {
                    byte[] transactionId = new byte[12];
                    random.nextBytes(transactionId);
                    sendBindingRequest(socket, server, transactionId);
                    pendingRequests.add(new PendingRequest(server, Arrays.copyOf(transactionId, transactionId.length)));
                } catch (IOException ignored) {
                }
            }
        }
        return pendingRequests;
    }

    DiscoveredEndpoint tryParseResponse(DatagramPacket datagramPacket) {
        byte[] payload = Arrays.copyOf(datagramPacket.getData(), datagramPacket.getLength());
        return parseResponse(payload);
    }

    DiscoveredEndpoint tryParseResponse(DatagramPacket datagramPacket, PendingRequest pendingRequest) {
        if (pendingRequest == null || !pendingRequest.matches(datagramPacket.getSocketAddress())) {
            return null;
        }
        byte[] payload = Arrays.copyOf(datagramPacket.getData(), datagramPacket.getLength());
        return parseResponse(payload, pendingRequest.transactionId());
    }

    private List<InetSocketAddress> parseServerCandidates(String rawServer) {
        int separator = rawServer.lastIndexOf(':');
        String host = rawServer.substring(0, separator);
        int port = Integer.parseInt(rawServer.substring(separator + 1));
        List<InetSocketAddress> servers = new ArrayList<>();
        for (InetAddress address : resolveAddresses(host)) {
            servers.add(new InetSocketAddress(address, port));
        }
        return servers;
    }

    private void sendBindingRequest(DatagramSocket socket, InetSocketAddress server, byte[] transactionId) throws IOException {
        ByteBuffer request = ByteBuffer.allocate(20);
        request.putShort((short) BINDING_REQUEST);
        request.putShort((short) 0);
        request.putInt(MAGIC_COOKIE);
        request.put(transactionId);
        socket.send(new DatagramPacket(request.array(), request.capacity(), server));
    }

    private DiscoveredEndpoint readBindingResponse(DatagramSocket socket, byte[] transactionId, int timeoutMs) throws IOException {
        byte[] buffer = new byte[512];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        int previousTimeout = socket.getSoTimeout();
        socket.setSoTimeout(timeoutMs);
        try {
            while (true) {
                socket.receive(response);
                DiscoveredEndpoint parsed = parseResponse(Arrays.copyOf(response.getData(), response.getLength()), transactionId);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (SocketTimeoutException ignored) {
            return null;
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }

    private DiscoveredEndpoint parseResponse(byte[] payload) {
        if (payload.length < 20) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int messageType = Short.toUnsignedInt(buffer.getShort());
        int messageLength = Short.toUnsignedInt(buffer.getShort());
        int magicCookie = buffer.getInt();
        if (messageType != BINDING_SUCCESS_RESPONSE || magicCookie != MAGIC_COOKIE || messageLength > buffer.remaining()) {
            return null;
        }

        byte[] transactionId = new byte[12];
        buffer.get(transactionId);
        return readAttributes(buffer, transactionId);
    }

    private DiscoveredEndpoint parseResponse(byte[] payload, byte[] expectedTransactionId) {
        if (payload.length < 20) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int messageType = Short.toUnsignedInt(buffer.getShort());
        int messageLength = Short.toUnsignedInt(buffer.getShort());
        int magicCookie = buffer.getInt();
        if (messageType != BINDING_SUCCESS_RESPONSE || magicCookie != MAGIC_COOKIE || messageLength > buffer.remaining()) {
            return null;
        }

        byte[] transactionId = new byte[12];
        buffer.get(transactionId);
        if (!Arrays.equals(expectedTransactionId, transactionId)) {
            return null;
        }

        return readAttributes(buffer, transactionId);
    }

    private DiscoveredEndpoint readAttributes(ByteBuffer buffer, byte[] transactionId) {
        while (buffer.remaining() >= 4) {
            int type = Short.toUnsignedInt(buffer.getShort());
            int length = Short.toUnsignedInt(buffer.getShort());
            if (length > buffer.remaining()) {
                return null;
            }

            int attributeStart = buffer.position();
            if (type == XOR_MAPPED_ADDRESS || type == MAPPED_ADDRESS) {
                DiscoveredEndpoint endpoint = parseAddressAttribute(buffer, type == XOR_MAPPED_ADDRESS, transactionId, length);
                if (endpoint != null) {
                    return endpoint;
                }
            }

            int paddedLength = (length + 3) & ~3;
            buffer.position(attributeStart + paddedLength);
        }
        return null;
    }

    private DiscoveredEndpoint parseAddressAttribute(ByteBuffer buffer, boolean xor, byte[] transactionId, int length) {
        if (length < 8) {
            return null;
        }

        int attributeStart = buffer.position();
        buffer.get();
        int family = Byte.toUnsignedInt(buffer.get());
        int port = Short.toUnsignedInt(buffer.getShort());
        if (xor) {
            port ^= MAGIC_COOKIE >>> 16;
        }

        byte[] addressBytes;
        if (family == 0x01) {
            addressBytes = new byte[4];
            buffer.get(addressBytes);
            if (xor) {
                byte[] cookie = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array();
                for (int i = 0; i < 4; i++) {
                    addressBytes[i] ^= cookie[i];
                }
            }
        } else if (family == 0x02) {
            addressBytes = new byte[16];
            buffer.get(addressBytes);
            if (xor) {
                byte[] mask = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(transactionId).array();
                for (int i = 0; i < 16; i++) {
                    addressBytes[i] ^= mask[i];
                }
            }
        } else {
            buffer.position(attributeStart + length);
            return null;
        }

        try {
            InetAddress address = InetAddress.getByAddress(addressBytes);
            buffer.position(attributeStart + length);
            return new DiscoveredEndpoint(new InetSocketAddress(address, port), null);
        } catch (IOException ignored) {
            buffer.position(attributeStart + length);
            return null;
        }
    }

    private InetAddress[] resolveAddresses(String host) {
        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            Arrays.sort(resolved, (left, right) -> {
                boolean leftIpv6 = !(left instanceof Inet4Address);
                boolean rightIpv6 = !(right instanceof Inet4Address);
                return Boolean.compare(rightIpv6, leftIpv6);
            });
            if (resolved.length > 0) {
                return resolved;
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Could not resolve STUN server " + host, exception);
        }

        throw new IllegalArgumentException("STUN server " + host + " did not resolve to any address");
    }

    static DiscoveredEndpoint preferredCandidate(Map<String, DiscoveredEndpoint> discovered) {
        if (discovered.isEmpty()) {
            return null;
        }

        DiscoveredEndpoint ipv4 = discovered.get("ipv4");
        if (ipv4 != null) {
            return ipv4;
        }

        DiscoveredEndpoint ipv6 = discovered.get("ipv6");
        if (ipv6 != null) {
            return ipv6;
        }

        return discovered.values().iterator().next();
    }

    static List<InetSocketAddress> publicEndpoints(Map<String, DiscoveredEndpoint> discovered) {
        ArrayList<InetSocketAddress> endpoints = new ArrayList<>();
        DiscoveredEndpoint ipv4 = discovered.get("ipv4");
        if (ipv4 != null) {
            endpoints.add(ipv4.publicAddress());
        }

        DiscoveredEndpoint ipv6 = discovered.get("ipv6");
        if (ipv6 != null) {
            endpoints.add(ipv6.publicAddress());
        }

        if (endpoints.isEmpty()) {
            DiscoveredEndpoint fallback = preferredCandidate(discovered);
            if (fallback != null) {
                endpoints.add(fallback.publicAddress());
            }
        }
        return endpoints;
    }

    record DiscoveredEndpoint(InetSocketAddress publicAddress, InetSocketAddress stunServer) {
        DiscoveredEndpoint withServer(InetSocketAddress server) {
            return new DiscoveredEndpoint(publicAddress, server);
        }

        String family() {
            return P2pSockets.addressFamily(publicAddress);
        }

        boolean matches(SocketAddress remote) {
            return remote instanceof InetSocketAddress socketAddress
                && stunServer != null
                && socketAddress.getAddress() != null
                && stunServer.getAddress() != null
                && socketAddress.getAddress().equals(stunServer.getAddress())
                && socketAddress.getPort() == stunServer.getPort();
        }
    }

    record PendingRequest(InetSocketAddress server, byte[] transactionId) {
        boolean matches(SocketAddress remote) {
            return remote instanceof InetSocketAddress socketAddress
                && server != null
                && socketAddress.getAddress() != null
                && server.getAddress() != null
                && socketAddress.getAddress().equals(server.getAddress())
                && socketAddress.getPort() == server.getPort();
        }
    }
}
