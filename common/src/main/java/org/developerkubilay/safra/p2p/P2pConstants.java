package org.developerkubilay.safra.p2p;

import java.util.Locale;
import java.util.regex.Pattern;

public final class P2pConstants {
    public static final String DEFAULT_RENDEZVOUS_URL = "https://safra.randdcodes.com";
    public static final String LOCAL_PROXY_HOST = "127.0.0.1";
    static final byte PROTOCOL_VERSION = 2;
    static final int HEADER_SIZE = 10;
    // RFC 9000'nin her ağda güvenle varsaydığı QUIC UDP tavanı 1200 bayttır.
    // Safra başlığı bunun dışındadır; dış UDP paketi 1218 bayta çıkar.
    static final int MAX_PAYLOAD_SIZE = 1200;
    static final String KWIK_APPLICATION_PROTOCOL = "safra-p2p";
    static final int KWIK_VIRTUAL_PORT = 4433;
    static final int KWIK_IDLE_TIMEOUT_SECONDS = 30;
    static final int MAX_DATAGRAM_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE;
    static final int SOCKET_BUFFER_SIZE = 1024 * 1024;
    static final int TCP_BUFFER_SIZE = 256 * 1024;
    static final long KWIK_DIRECT_FIRST_TIMEOUT_MS = 8_000L;
    static final long KWIK_DIRECT_SECOND_TIMEOUT_MS = 5_000L;
    static final long KWIK_RELAY_TIMEOUT_MS = 10_000L;
    static final int STUN_DISCOVERY_ATTEMPTS = 3;
    static final int STUN_INITIAL_RETRY_MS = 500;
    static final long STUN_REFRESH_MS = 20_000L;
    public static final long RENDEZVOUS_TIMEOUT_MS = 15_000L;
    public static final long RENDEZVOUS_REQUEST_TIMEOUT_MS = 8_000L;
    static final long RENDEZVOUS_RECONNECT_FIRST_DELAY_MS = 5_000L;
    static final long RENDEZVOUS_RECONNECT_DELAY_MS = 10_000L;
    static final long RENDEZVOUS_RECONNECT_SLOW_AFTER_MS = 60_000L;
    static final long RENDEZVOUS_RECONNECT_SLOW_DELAY_MS = 30_000L;
    static final long VOICE_HOST_WAIT_MS = 5_000L;
    public static final int TURN_REQUEST_TIMEOUT_MS = 8_000;
    public static final int TURN_DEFAULT_CREDENTIAL_TTL_SECONDS = 10 * 60;
    static final int TURN_DEFAULT_ALLOCATION_LIFETIME_SECONDS = 10 * 60;
    static final int TURN_DEFAULT_PERMISSION_LIFETIME_SECONDS = 4 * 60;
    public static final int TURN_REFRESH_SAFETY_MARGIN_SECONDS = 60;
    public static final int TURN_PERMISSION_REFRESH_MARGIN_SECONDS = 45;
    static final String ADDRESS_SCHEME = "p2p://";
    private static final String FORCE_DIRECT_THEN_TURN_PROPERTY = "safra.p2p.forceDirectThenTurn";
    private static final String FORCE_HOST_FAIL_SAFE_RELAY_PROPERTY = "safra.p2p.forceHostFailSafeRelay";
    private static final String NEVER_USE_RELAY_SERVER_PROPERTY = "safra.p2p.neverUseRelayServer";
    private static final String SITE_API_VERSION_PROPERTY = "safra.siteApiVersion";
    private static final String RENDEZVOUS_URL_PROPERTY = "safra.rendezvousUrl";
    private static final String DEFAULT_SITE_API_VERSION = "3.0";
    private static final Pattern SITE_API_VERSION_PATTERN = Pattern.compile("[a-z0-9][a-z0-9.-]{0,15}");
    private static final String TEST_MODE_DIRECT_THEN_TURN = "directthenturn";
    private static final String TEST_MODE_HOST_FAIL_SAFE = "hostfailsafe";
    static final String[] STUN_SERVERS = {
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302",
        "stun2.l.google.com:19302",
        "stun.cloudflare.com:3478",
        "global.stun.twilio.com:3478"
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
        if ("test-only".equals(siteApiVersion())) {
            return;
        }
        if (!hasExplicitRendezvousUrlOverride() && (runtimeRendezvousUrl == null || runtimeRendezvousUrl.isBlank())) {
            runtimeRendezvousUrl = DEFAULT_RENDEZVOUS_URL;
        }
    }

    public static boolean hasRendezvousUrl() {
        return !rendezvousUrl().isBlank();
    }

    public static boolean hasExplicitRendezvousUrlOverride() {
        return override(RENDEZVOUS_URL_PROPERTY, "SAFRA_RENDEZVOUS_URL") != null;
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
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static String rendezvousUrl() {
        String override = override(RENDEZVOUS_URL_PROPERTY, "SAFRA_RENDEZVOUS_URL");
        if (override != null) {
            return override;
        }

        String runtime = runtimeRendezvousUrl;
        return runtime == null || runtime.isBlank() ? "" : runtime.trim();
    }

    public static String siteApiVersion() {
        String override = override(SITE_API_VERSION_PROPERTY, "SAFRA_SITE_API_VERSION");
        if (override != null) {
            return normalizeSiteApiVersion(override);
        }

        String runtime = runtimeSiteApiVersion;
        return runtime == null || runtime.isBlank() ? DEFAULT_SITE_API_VERSION : normalizeSiteApiVersion(runtime);
    }

    static boolean forceDirectThenTurnRelay() {
        String override = override(FORCE_DIRECT_THEN_TURN_PROPERTY, "SAFRA_FORCE_DIRECT_THEN_TURN");
        return override != null
            ? Boolean.parseBoolean(override)
            : TEST_MODE_DIRECT_THEN_TURN.equals(buildTestMode());
    }

    static boolean forceHostFailSafeRelay() {
        String override = override(FORCE_HOST_FAIL_SAFE_RELAY_PROPERTY, "SAFRA_FORCE_HOST_FAIL_SAFE_RELAY");
        return override != null
            ? Boolean.parseBoolean(override)
            : TEST_MODE_HOST_FAIL_SAFE.equals(buildTestMode());
    }

    static boolean neverUseRelayServer() {
        String override = override(NEVER_USE_RELAY_SERVER_PROPERTY, "SAFRA_NEVER_USE_RELAY_SERVER");
        return override != null ? Boolean.parseBoolean(override) : runtimeNeverUseRelayServer;
    }

    /** The system property, then the environment variable; null when neither is set. */
    private static String override(String propertyKey, String environmentKey) {
        String property = System.getProperty(propertyKey);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }

        String environment = System.getenv(environmentKey);
        return environment == null || environment.isBlank() ? null : environment.trim();
    }

    private static String buildTestMode() {
        return SafraBuildInfo.testMode().trim().toLowerCase(Locale.ROOT);
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

    public static String normalizeSiteApiVersion(String siteApiVersion) {
        if (siteApiVersion == null) {
            return DEFAULT_SITE_API_VERSION;
        }
        String trimmed = siteApiVersion.trim().toLowerCase(Locale.ROOT);
        return SITE_API_VERSION_PATTERN.matcher(trimmed).matches() ? trimmed : DEFAULT_SITE_API_VERSION;
    }
}
