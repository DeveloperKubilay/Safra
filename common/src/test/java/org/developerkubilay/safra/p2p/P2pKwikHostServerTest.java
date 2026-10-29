package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class P2pKwikHostServerTest {
    @Test
    void refusedMinecraftConnectionLeavesNoSocketOrRegistration() throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, P2pSockets.loopbackAddress())) {
            port = reserved.getLocalPort();
        }
        int registrationsBefore = registrations().size();
        AtomicInteger closes = new AtomicInteger();
        try (var host = new P2pKwikHostServer(LoggerFactory.getLogger(getClass()), port,
            P2pSockets.loopbackAddress(), P2pKwikCertificate.create(), (transport, packet, address) -> { })) {
            var open = P2pKwikHostServer.class.getDeclaredMethod("openMinecraftStream", QuicConnection.class, QuicStream.class);
            open.setAccessible(true);
            open.invoke(host, connection(closes), null);
            assertTrue(sockets(host).isEmpty());
            assertEquals(registrationsBefore, registrations().size());
            assertEquals(1, closes.get());
        }
    }

    @Test
    void shutdownClosesAndUnregistersASocketBeforeConnect() throws Exception {
        try (var host = new P2pKwikHostServer(LoggerFactory.getLogger(getClass()), 25565,
            P2pSockets.loopbackAddress(), P2pKwikCertificate.create(), (transport, packet, address) -> { });
             Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(P2pSockets.loopbackAddress(), 0));
            InetSocketAddress target = new InetSocketAddress(P2pSockets.loopbackAddress(), 25565);
            var registration = P2pHostConnectionRegistry.getInstance().register(socket, target);
            sockets(host).put(socket, registration);
            assertTrue(P2pHostConnectionRegistry.getInstance().matches(socket.getLocalSocketAddress(), target));
            host.close();
            assertTrue(socket.isClosed());
            assertFalse(P2pHostConnectionRegistry.getInstance().matches(socket.getLocalSocketAddress(), target));
            assertTrue(sockets(host).isEmpty());
            AtomicInteger closes = new AtomicInteger();
            var open = P2pKwikHostServer.class.getDeclaredMethod("openMinecraftStream", QuicConnection.class, QuicStream.class);
            open.setAccessible(true);
            open.invoke(host, connection(closes), null);
            assertEquals(1, closes.get());
            assertTrue(sockets(host).isEmpty());
        }
    }

    private static QuicConnection connection(AtomicInteger closes) {
        return (QuicConnection) Proxy.newProxyInstance(QuicConnection.class.getClassLoader(),
            new Class<?>[]{QuicConnection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("close")) {
                    closes.incrementAndGet();
                    return null;
                }
                if (method.getName().equals("hashCode")) {
                    return System.identityHashCode(proxy);
                }
                if (method.getName().equals("equals")) {
                    return proxy == arguments[0];
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @SuppressWarnings("unchecked")
    private static Map<Socket, P2pHostConnectionRegistry.Registration> sockets(P2pKwikHostServer host) throws Exception {
        var field = P2pKwikHostServer.class.getDeclaredField("openMinecraftSockets");
        field.setAccessible(true);
        return (Map<Socket, P2pHostConnectionRegistry.Registration>) field.get(host);
    }

    private static Map<?, ?> registrations() throws Exception {
        var field = P2pHostConnectionRegistry.class.getDeclaredField("connections");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(P2pHostConnectionRegistry.getInstance());
    }
}
