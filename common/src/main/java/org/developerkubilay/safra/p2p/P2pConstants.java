package org.developerkubilay.safra.p2p;

public final class P2pConstants {
    public static final String DEFAULT_RENDEZVOUS_URL = "https://safra.randdcodes.com";
    public static final String LOCAL_PROXY_HOST = "127.0.0.1";
    static final byte PROTOCOL_VERSION = 1;
    static final int HEADER_SIZE = 18;
    static final int MAX_PAYLOAD_SIZE = 1200;
    static final int MAX_DATAGRAM_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE;
    static final int MIN_SEND_WINDOW_SIZE = 8;
    static final int INITIAL_SEND_WINDOW_SIZE = 32;
    static final int MAX_SEND_WINDOW_SIZE = 256;
    static final int SOCKET_BUFFER_SIZE = 1024 * 1024;
    static final int TCP_BUFFER_SIZE = 256 * 1024;
    static final long MAINTENANCE_TICK_MS = 25L;
    static final long OPEN_RESEND_MS = 500L;
    static final long DIRECT_OPEN_FALLBACK_MS = 8_000L;
    static final long OPEN_TIMEOUT_MS = 20_000L;
    static final int STUN_DISCOVERY_ATTEMPTS = 3;
    static final int STUN_INITIAL_RETRY_MS = 500;
    static final long INITIAL_RESEND_MS = 500L;
    static final long MIN_RESEND_MS = 200L;
    static final long MAX_RESEND_MS = 1_500L;
    static final long KEEP_ALIVE_MS = 10_000L;
    static final long CONNECTION_TIMEOUT_MS = 30_000L;
    static final int SELECTIVE_ACK_BITS = 32;
    static final int FAST_RETRANSMIT_DUP_ACKS = 3;
    static final long DEFAULT_FAST_RETRANSMIT_GUARD_MS = 60L;
    static final long MIN_FAST_RETRANSMIT_GUARD_MS = 30L;
    static final long MAX_FAST_RETRANSMIT_GUARD_MS = 250L;
    static final long NEGATIVE_ACK_REPEAT_MS = 30L;
    static final long ACK_REINFORCE_DELAY_MS = 8L;
    static final long DELAYED_ACK_MS = 2L;
    static final int DELAYED_ACK_PACKET_THRESHOLD = 2;
    static final long DIAGNOSTIC_SUMMARY_MS = 5_000L;
    static final long HEAD_OF_LINE_WARN_MS = 150L;
    static final long WINDOW_STALL_WARN_MS = 150L;
    static final long IDLE_RESTART_MIN_MS = 500L;
    static final int PACING_BURST_PACKETS = 16;
    static final long MIN_PACING_INTERVAL_NANOS = 50_000L;
    static final long MAX_PACING_INTERVAL_NANOS = 50_000_000L;
    static final long STUN_REFRESH_MS = 20_000L;
    public static final long RENDEZVOUS_TIMEOUT_MS = 15_000L;
    static final long RENDEZVOUS_RECONNECT_FIRST_DELAY_MS = 5_000L;
    static final long RENDEZVOUS_RECONNECT_DELAY_MS = 10_000L;
    static final long RENDEZVOUS_RECONNECT_SLOW_AFTER_MS = 60_000L;
    static final long RENDEZVOUS_RECONNECT_SLOW_DELAY_MS = 30_000L;
    static final long VOICE_HOST_WAIT_MS = 5_000L;
    public static final int TURN_REQUEST_TIMEOUT_MS = 6_000;
    public static final int TURN_DEFAULT_CREDENTIAL_TTL_SECONDS = 10 * 60;
    static final int TURN_DEFAULT_ALLOCATION_LIFETIME_SECONDS = 10 * 60;
    static final int TURN_DEFAULT_PERMISSION_LIFETIME_SECONDS = 4 * 60;
    public static final int TURN_REFRESH_SAFETY_MARGIN_SECONDS = 60;
    public static final int TURN_PERMISSION_REFRESH_MARGIN_SECONDS = 45;
    static final int RELIABLE_TUNNEL_FLUSH_THRESHOLD_BYTES = 32 * 1024;
    static final String ADDRESS_SCHEME = "p2p://";
    private static final String DIAGNOSTICS_PROPERTY = "safra.p2p.diagnostics";
    private static final String DIAGNOSTICS_INTERVAL_PROPERTY = "safra.p2p.diagnosticsIntervalMs";
    private static final String DIAGNOSTICS_TICK_DRIFT_WARN_PROPERTY = "safra.p2p.diagnosticsTickDriftWarnMs";
    private static final String FORCE_DIRECT_THEN_TURN_PROPERTY = "safra.p2p.forceDirectThenTurn";
    private static final String FORCE_HOST_FAIL_SAFE_RELAY_PROPERTY = "safra.p2p.forceHostFailSafeRelay";
    private static final String NEVER_USE_RELAY_SERVER_PROPERTY = "safra.p2p.neverUseRelayServer";
    private static final String SITE_API_VERSION_PROPERTY = "safra.siteApiVersion";
    private static final String TEST_MODE_DIRECT_THEN_TURN = "directthenturn";
    private static final String TEST_MODE_HOST_FAIL_SAFE = "hostfailsafe";
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
    private static volatile boolean runtimeNeverUseRelayServer;
    private static volatile String runtimeSiteApiVersion;

