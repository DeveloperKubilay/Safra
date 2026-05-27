package org.developerkubilay.safra.p2p;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.function.Consumer;

final class SafraRendezvousClient {
    private SafraRendezvousClient() {
    }

    static HostSession startHost(int tcpPort, int tunnelToken, String preferredRendezvousCode,
                                 Collection<InetSocketAddress> publicEndpoints,
                                 Consumer<InetSocketAddress> punchHandler,
                                 Consumer<InetSocketAddress> voicePunchHandler) throws IOException {
        throw new IOException("Safra rendezvous 1.10.2 portunda henuz devre disi");
    }

    static JoinSession join(String code, Collection<InetSocketAddress> publicEndpoints) throws IOException {
        throw new IOException("Safra rendezvous 1.10.2 portunda henuz devre disi");
    }

    static final class HostSession implements AutoCloseable {
        private final String code;

        HostSession(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        void publishVoice(Collection<InetSocketAddress> publicEndpoints) {
        }

        @Override
        public void close() {
        }
    }

    static final class JoinSession implements AutoCloseable {
        private final String code;
        private final InetSocketAddress hostAddress;
        private final int tunnelToken;

        JoinSession(String code, InetSocketAddress hostAddress, int tunnelToken) {
            this.code = code;
            this.hostAddress = hostAddress;
            this.tunnelToken = tunnelToken;
        }

        String code() {
            return code;
        }

        InetSocketAddress hostAddress() {
            return hostAddress;
        }

        int tunnelToken() {
            return tunnelToken;
        }

        InetSocketAddress resolveVoice(Collection<InetSocketAddress> publicEndpoints) throws IOException {
            throw new IOException("Safra voice rendezvous 1.10.2 portunda henuz devre disi");
        }

        @Override
        public void close() {
        }
    }
}
