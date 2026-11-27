package org.developerkubilay.safra.p2p.turn;

import org.developerkubilay.safra.p2p.P2pRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void openWinsViaSecondUdpServerWhenFirstHangs() throws Exception {
        InetSocketAddress expectedRelay = new InetSocketAddress("127.0.0.1", 49152);
        try (DatagramSocket silentServer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket respondingServer = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FutureTask<Void> responder = startMockTurnAllocateServer(respondingServer, expectedRelay, null);
            P2pTurnCredentials credentials = new P2pTurnCredentials(
                List.of(
                    new P2pTurnCredentials.TurnServer("127.0.0.1", silentServer.getLocalPort()),
                    new P2pTurnCredentials.TurnServer("127.0.0.1", respondingServer.getLocalPort())
                ),
                List.of(),
                List.of(),
                "user",
                "password",
                600
            );

            long startedNanos = System.nanoTime();
            try (P2pTurnDatagramTransport transport = P2pTurnDatagramTransport.open(
                LoggerFactory.getLogger(getClass()), "test", credentials)) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
                assertEquals(expectedRelay, transport.relayAddress());
                assertTrue(elapsedMs < 2_200L, "Expected staggered UDP race to win in < 2200ms, took " + elapsedMs + "ms");
            }
            responder.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void openSkipsSecondUdpServerWhenFirstSucceedsQuickly() throws Exception {
        InetSocketAddress expectedRelay = new InetSocketAddress("127.0.0.1", 49153);
        try (DatagramSocket primaryServer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket secondaryServer = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FutureTask<Void> primary = startMockTurnAllocateServer(primaryServer, expectedRelay, null);
            AtomicInteger secondaryPackets = new AtomicInteger();
            FutureTask<Void> secondaryWatcher = new FutureTask<>(() -> {
                secondaryServer.setSoTimeout(600);
                try {
                    while (!secondaryServer.isClosed()) {
                        secondaryServer.receive(new DatagramPacket(new byte[2_048], 2_048));
                        secondaryPackets.incrementAndGet();
                    }
                } catch (java.net.SocketTimeoutException | java.net.SocketException ignored) {
                }
                return null;
            });
            P2pRuntime.start("safra-test-turn-secondary", secondaryWatcher);

            P2pTurnCredentials credentials = new P2pTurnCredentials(
                List.of(
                    new P2pTurnCredentials.TurnServer("127.0.0.1", primaryServer.getLocalPort()),
                    new P2pTurnCredentials.TurnServer("127.0.0.1", secondaryServer.getLocalPort())
                ),
                List.of(),
                List.of(),
                "user",
                "password",
                600
            );

            try (P2pTurnDatagramTransport transport = P2pTurnDatagramTransport.open(
                LoggerFactory.getLogger(getClass()), "test", credentials)) {
                assertEquals(expectedRelay, transport.relayAddress());
            }
            primary.get(2, TimeUnit.SECONDS);
            secondaryWatcher.get(2, TimeUnit.SECONDS);
            assertEquals(0, secondaryPackets.get(), "Secondary UDP server should not be dialed when primary succeeds quickly");
        }
    }

    @Test
    void closeSendsZeroLifetimeRefreshToFreeAllocation() throws Exception {
        InetSocketAddress expectedRelay = new InetSocketAddress("127.0.0.1", 49154);
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            FutureTask<Integer> serverTask = new FutureTask<>(() -> {
                server.setSoTimeout(4_000);
                for (int step = 0; step < 2; step++) {
                    DatagramPacket packet = new DatagramPacket(new byte[2_048], 2_048);
                    server.receive(packet);
                    P2pTurnMessage request = P2pTurnMessage.parse(packet.getData(), packet.getLength());
                    assertNotNull(request);
                    byte[] response = request.attribute(P2pTurnProtocol.ATTR_MESSAGE_INTEGRITY) == null
                        ? buildTurnErrorResponse(0x0113, request.transactionId(), 401, "Unauthorized", "testrealm", "testnonce")
                        : buildTurnAllocateSuccess(request.transactionId(), expectedRelay, 600);
                    server.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                }
                DatagramPacket refreshPacket = new DatagramPacket(new byte[2_048], 2_048);
                server.receive(refreshPacket);
                P2pTurnMessage refresh = P2pTurnMessage.parse(refreshPacket.getData(), refreshPacket.getLength());
                assertNotNull(refresh);
                assertEquals(P2pTurnProtocol.TURN_REFRESH_REQUEST, refresh.type());
                byte[] lifetime = refresh.attribute(P2pTurnProtocol.ATTR_LIFETIME);
                assertNotNull(lifetime);
                assertEquals(4, lifetime.length);
                return ByteBuffer.wrap(lifetime).getInt();
            });
            P2pRuntime.start("safra-test-turn-dealloc", serverTask);
            P2pTurnCredentials credentials = new P2pTurnCredentials(
                List.of(new P2pTurnCredentials.TurnServer("127.0.0.1", server.getLocalPort())),
                List.of(),
                List.of(),
                "user",
                "password",
                600
            );
            P2pTurnDatagramTransport transport = P2pTurnDatagramTransport.open(
                LoggerFactory.getLogger(getClass()), "test", credentials);
            transport.close();
            assertEquals(0, serverTask.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void sendDoesNotBlockWhileBackgroundChannelRenewalWaits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            InetSocketAddress peer = new InetSocketAddress("192.0.2.1", 25565);
            var constructor = Class.forName(P2pTurnDatagramTransport.class.getName() + "$PeerChannel")
                .getDeclaredConstructor(int.class, InetSocketAddress.class);
            constructor.setAccessible(true);
            Object permission = constructor.newInstance(0x4000, peer);
            set(permission, "expiresAtNanos", System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
            set(permission, "permissionExpiresAtNanos", System.nanoTime() + TimeUnit.SECONDS.toNanos(20));
            permissions(fixture.transport).put(P2pTurnProtocol.permissionKey(peer), permission);

            java.util.concurrent.CountDownLatch renewalStarted = new java.util.concurrent.CountDownLatch(1);
            FutureTask<Void> delayedReply = new FutureTask<>(() -> {
                DatagramPacket request = new DatagramPacket(new byte[2_048], 2_048);
                fixture.server.receive(request);
                renewalStarted.countDown();
                Thread.sleep(400L);
                byte[] response = ByteBuffer.allocate(20)
                    .putShort((short) P2pTurnProtocol.TURN_CHANNEL_BIND_RESPONSE)
                    .putShort((short) 0)
                    .putInt(P2pTurnProtocol.MAGIC_COOKIE)
                    .put(request.getData(), 8, 12)
                    .array();
                fixture.server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                return null;
            });
            P2pRuntime.start("safra-test-delayed-bind", delayedReply);
            P2pRuntime.start("safra-test-renew-bg", () -> {
                try {
                    invoke(fixture.transport, "renewChannels");
                } catch (Exception ignored) {
                }
            });

            assertTrue(renewalStarted.await(2, TimeUnit.SECONDS));
            long sendStartNanos = System.nanoTime();
            fixture.transport.send(new DatagramPacket(new byte[]{1, 2, 3}, 3, peer));
            long sendMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sendStartNanos);
            assertTrue(sendMs < 150L, "Expected send() not to block on channel renewal, took " + sendMs + "ms");
            delayedReply.get(2, TimeUnit.SECONDS);
        }
    }

    private static FutureTask<Void> startMockTurnAllocateServer(DatagramSocket server,
                                                                InetSocketAddress relayedAddress,
                                                                AtomicInteger receivedCount) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            server.setSoTimeout(4_000);
            for (int step = 0; step < 2; step++) {
                DatagramPacket packet = new DatagramPacket(new byte[2_048], 2_048);
                server.receive(packet);
                if (receivedCount != null) {
                    receivedCount.incrementAndGet();
                }
                P2pTurnMessage request = P2pTurnMessage.parse(packet.getData(), packet.getLength());
                assertNotNull(request);
                assertEquals(P2pTurnProtocol.TURN_ALLOCATE_REQUEST, request.type());
                byte[] response = request.attribute(P2pTurnProtocol.ATTR_MESSAGE_INTEGRITY) == null
                    ? buildTurnErrorResponse(0x0113, request.transactionId(), 401, "Unauthorized", "testrealm", "testnonce")
                    : buildTurnAllocateSuccess(request.transactionId(), relayedAddress, 600);
                server.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
            }
            return null;
        });
        P2pRuntime.start("safra-test-mock-turn", task);
        return task;
    }

    private static byte[] buildTurnErrorResponse(int type, byte[] transactionId, int code,
                                                 String reason, String realm, String nonce) throws Exception {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] errorVal = new byte[4 + reasonBytes.length];
        errorVal[2] = (byte) (code / 100);
        errorVal[3] = (byte) (code % 100);
        System.arraycopy(reasonBytes, 0, errorVal, 4, reasonBytes.length);
        writeTurnAttr(attrs, P2pTurnProtocol.ATTR_ERROR_CODE, errorVal);
        writeTurnAttr(attrs, P2pTurnProtocol.ATTR_REALM, realm.getBytes(StandardCharsets.UTF_8));
        writeTurnAttr(attrs, P2pTurnProtocol.ATTR_NONCE, nonce.getBytes(StandardCharsets.UTF_8));
        return wrapStunMessage(type, transactionId, attrs.toByteArray());
    }

    private static byte[] buildTurnAllocateSuccess(byte[] transactionId, InetSocketAddress relayed, int lifetime) throws Exception {
        ByteArrayOutputStream attrs = new ByteArrayOutputStream();
        byte[] rawIp = relayed.getAddress().getAddress();
        ByteBuffer xor = ByteBuffer.allocate(8);
        xor.put((byte) 0).put((byte) 0x01);
        xor.putShort((short) (relayed.getPort() ^ (P2pTurnProtocol.MAGIC_COOKIE >>> 16)));
        byte[] cookie = ByteBuffer.allocate(4).putInt(P2pTurnProtocol.MAGIC_COOKIE).array();
        for (int i = 0; i < 4; i++) {
            xor.put((byte) (rawIp[i] ^ cookie[i]));
        }
        writeTurnAttr(attrs, P2pTurnProtocol.ATTR_XOR_RELAYED_ADDRESS, xor.array());
        writeTurnAttr(attrs, P2pTurnProtocol.ATTR_LIFETIME, ByteBuffer.allocate(4).putInt(lifetime).array());
        return wrapStunMessage(P2pTurnProtocol.TURN_ALLOCATE_RESPONSE, transactionId, attrs.toByteArray());
    }

    private static void writeTurnAttr(ByteArrayOutputStream out, int type, byte[] value) throws Exception {
        out.write((type >>> 8) & 0xFF);
        out.write(type & 0xFF);
        out.write((value.length >>> 8) & 0xFF);
        out.write(value.length & 0xFF);
        out.write(value);
        int padding = (4 - (value.length & 3)) & 3;
        for (int i = 0; i < padding; i++) {
            out.write(0);
        }
    }

    private static byte[] wrapStunMessage(int type, byte[] transactionId, byte[] attrs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((type >>> 8) & 0xFF);
        out.write(type & 0xFF);
        out.write((attrs.length >>> 8) & 0xFF);
        out.write(attrs.length & 0xFF);
        out.write(ByteBuffer.allocate(4).putInt(P2pTurnProtocol.MAGIC_COOKIE).array());
        out.write(transactionId);
        out.write(attrs);
        return out.toByteArray();
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
