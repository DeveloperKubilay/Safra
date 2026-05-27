package org.developerkubilay.safra.p2p.turn;

import java.util.List;

public final class P2pTurnCredentials {
    private final List<TurnServer> udpServers;
    private final String username;
    private final String credential;
    private final int ttlSeconds;

    public P2pTurnCredentials(List<TurnServer> udpServers, String username, String credential, int ttlSeconds) {
        this.udpServers = udpServers;
        this.username = username;
        this.credential = credential;
        this.ttlSeconds = ttlSeconds;
    }

    public List<TurnServer> udpServers() {
        return udpServers;
    }

    public String username() {
        return username;
    }

    public String credential() {
        return credential;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }

    public static final class TurnServer {
        private final String host;
        private final int port;

        public TurnServer(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }
    }
}
