package org.developerkubilay.safra.p2p;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class P2pHostConnectionRegistry {
    private static final P2pHostConnectionRegistry INSTANCE = new P2pHostConnectionRegistry();
    private final ConcurrentMap<Endpoints, Registration> connections = new ConcurrentHashMap<>();

    public static P2pHostConnectionRegistry getInstance() {
        return INSTANCE;
    }

    Registration register(Socket socket, InetSocketAddress target) {
        SocketAddress source = socket.getLocalSocketAddress();
        if (!valid(source) || !valid(target) || socket.isClosed()) {
            throw new IllegalArgumentException("Host tunnel requires an open bound socket and resolved endpoints");
        }
        Endpoints endpoints = new Endpoints((InetSocketAddress) source, target);
        Registration registration = new Registration(endpoints, socket);
        connections.put(endpoints, registration);
        return registration;
    }

    public boolean matches(SocketAddress source, SocketAddress target) {
        if (!valid(source) || !valid(target)) {
            return false;
        }
        Endpoints endpoints = new Endpoints((InetSocketAddress) source, (InetSocketAddress) target);
        Registration registration = connections.get(endpoints);
        if (registration == null) {
            return false;
        }
        if (registration.closed.get() || registration.socket.isClosed()) {
            connections.remove(endpoints, registration);
            return false;
        }
        return true;
    }

    private static boolean valid(SocketAddress address) {
        return address instanceof InetSocketAddress endpoint && !endpoint.isUnresolved()
            && !endpoint.getAddress().isAnyLocalAddress() && endpoint.getPort() > 0;
    }

    private record Endpoints(InetSocketAddress source, InetSocketAddress target) {
    }

    final class Registration implements AutoCloseable {
        private final Endpoints endpoints;
        private final Socket socket;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(Endpoints endpoints, Socket socket) {
            this.endpoints = endpoints;
            this.socket = socket;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                connections.remove(endpoints, this);
            }
        }
    }
}
