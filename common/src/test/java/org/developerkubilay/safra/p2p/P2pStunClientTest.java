package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.developerkubilay.safra.p2p.P2pSockets.AddressFamily.IPV4;

@Timeout(10)
class P2pStunClientTest {
    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final byte[] TRANSACTION_ID = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void discoversIpv4EvenWhenEarlierRequestsAreDropped(int responseAttempt) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket server = new DatagramSocket(0, loopback);
             DatagramSocket client = new DatagramSocket(0, loopback)) {
            server.setSoTimeout(4000);
            client.setSoTimeout(77);
            InetSocketAddress serverAddress = (InetSocketAddress) server.getLocalSocketAddress();
            List<P2pStunClient.PendingRequest> pending = pendingRequest(serverAddress);
            FutureTask<Integer> responder = new FutureTask<>(() -> {
                byte[] bytes = new byte[512];
                for (int attempt = 1; attempt <= responseAttempt; attempt++) {
                    DatagramPacket request = new DatagramPacket(bytes, bytes.length);
                    server.receive(request);
                    assertEquals(20, request.getLength());
                    assertArrayEquals(TRANSACTION_ID,
                        java.util.Arrays.copyOfRange(request.getData(), 8, 20));
                    if (attempt == responseAttempt) {
                        byte[] response = bindingResponse();
                        server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                    }
                }
                return responseAttempt;
            });
            Thread thread = new Thread(responder, "safra-test-stun-responder");
            thread.setDaemon(true);
            thread.start();

            sendInitialRequest(client, serverAddress);
            var discovered = new P2pStunClient().discoverCandidates(client, IPV4, pending);

            assertTrue(discovered.containsKey(IPV4));
            assertEquals(new InetSocketAddress("192.0.2.1", 25565), discovered.get(IPV4).publicAddress());
            assertEquals(serverAddress, discovered.get(IPV4).stunServer());
            assertTrue(pending.isEmpty());
            assertEquals(77, client.getSoTimeout());
            assertEquals(responseAttempt, responder.get(5, TimeUnit.SECONDS));
            server.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class,
                () -> server.receive(new DatagramPacket(new byte[512], 512)));
        }
    }

    @Test
    void exhaustsAllAttemptsAndRestoresSocketTimeoutWhenNoServerAnswers() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket server = new DatagramSocket(0, loopback);
             DatagramSocket client = new DatagramSocket(0, loopback)) {
            client.setSoTimeout(77);
            InetSocketAddress serverAddress = (InetSocketAddress) server.getLocalSocketAddress();
            List<P2pStunClient.PendingRequest> pending = pendingRequest(serverAddress);
            sendInitialRequest(client, serverAddress);

            assertTrue(new P2pStunClient().discoverCandidates(client, IPV4, pending).isEmpty());
            assertEquals(77, client.getSoTimeout());
            assertEquals(1, pending.size());
            server.setSoTimeout(100);
            for (int attempt = 0; attempt < P2pConstants.STUN_DISCOVERY_ATTEMPTS; attempt++) {
                DatagramPacket request = new DatagramPacket(new byte[512], 512);
                server.receive(request);
                assertEquals(20, request.getLength());
            }
            assertThrows(SocketTimeoutException.class,
                () -> server.receive(new DatagramPacket(new byte[512], 512)));
        }
    }

    @Test
    void returnsImmediatelyWhenThereAreNoPendingRequests() throws Exception {
        try (DatagramSocket client = new DatagramSocket((java.net.SocketAddress) null)) {
            client.setSoTimeout(77);
            assertTrue(new P2pStunClient().discoverCandidates(client, IPV4, new ArrayList<>()).isEmpty());
            assertEquals(77, client.getSoTimeout());
            assertFalse(client.isBound());
        }
    }

    private static List<P2pStunClient.PendingRequest> pendingRequest(InetSocketAddress server) {
        return new ArrayList<>(List.of(new P2pStunClient.PendingRequest(server, TRANSACTION_ID.clone())));
    }

    private static void sendInitialRequest(DatagramSocket client, InetSocketAddress server) throws Exception {
        byte[] request = ByteBuffer.allocate(20).putShort((short) 0x0001).putShort((short) 0)
            .putInt(MAGIC_COOKIE).put(TRANSACTION_ID).array();
        client.send(new DatagramPacket(request, request.length, server));
    }

    private static byte[] bindingResponse() {
        return ByteBuffer.allocate(32).putShort((short) 0x0101).putShort((short) 12)
            .putInt(MAGIC_COOKIE).put(TRANSACTION_ID)
            .putShort((short) 0x0020).putShort((short) 8)
            .put((byte) 0).put((byte) 1).putShort((short) (25565 ^ (MAGIC_COOKIE >>> 16)))
            .putInt(0xC0000201 ^ MAGIC_COOKIE).array();
    }
}
