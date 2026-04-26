package org.developerkubilay.safra.p2p;

public final class P2pConstants {
    public static final String LOCAL_PROXY_HOST = "127.0.0.1";
    static final byte PROTOCOL_VERSION = 1;
    static final int HEADER_SIZE = 18;
    static final int MAX_PAYLOAD_SIZE = 1000;
    static final int MAX_DATAGRAM_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE;
    static final int SEND_WINDOW_SIZE = 32;
    static final int SOCKET_BUFFER_SIZE = 1024 * 1024;
    static final int TCP_BUFFER_SIZE = 256 * 1024;
    static final long MAINTENANCE_TICK_MS = 100L;
    static final long OPEN_RESEND_MS = 500L;
    static final long OPEN_TIMEOUT_MS = 20_000L;
    static final long RESEND_MS = 350L;
    static final long KEEP_ALIVE_MS = 10_000L;
    static final long CONNECTION_TIMEOUT_MS = 30_000L;
    static final long STUN_REFRESH_MS = 20_000L;
    static final long RENDEZVOUS_TIMEOUT_MS = 15_000L;
    static final long RENDEZVOUS_PING_MS = 10 * 60 * 1000L;
    static final String DEFAULT_RENDEZVOUS_URL = "https://safra.developerkubilay.workers.dev";
    static final String ADDRESS_SCHEME = "p2p://";
    static final String[][] STUN_SERVER_GROUPS = {
        {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun2.l.google.com:19302"
        },
        {
            "stun.cloudflare.com:3478",
            "global.stun.twilio.com:3478"
        }
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