    private P2pConstants() {
    }

    public static void setRuntimeRendezvousUrl(String url) {
        runtimeRendezvousUrl = isValidRendezvousUrl(url) ? url.trim() : null;
    }

    public static void applyDefaultRendezvousUrlIfAbsent() {
        if (!hasExplicitRendezvousUrlOverride() && (runtimeRendezvousUrl == null || runtimeRendezvousUrl.isBlank())) {
            runtimeRendezvousUrl = DEFAULT_RENDEZVOUS_URL;
        }
    }

    public static boolean hasRendezvousUrl() {
        return !rendezvousUrl().isBlank();
    }

    public static boolean hasExplicitRendezvousUrlOverride() {
        String property = System.getProperty("safra.rendezvousUrl");
        if (property != null && !property.isBlank()) {
            return true;
        }

        String environment = System.getenv("SAFRA_RENDEZVOUS_URL");
        if (environment != null && !environment.isBlank()) {
            return true;
        }

        String legacyEnvironment = System.getenv("SAFRA_SIGNALING_URL");
        return legacyEnvironment != null && !legacyEnvironment.isBlank();
    }

    public static void setRuntimeNeverUseRelayServer(boolean neverUseRelayServer) {
        runtimeNeverUseRelayServer = neverUseRelayServer;
    }

    public static void setRuntimeSiteApiVersion(String siteApiVersion) {
        runtimeSiteApiVersion = normalizeSiteApiVersion(siteApiVersion);
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

    public static String siteApiVersion() {
        String property = System.getProperty(SITE_API_VERSION_PROPERTY);
        if (property != null && !property.isBlank()) {
            return normalizeSiteApiVersion(property);
        }

        String environment = System.getenv("SAFRA_SITE_API_VERSION");
        if (environment != null && !environment.isBlank()) {
            return normalizeSiteApiVersion(environment);
        }

        String runtime = runtimeSiteApiVersion;
        if (runtime != null && !runtime.isBlank()) {
            return normalizeSiteApiVersion(runtime);
        }

        return "3.0";
    }

    public static boolean useApi30Rendezvous() {
        return "3.0".equals(siteApiVersion());
    }

    static boolean diagnosticsEnabled() {
        String property = System.getProperty(DIAGNOSTICS_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }

        return false;
    }

    static long diagnosticsSummaryMs() {
        return longProperty(DIAGNOSTICS_INTERVAL_PROPERTY, DIAGNOSTIC_SUMMARY_MS);
    }

    static long diagnosticsTickDriftWarnMs() {
        return longProperty(DIAGNOSTICS_TICK_DRIFT_WARN_PROPERTY, Math.max(150L, MAINTENANCE_TICK_MS * 6L));
    }

    static boolean forceDirectThenTurnRelay() {
        String property = System.getProperty(FORCE_DIRECT_THEN_TURN_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }

        String environment = System.getenv("SAFRA_FORCE_DIRECT_THEN_TURN");
        if (environment != null && !environment.isBlank()) {
            return Boolean.parseBoolean(environment.trim());
        }

        return TEST_MODE_DIRECT_THEN_TURN.equals(buildTestMode());
    }

    static boolean forceHostFailSafeRelay() {
        String property = System.getProperty(FORCE_HOST_FAIL_SAFE_RELAY_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }

        String environment = System.getenv("SAFRA_FORCE_HOST_FAIL_SAFE_RELAY");
        if (environment != null && !environment.isBlank()) {
            return Boolean.parseBoolean(environment.trim());
        }

        return TEST_MODE_HOST_FAIL_SAFE.equals(buildTestMode());
    }

    static boolean neverUseRelayServer() {
        String property = System.getProperty(NEVER_USE_RELAY_SERVER_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property.trim());
        }

        String environment = System.getenv("SAFRA_NEVER_USE_RELAY_SERVER");
        if (environment != null && !environment.isBlank()) {
            return Boolean.parseBoolean(environment.trim());
        }

        return runtimeNeverUseRelayServer;
    }

    private static String buildTestMode() {
        return SafraBuildInfo.testMode().trim().toLowerCase(java.util.Locale.ROOT);
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
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static long longProperty(String key, long fallback) {
        String property = System.getProperty(key);
        if (property == null || property.isBlank()) {
            return fallback;
        }

        try {
            return Long.parseLong(property.trim());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String normalizeSiteApiVersion(String siteApiVersion) {
        if (siteApiVersion == null || siteApiVersion.isBlank()) {
            return "3.0";
        }
        return "3.0";
    }
}
