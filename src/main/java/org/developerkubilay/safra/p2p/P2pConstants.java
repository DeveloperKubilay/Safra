package org.developerkubilay.safra.p2p;

public final class P2pConstants {
    public static final String LOCAL_PROXY_HOST = "127.0.0.1";
    static final byte PROTOCOL_VERSION = 1;
    static final int HEADER_SIZE = 18;
    static final int MAX_PAYLOAD_SIZE = 1000;
    static final int MAX_DATAGRAM_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE;
    static final int SEND_WINDOW_SIZE = 32;
    static final int INITIAL_SEND_WINDOW_SIZE = 48;
    static final int MAX_SEND_WINDOW_SIZE = 128;
    static final int SOCKET_BUFFER_SIZE = 1024 * 1024;
    static final int TCP_BUFFER_SIZE = 256 * 1024;
    static final long MAINTENANCE_TICK_MS = 100L;
    static final long OPEN_RESEND_MS = 500L;
    static final long OPEN_TIMEOUT_MS = 20_000L;
    static final long INITIAL_RESEND_MS = 150L;
    static final long MIN_RESEND_MS = 90L;
    static final long MAX_RESEND_MS = 450L;
    static final long KEEP_ALIVE_MS = 10_000L;
    static final long CONNECTION_TIMEOUT_MS = 30_000L;
    static final int SELECTIVE_ACK_BITS = 32;
    static final int MICRO_BATCH_WARMUP_SAMPLES = 24;
    static final int MICRO_BATCH_MIN_THRESHOLD_BYTES = 64;
    static final int MICRO_BATCH_THRESHOLD_BYTES = 0;
    static final int MICRO_BATCH_MAX_THRESHOLD_BYTES = 0;
    static final int CLIENT_MICRO_BATCH_THRESHOLD_BYTES = 0;
    static final int FAST_RETRANSMIT_DUP_ACKS = 3;
    static final long FAST_RETRANSMIT_GUARD_MS = 60L;
    static final long NEGATIVE_ACK_REPEAT_MS = 80L;
    static final long ACK_REINFORCE_DELAY_MS = 12L;
    static final long DELAYED_ACK_MS = 2L;
    static final int DELAYED_ACK_PACKET_THRESHOLD = 2;
    static final long MICRO_BATCH_MIN_WAIT_NANOS = 350_000L;
    static final long MICRO_BATCH_WAIT_NANOS = 900_000L;
    static final long MICRO_BATCH_MAX_WAIT_NANOS = 1_500_000L;
    static final long MICRO_BATCH_POLL_NANOS = 100_000L;

    static final int RELIABLE_TUNNEL_FLUSH_THRESHOLD_BYTES = 32 * 1024;

    static final long DIAGNOSTIC_SUMMARY_MS = 5_000L;
    static final long HEAD_OF_LINE_WARN_MS = 150L;
    static final long WINDOW_STALL_WARN_MS = 150L;
    static final long IDLE_RESTART_MIN_MS = 500L;
    static final int PACING_BURST_PACKETS = 16;
    static final long MIN_PACING_INTERVAL_NANOS = 50_000L;
    static final long MAX_PACING_INTERVAL_NANOS = 1_000_000L;
    static final long STUN_REFRESH_MS = 20_000L;
    public static final long RENDEZVOUS_TIMEOUT_MS = 15_000L;
    public static final int TURN_REQUEST_TIMEOUT_MS = 6_000;
    public static final int TURN_DEFAULT_CREDENTIAL_TTL_SECONDS = 10 * 60;
    static final int TURN_DEFAULT_ALLOCATION_LIFETIME_SECONDS = 10 * 60;
    static final int TURN_DEFAULT_PERMISSION_LIFETIME_SECONDS = 4 * 60;
    public static final int TURN_REFRESH_SAFETY_MARGIN_SECONDS = 60;
    public static final int TURN_PERMISSION_REFRESH_MARGIN_SECONDS = 45;
    static final String ADDRESS_SCHEME = "p2p://";
    private static final String DIAGNOSTICS_PROPERTY = "safra.p2p.diagnostics";
    private static final String FORCE_TURN_PROPERTY = "safra.p2p.forceTurn";
    private static final String TURN_ENABLED_PROPERTY = "safra.p2p.turnEnabled";
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

    private static volatile String runtimeRendezvousUrl;

    private P2pConstants() {
    }

    public static void setRuntimeRendezvousUrl(String url) {
        runtimeRendezvousUrl = isValidRendezvousUrl(url) ? url.trim() : null;
    }

    public static boolean hasRendezvousUrl() {
        return !rendezvousUrl().isBlank();
    }

    public static boolean isValidRendezvousUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            String scheme = java.net.URI.create(url.trim()).getScheme();
            return "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)
                || "ws".equalsIgnoreCase(scheme)
                || "wss".equalsIgnoreCase(scheme);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static String rendezvousUrl() {
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

        String runtime = runtimeRendezvousUrl;
        if (runtime != null && !runtime.isBlank()) {
            return runtime.trim();
        }

        return "";
    }

    public static String rendezvousToken() {
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

    static boolean diagnosticsEnabled() {
        String property = System.getProperty(DIAGNOSTICS_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }

        return false;
    }

    static boolean forceTurnRelay() {
        String property = System.getProperty(FORCE_TURN_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }

        String environment = System.getenv("SAFRA_FORCE_TURN");
        if (environment != null && !environment.isBlank()) {
            return Boolean.parseBoolean(environment.trim());
        }

        return false;
    }

    static boolean turnEnabled() {
        String property = System.getProperty(TURN_ENABLED_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }

        String environment = System.getenv("SAFRA_TURN_ENABLED");
        if (environment != null && !environment.isBlank()) {
            return Boolean.parseBoolean(environment.trim());
        }

        return true;
    }

    public static int turnCredentialTtlSeconds() {
        return integerProperty("safra.p2p.turnCredentialTtlSeconds", TURN_DEFAULT_CREDENTIAL_TTL_SECONDS);
    }

    public static int turnAllocationLifetimeSeconds() {
        return integerProperty("safra.p2p.turnAllocationLifetimeSeconds", TURN_DEFAULT_ALLOCATION_LIFETIME_SECONDS);
    }

    public static int turnPermissionLifetimeSeconds() {
        return integerProperty("safra.p2p.turnPermissionLifetimeSeconds", TURN_DEFAULT_PERMISSION_LIFETIME_SECONDS);
    }

    private static int integerProperty(String key, int fallback) {
        String property = System.getProperty(key);
        if (property == null || property.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(property.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

}
