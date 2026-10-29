package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.impl.QuicClientConnectionImpl;
import tech.kwik.core.impl.TransportParameters;
import tech.kwik.core.stream.StreamManager;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class P2pKwikClientTunnelTest {
    static Stream<Boolean> transferCases() {
        return IntStream.range(0, Math.max(1, Math.min(1000, Integer.getInteger("safra.tunnelTestRepetitions", 1))))
            .boxed().flatMap(ignored -> Stream.of(false, true));
    }

    @ParameterizedTest
    @MethodSource("transferCases")
    void negotiatesTheInitialWindowBeforeReceivingABurst(boolean loseDatagram) throws Exception {
        var logger = LoggerFactory.getLogger(getClass());
        byte[] payload = new byte[300_000];
        Arrays.fill(payload, (byte) 37);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch established = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<P2pKwikClientTunnel> tunnelRef = new AtomicReference<>();
        AtomicInteger packets = new AtomicInteger();
        try (ServerSocket target = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             ServerSocket proxy = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             Socket game = new Socket()) {
            game.setReceiveBufferSize(1_024);
            game.connect(proxy.getLocalSocketAddress());
            game.setSoTimeout(8_000);
            FutureTask<Void> writer = new FutureTask<>(() -> {
                try (Socket socket = target.accept()) {
                    socket.getOutputStream().write(payload);
                    release.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            P2pRuntime.start("safra-test-minecraft", writer);
            try (var host = new P2pKwikHostServer(logger, target.getLocalPort(), P2pSockets.loopbackAddress(),
                P2pKwikCertificate.create(), (transport, packet, address) -> {
                    if (loseDatagram && established.getCount() == 0 && packet.type() == P2pPacket.Type.QUIC_DATA
                        && packets.incrementAndGet() == 10) return;
                    tunnelRef.get().handlePacket(packet);
                });
                 var tunnel = new P2pKwikClientTunnel(logger, 7, 9, proxy.accept(), 7_000,
                     packet -> host.handlePacket(packet, new InetSocketAddress(P2pSockets.loopbackAddress(), 4444), null),
                     () -> { }, failed::countDown, established::countDown)) {
                tunnelRef.set(tunnel);
                tunnel.start();
                assertTrue(established.await(5, TimeUnit.SECONDS));
                var connectionField = P2pKwikClientTunnel.class.getDeclaredField("connection");
                connectionField.setAccessible(true);
                QuicClientConnection connection = (QuicClientConnection) connectionField.get(tunnel);
                var parametersField = QuicClientConnectionImpl.class.getDeclaredField("transportParams");
                parametersField.setAccessible(true);
                TransportParameters parameters = (TransportParameters) parametersField.get(connection);
                var managerField = QuicClientConnectionImpl.class.getDeclaredField("streamManager");
                managerField.setAccessible(true);
                StreamManager manager = (StreamManager) managerField.get(connection);
                assertEquals(P2pConstants.MIN_STREAM_WINDOW_BYTES, parameters.getInitialMaxStreamDataBidiLocal());
                assertEquals(manager.getMaxBidirectionalStreamBufferSize(), parameters.getInitialMaxStreamDataBidiLocal());
                game.getOutputStream().write(1);
                Thread.sleep(250);
                assertArrayEquals(payload, game.getInputStream().readNBytes(payload.length));
                assertTrue(connection.isConnected());
                assertEquals(1, failed.getCount());
                if (loseDatagram) assertTrue(packets.get() >= 10);
                release.countDown();
                writer.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectsAHostWhoseCertificateDiffersFromThePinnedIdentity() throws Exception {
        var logger = LoggerFactory.getLogger(getClass());
        byte[] wrongIdentity = P2pKwikCertificate.create().encoded();
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger established = new AtomicInteger();
        AtomicReference<P2pKwikClientTunnel> tunnelRef = new AtomicReference<>();
        try (ServerSocket target = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             ServerSocket proxy = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             Socket game = new Socket(P2pSockets.loopbackAddress(), proxy.getLocalPort());
             var host = new P2pKwikHostServer(logger, target.getLocalPort(), P2pSockets.loopbackAddress(),
                 P2pKwikCertificate.create(), (transport, packet, address) -> tunnelRef.get().handlePacket(
                     packet.type() == P2pPacket.Type.QUIC_CERTIFICATE
                         ? P2pPacket.quicCertificate(packet.token(), packet.connectionId(), wrongIdentity) : packet));
             var tunnel = new P2pKwikClientTunnel(logger, 7, 9, proxy.accept(), 2_000,
                 packet -> host.handlePacket(packet, new InetSocketAddress(P2pSockets.loopbackAddress(), 4444), null),
                 () -> { }, failed::countDown, established::incrementAndGet)) {
            tunnelRef.set(tunnel);
            tunnel.start();
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals(0, established.get());
        }
    }

    @Test
    void exchangesTinyBidirectionalPayloadsBeforeEof() throws Exception {
        var logger = LoggerFactory.getLogger(getClass());
        CountDownLatch established = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<P2pKwikClientTunnel> tunnelRef = new AtomicReference<>();
        AtomicReference<SocketAddress> sourceEndpoint = new AtomicReference<>();
        AtomicReference<SocketAddress> targetEndpoint = new AtomicReference<>();
        int[] sizes = {1, 3, 15, 40, 255, 256, 257};
        try (ServerSocket target = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             ServerSocket proxy = new ServerSocket(0, 1, P2pSockets.loopbackAddress());
             Socket game = new Socket()) {
            game.setReceiveBufferSize(1_024);
            game.connect(proxy.getLocalSocketAddress());
            game.setSoTimeout(8_000);
            FutureTask<Void> serverTask = new FutureTask<>(() -> {
                try (Socket targetSocket = target.accept()) {
                    targetSocket.setSoTimeout(8_000);
                    sourceEndpoint.set(targetSocket.getRemoteSocketAddress());
                    targetEndpoint.set(targetSocket.getLocalSocketAddress());
                    assertTrue(P2pHostConnectionRegistry.getInstance().matches(sourceEndpoint.get(), targetEndpoint.get()));
                    for (int size : sizes) {
                        byte[] expectedRequest = createPayload(size, 1);
                        byte[] actualRequest = targetSocket.getInputStream().readNBytes(size);
                        assertArrayEquals(expectedRequest, actualRequest);

                        byte[] response = createPayload(size, 2);
                        targetSocket.getOutputStream().write(response);
                        targetSocket.getOutputStream().flush();
                    }
                    assertEquals(-1, targetSocket.getInputStream().read());
                }
                return null;
            });
            P2pRuntime.start("safra-test-server", serverTask);
            try (var host = new P2pKwikHostServer(logger, target.getLocalPort(), P2pSockets.loopbackAddress(),
                P2pKwikCertificate.create(), (transport, packet, address) -> tunnelRef.get().handlePacket(packet));
                 var tunnel = new P2pKwikClientTunnel(logger, 7, 9, proxy.accept(), 7_000,
                     packet -> host.handlePacket(packet, new InetSocketAddress(P2pSockets.loopbackAddress(), 4444), null),
                     () -> { }, failed::countDown, established::countDown)) {
                tunnelRef.set(tunnel);
                tunnel.start();
                assertTrue(established.await(5, TimeUnit.SECONDS));
                for (int size : sizes) {
                    byte[] request = createPayload(size, 1);
                    game.getOutputStream().write(request);
                    game.getOutputStream().flush();

                    byte[] expectedResponse = createPayload(size, 2);
                    byte[] actualResponse = game.getInputStream().readNBytes(size);
                    assertArrayEquals(expectedResponse, actualResponse);
                }
                game.shutdownOutput();
                assertEquals(-1, game.getInputStream().read());
                serverTask.get(5, TimeUnit.SECONDS);
                assertEquals(1, failed.getCount());
            }
            assertNotNull(sourceEndpoint.get());
            assertNotNull(targetEndpoint.get());
            assertFalse(P2pHostConnectionRegistry.getInstance().matches(sourceEndpoint.get(), targetEndpoint.get()));
        }
    }

    private static byte[] createPayload(int size, int seed) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        return payload;
    }
}
