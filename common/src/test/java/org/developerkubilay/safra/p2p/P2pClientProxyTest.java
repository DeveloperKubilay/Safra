package org.developerkubilay.safra.p2p;

import org.developerkubilay.safra.p2p.transport.P2pDirectDatagramTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class P2pClientProxyTest {
    @Test
    void retriesALostRouteProbeWithoutClosingTheOriginalTransport() throws Exception {
        try (Fixture fixture = new Fixture(); DatagramSocket host = new DatagramSocket(0, P2pSockets.loopbackAddress());
             var candidate = new P2pDirectDatagramTransport(P2pSockets.datagramSocket())) {
            host.setSoTimeout(3_000);
            FutureTask<Void> responder = new FutureTask<>(() -> {
                DatagramPacket request = new DatagramPacket(new byte[1_210], 1_210);
                host.receive(request);
                request.setLength(1_210);
                host.receive(request);
                byte[] response = P2pPacket.quicCertificate(7, 9, new byte[]{1, 2, 3}).encode();
                host.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                return null;
            });
            P2pRuntime.start("safra-test-route-reply", responder);
            assertTrue(fixture.probe(candidate, (InetSocketAddress) host.getLocalSocketAddress()));
            responder.get(2, TimeUnit.SECONDS);
            assertFalse(fixture.original.isClosed());
            assertSame(fixture.original, get(fixture.proxy, "transport"));
            assertEquals(0, candidate.socket().getSoTimeout());
        }
    }

    @Test
    void rejectsAChangedCertificateAndStopsWhenTheOriginalRouteRecovers() throws Exception {
        try (Fixture fixture = new Fixture(); DatagramSocket host = new DatagramSocket(0, P2pSockets.loopbackAddress());
             var candidate = new P2pDirectDatagramTransport(P2pSockets.datagramSocket())) {
            host.setSoTimeout(3_000);
            FutureTask<Void> responder = new FutureTask<>(() -> {
                DatagramPacket request = new DatagramPacket(new byte[1_210], 1_210);
                host.receive(request);
                byte[] response = P2pPacket.quicCertificate(7, 9, new byte[]{4, 5, 6}).encode();
                host.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                set(fixture.proxy, "lastPacketReceivedNanos", System.nanoTime());
                return null;
            });
            P2pRuntime.start("safra-test-route-reply", responder);
            assertFalse(fixture.probe(candidate, (InetSocketAddress) host.getLocalSocketAddress()));
            responder.get(2, TimeUnit.SECONDS);
            assertFalse(fixture.original.isClosed());
            assertSame(fixture.original, get(fixture.proxy, "transport"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void closingTheProxyAlsoClosesSocketsWaitingForRetry() throws Exception {
        try (var proxy = new P2pClientProxy(new P2pShareCode("127.0.0.1", 25565, 7, null), () -> { });
             var socket = new Socket()) {
            ((Set<Socket>) get(proxy, "pendingRetrySockets")).add(socket);
            proxy.close();
            assertTrue(socket.isClosed());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacingARouteStartsItsReceiverAndClosesThePreviousSocket() throws Exception {
        try (Fixture fixture = new Fixture(); DatagramSocket host = new DatagramSocket(0, P2pSockets.loopbackAddress());
             var candidate = new P2pDirectDatagramTransport(P2pSockets.datagramSocket())) {
            Class<?> routeClass = Class.forName(P2pClientProxy.class.getName() + "$RelayRoute");
            var constructor = routeClass.getDeclaredConstructor(P2pTransportBinding.class, InetSocketAddress.class, int.class);
            constructor.setAccessible(true);
            Object route = constructor.newInstance(new P2pTransportBinding(candidate, List.of(), Map.of(), false),
                host.getLocalSocketAddress(), 7);
            var activate = P2pClientProxy.class.getDeclaredMethod("activateRoute", routeClass);
            activate.setAccessible(true);
            activate.invoke(fixture.proxy, route);
            byte[] response = P2pPacket.quicCertificate(7, 9, new byte[]{1, 2, 3}).encode();
            host.send(new DatagramPacket(response, response.length,
                new InetSocketAddress(P2pSockets.loopbackAddress(), candidate.socket().getLocalPort())));
            Map<Integer, P2pKwikClientTunnel> connections = (Map<Integer, P2pKwikClientTunnel>) get(fixture.proxy, "connections");
            var certificates = (java.util.concurrent.BlockingQueue<byte[]>) get(connections.get(9), "certificates");
            assertArrayEquals(new byte[]{1, 2, 3}, certificates.poll(2, TimeUnit.SECONDS));
            assertTrue(fixture.original.isClosed());
            assertFalse(candidate.isClosed());
        }
    }

    @Test
    void matchesOnlyTheOpenProxySocketEndpoint() throws Exception {
        try (var proxy = new P2pClientProxy(new P2pShareCode("127.0.0.1", 25565, 7, null), () -> { });
             var server = new java.net.ServerSocket(0, 16, P2pSockets.loopbackAddress())) {
            var endpoint = (InetSocketAddress) server.getLocalSocketAddress();
            assertFalse(proxy.matchesProxyEndpoint(endpoint));
            set(proxy, "proxyServer", server);
            assertTrue(proxy.matchesProxyEndpoint(endpoint));
            assertFalse(proxy.matchesProxyEndpoint(null));
            assertFalse(proxy.matchesProxyEndpoint(new InetSocketAddress(P2pSockets.loopbackAddress(), 0)));
            assertFalse(proxy.matchesProxyEndpoint(InetSocketAddress.createUnresolved("localhost", endpoint.getPort())));
            assertFalse(proxy.matchesProxyEndpoint(new InetSocketAddress("192.0.2.1", endpoint.getPort())));
            assertFalse(proxy.matchesProxyEndpoint(new InetSocketAddress("127.0.0.2", endpoint.getPort())));
            proxy.close();
            assertFalse(proxy.matchesProxyEndpoint(endpoint));
        }
    }

    @Test
    void rejectsAClosedProxyListener() throws Exception {
        try (var proxy = new P2pClientProxy(new P2pShareCode("127.0.0.1", 25565, 7, null), () -> { });
             var server = new java.net.ServerSocket(0, 16, P2pSockets.loopbackAddress())) {
            var endpoint = server.getLocalSocketAddress();
            set(proxy, "proxyServer", server);
            server.close();
            assertFalse(proxy.matchesProxyEndpoint(endpoint));
        }
    }

    private static Object get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture implements AutoCloseable {
        private final P2pClientProxy proxy = new P2pClientProxy(new P2pShareCode("127.0.0.1", 25565, 7, null), () -> { });
        private final P2pDirectDatagramTransport original = new P2pDirectDatagramTransport(P2pSockets.datagramSocket());

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            var constructor = SafraRendezvousClient.JoinSession.class.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            set(proxy, "rendezvousSession", constructor.newInstance("test"));
            set(proxy, "transport", original);
            set(proxy, "tunnelToken", 7);
            set(proxy, "lastPacketReceivedNanos", System.nanoTime() - TimeUnit.SECONDS.toNanos(5));
            var tunnel = new P2pKwikClientTunnel(LoggerFactory.getLogger(getClass()), 7, 9, new Socket(),
                7_000, packet -> { }, () -> { }, () -> { }, () -> { });
            set(tunnel, "certificate", new byte[]{1, 2, 3});
            ((Map<Integer, P2pKwikClientTunnel>) get(proxy, "connections")).put(9, tunnel);
        }

        private boolean probe(P2pDirectDatagramTransport candidate, InetSocketAddress address) throws Exception {
            var method = P2pClientProxy.class.getDeclaredMethod("probeDirectRoute", P2pTransportBinding.class, InetSocketAddress.class);
            method.setAccessible(true);
            return (boolean) method.invoke(proxy, new P2pTransportBinding(candidate, List.of(), Map.of(), false), address);
        }

        @Override
        public void close() {
            proxy.close();
        }
    }
}
