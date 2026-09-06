package org.developerkubilay.safra.p2p;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collection;
import java.util.Locale;

public final class P2pSockets {
    private static final InetAddress IPV4_LOOPBACK = createIpv4Loopback();
    private static final InetAddress IPV4_ANY = createIpv4Any();

    private P2pSockets() {
    }

    public static DatagramSocket datagramSocket() throws SocketException {
        DatagramSocket socket = new DatagramSocket((SocketAddress) null);
        socket.bind(new InetSocketAddress(0));
        tune(socket);
        return socket;
    }

    public static DatagramSocket datagramSocket(int port) throws SocketException {
        DatagramSocket socket = new DatagramSocket((SocketAddress) null);
        socket.bind(new InetSocketAddress(port));
        tune(socket);
        return socket;
    }

    static DatagramSocket ipv4DatagramSocket() throws SocketException {
        return ipv4DatagramSocket(0);
    }

    static DatagramSocket ipv4DatagramSocket(int port) throws SocketException {
        DatagramSocket socket = new DatagramSocket((SocketAddress) null);
        socket.bind(new InetSocketAddress(IPV4_ANY, port));
        tune(socket);
        return socket;
    }

    static void tune(Socket socket) {
        trySet(() -> socket.setTcpNoDelay(true));
        trySet(() -> socket.setKeepAlive(true));
        trySet(() -> socket.setReceiveBufferSize(P2pConstants.TCP_BUFFER_SIZE));
        trySet(() -> socket.setSendBufferSize(P2pConstants.TCP_BUFFER_SIZE));
    }

    static InetAddress loopbackAddress() {
        return IPV4_LOOPBACK;
    }

    static InetAddress ipv4WildcardAddress() {
        return IPV4_ANY;
    }

    static AddressFamily addressFamily(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return AddressFamily.UNKNOWN;
        }

        return address.getAddress() instanceof Inet4Address ? AddressFamily.IPV4 : AddressFamily.IPV6;
    }

    /** The endpoint a peer should be told about: IPv4 when there is one, because the protocol carries a single address. */
    static InetSocketAddress preferredEndpoint(Collection<InetSocketAddress> endpoints) {
        if (endpoints == null) {
            return null;
        }

        InetSocketAddress fallback = null;
        for (InetSocketAddress endpoint : endpoints) {
            if (endpoint == null || endpoint.getAddress() == null) {
                continue;
            }
            if (addressFamily(endpoint) == AddressFamily.IPV4) {
                return endpoint;
            }
            if (fallback == null) {
                fallback = endpoint;
            }
        }
        return fallback;
    }

    enum AddressFamily {
        IPV4,
        IPV6,
        UNKNOWN;

        private final String wireName = name().toLowerCase(Locale.ROOT);

        /** The spelling the rendezvous protocol uses. */
        String wireName() {
            return wireName;
        }
    }

    private static void tune(DatagramSocket socket) {
        trySet(() -> socket.setReceiveBufferSize(P2pConstants.SOCKET_BUFFER_SIZE));
        trySet(() -> socket.setSendBufferSize(P2pConstants.SOCKET_BUFFER_SIZE));
        trySet(() -> socket.setTrafficClass(0x10));
    }

    private static InetAddress createIpv4Loopback() {
        try {
            return InetAddress.getByName(P2pConstants.LOCAL_PROXY_HOST);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("IPv4 loopback address could not be resolved", exception);
        }
    }

    private static InetAddress createIpv4Any() {
        try {
            return InetAddress.getByName("0.0.0.0");
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("IPv4 wildcard address could not be resolved", exception);
        }
    }

    private static void trySet(SocketSetter setter) {
        try {
            setter.set();
        } catch (SocketException ignored) {
        }
    }

    @FunctionalInterface
    private interface SocketSetter {
        void set() throws SocketException;
    }
}
