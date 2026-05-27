package org.developerkubilay.safra.p2p;

public final class P2pText {
    private P2pText() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
