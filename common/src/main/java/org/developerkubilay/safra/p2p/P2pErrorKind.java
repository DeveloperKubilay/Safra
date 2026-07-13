package org.developerkubilay.safra.p2p;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public enum P2pErrorKind {
    RATE_LIMITED("safra.p2p.error.rate_limited"),
    SERVER_BUSY("safra.p2p.error.server_busy"),
    SESSION_NOT_FOUND("safra.p2p.error.session_not_found"),
    INVALID_SESSION("safra.p2p.error.invalid_session"),
    OTHER("");

    private final String translationKey;

    P2pErrorKind(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public static P2pErrorKind classify(Throwable throwable) {
        Set<Throwable> visited = new HashSet<>();
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            P2pErrorKind kind = classify(current.getMessage());
            if (kind != OTHER) {
                return kind;
            }
            current = current.getCause();
        }
        return OTHER;
    }

    public static P2pErrorKind classify(String message) {
        if (message == null || message.isBlank()) {
            return OTHER;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("http/1.1 header parser received no bytes")
            || normalized.contains("http 429")
            || normalized.contains("too many active sessions")) {
            return RATE_LIMITED;
        }
        if (normalized.contains("http 521")) {
            return SERVER_BUSY;
        }
        if (normalized.contains("http 404") || normalized.contains("session not found")) {
            return SESSION_NOT_FOUND;
        }
        if (normalized.contains("invalid session")
            || normalized.contains("invalidsession")
            || normalized.contains("invalid_session")) {
            return INVALID_SESSION;
        }
        return OTHER;
    }
}
