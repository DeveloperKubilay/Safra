package org.developerkubilay.safra.p2p;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.Socket;
import java.net.SocketException;

final class P2pSockets {
    private static final InetAddress IPV4_LOOPBACK = createIpv4Loopback();

    private P2pSockets() {
    }

    static DatagramSocket datagramSocket() throws SocketException {
        DatagramSocket socket = new DatagramSocket();
        tune(socket);
        return socket;
    }

    static DatagramSocket datagramSocket(int port) throws SocketException {
        DatagramSocket socket = new DatagramSocket(port);
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
