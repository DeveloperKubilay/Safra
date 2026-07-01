package org.developerkubilay.safra.p2p;

import com.google.common.net.HostAndPort;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class P2pShareCode {
    public static final int DEFAULT_RENDEZVOUS_CODE_LENGTH = 12;
    public static final int FIXED_RENDEZVOUS_CODE_LENGTH = 16;
    private static final String RENDEZVOUS_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern RENDEZVOUS_CODE_PATTERN = Pattern.compile("[A-HJ-NP-Z2-9]{6,16}");

    private final String host;
    private final int port;
    private final int token;
    private final String rendezvousCode;

    public P2pShareCode(String host, int port, int token) {
        this(host, port, token, null);
    }

    public P2pShareCode(String host, int port, int token, String rendezvousCode) {
        if (rendezvousCode != null && !rendezvousCode.trim().isEmpty()) {
            rendezvousCode = normalizeRendezvousCode(rendezvousCode);
            if (rendezvousCode == null) {
                throw new IllegalArgumentException("rendezvous code is invalid");
            }
            this.host = "";
            this.port = 0;
            this.token = 0;
            this.rendezvousCode = rendezvousCode;
        } else {
            Objects.requireNonNull(host, "host");
            if (host.trim().isEmpty()) {
                throw new IllegalArgumentException("host is blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
            this.host = host;
            this.port = port;
            this.token = token;
            this.rendezvousCode = rendezvousCode;
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public int token() {
        return token;
    }

    public String rendezvousCode() {
        return rendezvousCode;
    }

    public static boolean isStoredAddress(String address) {
        return address != null && address.toLowerCase(Locale.ROOT).startsWith(P2pConstants.ADDRESS_SCHEME);
    }

    public static P2pShareCode parse(String rawAddress) {
        if (rawAddress == null) {
            throw new IllegalArgumentException("address is null");
        }

        String normalized = rawAddress.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith(P2pConstants.ADDRESS_SCHEME)) {
            normalized = normalized.substring(P2pConstants.ADDRESS_SCHEME.length());
        }

        String rendezvousCode = normalizeRendezvousCode(normalized);
        if (rendezvousCode != null) {
            return rendezvous(rendezvousCode);
        }

        String endpointPart = normalized;
        int parsedToken = 0;
        int tokenSeparator = normalized.indexOf('#');
        if (tokenSeparator >= 0) {
            endpointPart = normalized.substring(0, tokenSeparator).trim();
            String tokenPart = normalized.substring(tokenSeparator + 1).trim();
            if (!tokenPart.isEmpty()) {
                parsedToken = Integer.parseUnsignedInt(tokenPart, 36);
            }
        }

        HostAndPort hostAndPort = HostAndPort.fromString(endpointPart).withDefaultPort(25565);
        String host = hostAndPort.getHost().trim();
        if (host.trim().isEmpty()) {
            throw new IllegalArgumentException("host is blank");
        }

        return new P2pShareCode(host, hostAndPort.getPort(), parsedToken);
    }

    public static P2pShareCode rendezvous(String code) {
        return new P2pShareCode("", 0, 0, code);
    }

    public static int rendezvousTunnelToken(String code) {
        String normalized = normalizeRendezvousCode(code);
        if (normalized == null) {
            throw new IllegalArgumentException("rendezvous code is invalid");
        }

        int token = java.util.Arrays.hashCode(normalized.getBytes(StandardCharsets.UTF_8));
        return token == 0 ? 0x51F15EED : token;
    }

    public static String createRendezvousCode() {
        return createRendezvousCode(DEFAULT_RENDEZVOUS_CODE_LENGTH);
    }

    public static String createRendezvousCode(int length) {
        if (length < 6 || length > 16) {
            throw new IllegalArgumentException("rendezvous code length out of range");
        }

        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(RENDEZVOUS_CODE_ALPHABET.charAt(random.nextInt(RENDEZVOUS_CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    public boolean isRendezvous() {
        return rendezvousCode != null && !rendezvousCode.trim().isEmpty();
    }

    public String toStoredAddress() {
        return P2pConstants.ADDRESS_SCHEME + toDisplayCode();
    }

    public String toDisplayCode() {
        if (isRendezvous()) {
            return rendezvousCode;
        }

        String base = HostAndPort.fromParts(host, port).toString();
        return token == 0 ? base : base + "#" + Integer.toUnsignedString(token, 36);
    }

    public InetSocketAddress toSocketAddress() {
        if (isRendezvous()) {
            throw new IllegalStateException("rendezvous code must be resolved before opening a socket");
        }

        return new InetSocketAddress(host, port);
    }

    public static String normalizeRendezvousCode(String rawCode) {
        if (rawCode == null) {
            return null;
        }
        String code = rawCode.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        if (!RENDEZVOUS_CODE_PATTERN.matcher(code).matches()) {
            return null;
        }

        return code;
    }
}
