package org.developerkubilay.safra.p2p;

import com.google.common.net.HostAndPort;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record P2pShareCode(String host, int port, int token, String rendezvousCode) {
    private static final Pattern RENDEZVOUS_CODE_PATTERN = Pattern.compile("[A-HJ-NP-Z2-9]{6,16}");

    public P2pShareCode(String host, int port, int token) {
        this(host, port, token, null);
    }

    public P2pShareCode {
        if (rendezvousCode != null && !rendezvousCode.isBlank()) {
            rendezvousCode = normalizeRendezvousCode(rendezvousCode);
            if (rendezvousCode == null) {
                throw new IllegalArgumentException("rendezvous code is invalid");
            }
            host = "";
            port = 0;
            token = 0;
        } else {
            Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw new IllegalArgumentException("host is blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
        }
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
        if (host.isBlank()) {
            throw new IllegalArgumentException("host is blank");
        }

        return new P2pShareCode(host, hostAndPort.getPort(), parsedToken);
    }

    public static P2pShareCode rendezvous(String code) {
        return new P2pShareCode("", 0, 0, code);
    }

    public boolean isRendezvous() {
        return rendezvousCode != null && !rendezvousCode.isBlank();
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

    private static String normalizeRendezvousCode(String rawCode) {
        String code = rawCode.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        if (!RENDEZVOUS_CODE_PATTERN.matcher(code).matches()) {
            return null;
        }

        return code;
    }
}
