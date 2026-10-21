package org.developerkubilay.safra.p2p.turn;

import org.developerkubilay.safra.p2p.P2pRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class P2pTurnDatagramTransportTest {
    @Test
    void retriesRefreshWhileTheExistingAllocationIsStillValid() throws Exception {
        try (Fixture fixture = new Fixture()) {
            set(fixture.transport, "allocationExpiresAtNanos", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            invoke(fixture.transport, "refreshAllocationSafely");
            assertFalse(fixture.transport.isClosed());
            fixture.discardRequest();
            FutureTask<Integer> reply = fixture.reply(P2pTurnProtocol.TURN_REFRESH_RESPONSE, 120);
            assertEquals(P2pTurnProtocol.TURN_REFRESH_REQUEST, reply.get(4, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((long) get(fixture.transport, "allocationExpiresAtNanos") - System.nanoTime() < TimeUnit.SECONDS.toNanos(100)
                && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue((long) get(fixture.transport, "allocationExpiresAtNanos") - System.nanoTime() > TimeUnit.SECONDS.toNanos(100));
            assertFalse(fixture.transport.isClosed());
        }
    }

    @Test
    void closesAnExpiredAllocationInsteadOfRetryingForever() throws Exception {
        try (Fixture fixture = new Fixture()) {
            set(fixture.transport, "allocationExpiresAtNanos", System.nanoTime() - 1);
            invoke(fixture.transport, "refreshAllocationSafely");
            assertTrue(fixture.transport.isClosed());
        }
    }

    @Test
    void schedulesShortAllocationsBeforeTheyExpire() throws Exception {
        try (Fixture fixture = new Fixture()) {
            set(fixture.transport, "grantedLifetimeSeconds", 10);
            invoke(fixture.transport, "scheduleRefresh");
            ScheduledFuture<?> task = (ScheduledFuture<?>) get(fixture.transport, "refreshTask");
            assertTrue(task.getDelay(TimeUnit.MILLISECONDS) > 0);
            assertTrue(task.getDelay(TimeUnit.MILLISECONDS) <= 5_000);
        }
    }

    @Test
    void recreatesExpiredPermissionsEvenWhenTheyRemainInTheCache() throws Exception {
        try (Fixture fixture = new Fixture()) {
            InetSocketAddress peer = new InetSocketAddress("192.0.2.1", 25565);
            var constructor = Class.forName(P2pTurnDatagramTransport.class.getName() + "$PeerChannel")
                .getDeclaredConstructor(int.class, InetSocketAddress.class);
            constructor.setAccessible(true);
            Map<String, Object> permissions = permissions(fixture.transport);
            permissions.put(P2pTurnProtocol.permissionKey(peer), constructor.newInstance(0x4000, peer));
            FutureTask<Integer> reply = fixture.reply(P2pTurnProtocol.TURN_CHANNEL_BIND_RESPONSE, null);
            var method = P2pTurnDatagramTransport.class.getDeclaredMethod("ensureChannel", InetSocketAddress.class);
            method.setAccessible(true);
            method.invoke(fixture.transport, peer);
            assertEquals(P2pTurnProtocol.TURN_CHANNEL_BIND_REQUEST, reply.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void retriesPermissionRenewalAfterATransientFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            InetSocketAddress peer = new InetSocketAddress("192.0.2.1", 25565);
            var constructor = Class.forName(P2pTurnDatagramTransport.class.getName() + "$PeerChannel")
                .getDeclaredConstructor(int.class, InetSocketAddress.class);
            constructor.setAccessible(true);
            Object permission = constructor.newInstance(0x4000, peer);
            long oldExpiry = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            set(permission, "expiresAtNanos", System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
            set(permission, "permissionExpiresAtNanos", oldExpiry);
            Map<String, Object> permissions = permissions(fixture.transport);
            permissions.put(P2pTurnProtocol.permissionKey(peer), permission);
            invoke(fixture.transport, "renewChannels");
            assertSame(permission, permissions.get(P2pTurnProtocol.permissionKey(peer)));
            assertEquals(oldExpiry, get(permission, "permissionExpiresAtNanos"));
            fixture.discardRequest();
            FutureTask<Integer> reply = fixture.reply(P2pTurnProtocol.TURN_CHANNEL_BIND_RESPONSE, null);
            invoke(fixture.transport, "renewChannels");
            assertEquals(P2pTurnProtocol.TURN_CHANNEL_BIND_REQUEST, reply.get(2, TimeUnit.SECONDS));
            assertTrue((long) get(permission, "permissionExpiresAtNanos") > oldExpiry);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> permissions(P2pTurnDatagramTransport transport) throws Exception {
        return (Map<String, Object>) get(transport, "channelsByPeer");
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

    private static void invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static final class Fixture implements AutoCloseable {
        private final DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        private final P2pTurnDatagramTransport transport;

        private Fixture() throws Exception {
            DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            client.connect(server.getLocalSocketAddress());
            server.setSoTimeout(4_000);
            var constructor = P2pTurnDatagramTransport.class.getDeclaredConstructor(Logger.class, String.class,
                DatagramSocket.class, Socket.class, InetSocketAddress.class, String.class, int.class, String.class, String.class);
            constructor.setAccessible(true);
            transport = constructor.newInstance(LoggerFactory.getLogger(getClass()), "test", client, null,
                server.getLocalSocketAddress(), "UDP", 100, "user", "password");
            set(transport, "realm", "test");
            set(transport, "nonce", "test");
            P2pRuntime.start("safra-test-turn-recv", () -> {
                try { invoke(transport, "receiveLoop"); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }

        private FutureTask<Integer> reply(int responseType, Integer lifetime) {
            FutureTask<Integer> reply = new FutureTask<>(() -> {
                DatagramPacket request = new DatagramPacket(new byte[2_048], 2_048);
                server.receive(request);
                byte[] response = new byte[lifetime == null ? 20 : 28];
                ByteBuffer buffer = ByteBuffer.wrap(response);
                buffer.putShort((short) responseType).putShort((short) (response.length - 20))
                    .putInt(P2pTurnProtocol.MAGIC_COOKIE).put(request.getData(), 8, 12);
                if (lifetime != null) buffer.putShort((short) P2pTurnProtocol.ATTR_LIFETIME).putShort((short) 4).putInt(lifetime);
                server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                return Short.toUnsignedInt(ByteBuffer.wrap(request.getData()).getShort());
            });
            P2pRuntime.start("safra-test-turn-reply", reply);
            return reply;
        }

        private void discardRequest() throws Exception {
            server.setSoTimeout(25);
            try {
                while (true) server.receive(new DatagramPacket(new byte[2_048], 2_048));
            } catch (java.net.SocketTimeoutException ignored) {
            } finally {
                server.setSoTimeout(4_000);
            }
        }

        @Override
        public void close() {
            transport.close();
            server.close();
        }
    }
}
