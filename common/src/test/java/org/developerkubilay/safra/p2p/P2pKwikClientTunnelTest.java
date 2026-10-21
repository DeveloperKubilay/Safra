package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.impl.QuicClientConnectionImpl;
import tech.kwik.core.impl.TransportParameters;
import tech.kwik.core.stream.StreamManager;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class P2pKwikClientTunnelTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void negotiatesTheMeasuredWindowBeforeReceivingABurst(boolean loseDatagram) throws Exception {
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
}
