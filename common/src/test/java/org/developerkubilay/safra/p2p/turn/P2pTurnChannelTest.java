package org.developerkubilay.safra.p2p.turn;

import org.developerkubilay.safra.p2p.P2pRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class P2pTurnChannelTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 1210, 65535})
    void framesPreserveLengthAndPadOnlyOutsideThePayload(int size) throws Exception {
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) 59);
        byte[] frame = P2pTurnProtocol.channelData(0x4000, payload);
        assertEquals(4 + ((size + 3) & ~3), frame.length);
        assertEquals(size, Short.toUnsignedInt(ByteBuffer.wrap(frame, 2, 2).getShort()));
        assertArrayEquals(payload, Arrays.copyOfRange(frame, 4, 4 + size));
        if (size == 1210) assertEquals(1264, frame.length + 48);
    }

    @Test
    void rejectsInvalidChannelsAndUnrepresentableLengths() {
        for (int channel : new int[]{-1, 0, 0x3FFF, 0x5000, 0x7FFF}) {
            assertThrows(IOException.class, () -> P2pTurnProtocol.channelData(channel, new byte[1]));
        }
        assertThrows(IOException.class, () -> P2pTurnProtocol.channelData(0x4000, new byte[65536]));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exchangesDataAndInterleavedControlOverUdpAndTcp(boolean tcp) throws Exception {
        InetSocketAddress peer = new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 25565);
        byte[] payload = new byte[1210];
        Arrays.fill(payload, (byte) 53);
        try (Fixture fixture = new Fixture(tcp, 1500)) {
            FutureTask<Void> relay = fixture.run(() -> {
                byte[] request = fixture.read();
                P2pTurnMessage message = P2pTurnMessage.parse(request, request.length);
                assertEquals(P2pTurnProtocol.TURN_CHANNEL_BIND_REQUEST, message.type());
                assertEquals(peer, message.xorAddress(P2pTurnProtocol.ATTR_XOR_PEER_ADDRESS));
                int channel = Short.toUnsignedInt(ByteBuffer.wrap(message.attribute(P2pTurnProtocol.ATTR_CHANNEL_NUMBER)).getShort());
                fixture.write(P2pTurnProtocol.channelData(channel, new byte[]{7, 8, 9}));
                fixture.write(response(message, P2pTurnProtocol.TURN_CHANNEL_BIND_RESPONSE));
                byte[] frame = fixture.read();
                assertEquals(1264, frame.length + 48);
                assertArrayEquals(payload, Arrays.copyOfRange(frame, 4, payload.length + 4));
                fixture.write(frame);
                return null;
            });
            fixture.transport.send(new DatagramPacket(payload, payload.length, peer));
            assertArrayEquals(new byte[]{7, 8, 9}, fixture.receive(peer));
            assertArrayEquals(payload, fixture.receive(peer));
            relay.get(3, TimeUnit.SECONDS);
            FutureTask<Void> renewal = fixture.run(() -> {
                byte[] request = fixture.read();
                P2pTurnMessage message = P2pTurnMessage.parse(request, request.length);
                assertEquals(P2pTurnProtocol.TURN_CHANNEL_BIND_REQUEST, message.type());
                fixture.write(response(message, P2pTurnProtocol.TURN_CHANNEL_BIND_RESPONSE));
                return null;
            });
            Map<?, ?> channels = (Map<?, ?>) get(fixture.transport, "channelsByPeer");
            Object channel = channels.values().iterator().next();
            set(channel, "permissionExpiresAtNanos", System.nanoTime() - 1);
            fixture.transport.send(new DatagramPacket(new byte[]{1}, 1, peer));
            renewal.get(3, TimeUnit.SECONDS);
            assertEquals(1, channels.size());
            assertTrue((long) get(channel, "permissionExpiresAtNanos") > System.nanoTime());
        }
    }

    @Test
    void retransmitsALostChannelBindUsingTheSameTransaction() throws Exception {
        try (Fixture fixture = new Fixture(false, 1500)) {
            InetSocketAddress peer = new InetSocketAddress("192.0.2.1", 25565);
            FutureTask<Void> relay = fixture.run(() -> {
                byte[] first = fixture.read(), retried = fixture.read();
                assertArrayEquals(first, retried);
                fixture.write(response(P2pTurnMessage.parse(retried, retried.length), P2pTurnProtocol.TURN_CHANNEL_BIND_RESPONSE));
                assertEquals(8, fixture.read().length);
                return null;
            });
            fixture.transport.send(new DatagramPacket(new byte[]{1}, 1, peer));
            relay.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void ignoresUnknownExpiredAndTruncatedChannelFrames() throws Exception {
        try (Fixture fixture = new Fixture(false, 1500)) {
            var channelType = Class.forName(P2pTurnDatagramTransport.class.getName() + "$PeerChannel");
            var constructor = channelType.getDeclaredConstructor(int.class, InetSocketAddress.class);
            constructor.setAccessible(true);
            Object channel = constructor.newInstance(0x4000, new InetSocketAddress("192.0.2.1", 25565));
            set(channel, "expiresAtNanos", System.nanoTime() + TimeUnit.MINUTES.toNanos(1));
            @SuppressWarnings("unchecked") var channels = (Map<Integer, Object>) get(fixture.transport, "channelsByNumber");
            channels.put(0x4000, channel);
            var receive = P2pTurnDatagramTransport.class.getDeclaredMethod("receiveChannel", byte[].class, int.class);
            receive.setAccessible(true);
            for (byte[] malformed : new byte[][]{new byte[0], new byte[3], {0x40, 1, 0, 1, 7}, {0x40, 0, 0, 8, 7}, {0x40, 0, 0, 0, 7}}) {
                receive.invoke(fixture.transport, malformed, malformed.length);
            }
            set(channel, "expiresAtNanos", System.nanoTime() - 1);
            byte[] expired = P2pTurnProtocol.channelData(0x4000, new byte[]{1});
            receive.invoke(fixture.transport, expired, expired.length);
            assertTrue(((java.util.Queue<?>) get(fixture.transport, "incoming")).isEmpty());
        }
    }

    private static byte[] response(P2pTurnMessage request, int type) {
        return ByteBuffer.allocate(20).putShort((short) type).putShort((short) 0)
            .putInt(P2pTurnProtocol.MAGIC_COOKIE).put(request.transactionId()).array();
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
        final DatagramSocket udp;
        final Socket stream;
        final P2pTurnDatagramTransport transport;
        InetSocketAddress clientAddress;

        Fixture(boolean tcp, int timeout) throws Exception {
            Socket clientStream = null;
            DatagramSocket clientUdp = null;
            InetSocketAddress server;
            if (tcp) {
                try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                    clientStream = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                    stream = listener.accept();
                    stream.setSoTimeout(3000);
                    server = (InetSocketAddress) listener.getLocalSocketAddress();
                }
                udp = null;
            } else {
                stream = null;
                udp = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                udp.setSoTimeout(3000);
                clientUdp = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                clientUdp.connect(udp.getLocalSocketAddress());
                clientAddress = (InetSocketAddress) clientUdp.getLocalSocketAddress();
                server = (InetSocketAddress) udp.getLocalSocketAddress();
            }
            var constructor = P2pTurnDatagramTransport.class.getDeclaredConstructor(Logger.class, String.class,
                DatagramSocket.class, Socket.class, InetSocketAddress.class, String.class, int.class, String.class, String.class);
            constructor.setAccessible(true);
            transport = constructor.newInstance(LoggerFactory.getLogger(getClass()), "channel-test", clientUdp,
                clientStream, server, tcp ? "TCP" : "UDP", timeout, "user", "password");
            set(transport, "realm", "test");
            set(transport, "nonce", "test");
            run(() -> {
                var method = P2pTurnDatagramTransport.class.getDeclaredMethod("receiveLoop");
                method.setAccessible(true);
                method.invoke(transport);
                return null;
            });
        }

        FutureTask<Void> run(java.util.concurrent.Callable<Void> action) {
            var task = new FutureTask<>(action);
            P2pRuntime.start("safra-channel-test", task);
            return task;
        }

        byte[] read() throws Exception {
            if (udp != null) {
                var packet = new DatagramPacket(new byte[65535], 65535);
                udp.receive(packet);
                clientAddress = (InetSocketAddress) packet.getSocketAddress();
                return Arrays.copyOf(packet.getData(), packet.getLength());
            }
            var in = new DataInputStream(stream.getInputStream());
            byte[] prefix = in.readNBytes(4);
            int length = Short.toUnsignedInt(ByteBuffer.wrap(prefix, 2, 2).getShort());
            int remaining = (prefix[0] & 0xC0) == 0x40 ? (length + 3) & ~3 : length + 16;
            byte[] frame = Arrays.copyOf(prefix, 4 + remaining);
            in.readFully(frame, 4, remaining);
            return frame;
        }

        void write(byte[] frame) throws Exception {
            if (udp != null) udp.send(new DatagramPacket(frame, frame.length, clientAddress));
            else {
                stream.getOutputStream().write(frame);
                stream.getOutputStream().flush();
            }
        }

        byte[] receive(InetSocketAddress peer) throws Exception {
            var packet = new DatagramPacket(new byte[2048], 2048);
            transport.receive(packet);
            assertEquals(peer, packet.getSocketAddress());
            return Arrays.copyOf(packet.getData(), packet.getLength());
        }

        public void close() throws Exception {
            transport.close();
            if (udp != null) udp.close();
            if (stream != null) stream.close();
        }
    }
}
