package org.developerkubilay.safra.p2p;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class P2pHostConnectionRegistryTest {
    @Test
    void matchesOnlyTheExactLiveTupleBeforeConnect() throws Exception {
        P2pHostConnectionRegistry registry = new P2pHostConnectionRegistry();
        InetSocketAddress target = new InetSocketAddress(P2pSockets.loopbackAddress(), 25565);
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(P2pSockets.loopbackAddress(), 0));
            try (var registration = registry.register(socket, target)) {
                assertFalse(socket.isConnected());
                assertTrue(registry.matches(socket.getLocalSocketAddress(), target));
                assertFalse(registry.matches(socket.getLocalSocketAddress(), null));
                assertFalse(registry.matches(null, target));
                assertFalse(registry.matches(socket.getLocalSocketAddress(),
                    InetSocketAddress.createUnresolved("localhost", 25565)));
                assertFalse(registry.matches(socket.getLocalSocketAddress(),
                    new InetSocketAddress(P2pSockets.loopbackAddress(), 25566)));
                assertFalse(registry.matches(socket.getLocalSocketAddress(),
                    new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 25565)));
                assertFalse(registry.matches(target, socket.getLocalSocketAddress()));
                socket.close();
                assertFalse(registry.matches(socket.getLocalSocketAddress(), target));
            }
        }
    }

    @Test
    void staleTokenCannotRemoveReplacementAndCloseIsIdempotent() throws Exception {
        P2pHostConnectionRegistry registry = new P2pHostConnectionRegistry();
        InetSocketAddress target = new InetSocketAddress(P2pSockets.loopbackAddress(), 25565);
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(P2pSockets.loopbackAddress(), 0));
            var old = registry.register(socket, target);
            try (var replacement = registry.register(socket, target)) {
                old.close();
                old.close();
                assertTrue(registry.matches(socket.getLocalSocketAddress(), target));
            }
            assertFalse(registry.matches(socket.getLocalSocketAddress(), target));
        }
    }

    @Test
    void rejectsUnboundClosedAndUnresolvedRegistrations() throws Exception {
        P2pHostConnectionRegistry registry = new P2pHostConnectionRegistry();
        InetSocketAddress target = new InetSocketAddress(P2pSockets.loopbackAddress(), 25565);
        try (Socket socket = new Socket()) {
            assertThrows(IllegalArgumentException.class, () -> registry.register(socket, target));
            socket.bind(new InetSocketAddress(P2pSockets.loopbackAddress(), 0));
            assertThrows(IllegalArgumentException.class, () -> registry.register(socket,
                InetSocketAddress.createUnresolved("localhost", 25565)));
            socket.close();
            assertThrows(IllegalArgumentException.class, () -> registry.register(socket, target));
        }
    }
}
