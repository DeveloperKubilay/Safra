package org.developerkubilay.safra.p2p;

public final class P2pConstants {
    static final byte PROTOCOL_VERSION = 1;
    static final int HEADER_SIZE = 18;
    static final int MAX_PAYLOAD_SIZE = 1200;
    static final int MAX_DATAGRAM_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE;
    static final int SEND_WINDOW_SIZE = 24;
    static final int FAST_RESEND_DUP_ACKS = 2;
    static final int SOCKET_BUFFER_SIZE = 1024 * 1024;
    static final int TCP_BUFFER_SIZE = 256 * 1024;
    static final long MAINTENANCE_TICK_MS = 50L;
    static final long OPEN_RESEND_MS = 250L;
    static final long OPEN_TIMEOUT_MS = 20_000L;
    static final long RESEND_MS = 180L;
    static final long KEEP_ALIVE_MS = 5_000L;
    static final long CONNECTION_TIMEOUT_MS = 30_000L;
    static final long STUN_REFRESH_MS = 20_000L;
    static final long RENDEZVOUS_TIMEOUT_MS = 15_000L;
    static final long RENDEZVOUS_PING_MS = 10 * 60 * 1000L;
    static final String DEFAULT_RENDEZVOUS_URL = "https://safra.developerkubilay.workers.dev";
    static final String ADDRESS_SCHEME = "p2p://";
    static final String[] STUN_SERVERS = {
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302"
    };

    private P2pConstants() {
    }

    static String rendezvousUrl() {
        String property = System.getProperty("safra.rendezvousUrl");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }

        String environment = System.getenv("SAFRA_RENDEZVOUS_URL");
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }

        String legacyEnvironment = System.getenv("SAFRA_SIGNALING_URL");
        if (legacyEnvironment != null && !legacyEnvironment.isBlank()) {
            return legacyEnvironment.trim();
        }

        return DEFAULT_RENDEZVOUS_URL;
    }

    static String rendezvousToken() {
        String property = System.getProperty("safra.rendezvousToken");
        if (property != null && !property.isBlank()) {
            return property.trim();
        }

        String environment = System.getenv("SAFRA_RENDEZVOUS_TOKEN");
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }

        String legacyEnvironment = System.getenv("SAFRA_SIGNALING_TOKEN");
        if (legacyEnvironment != null && !legacyEnvironment.isBlank()) {
            return legacyEnvironment.trim();
        }

        return "";
    }
}
