package org.developerkubilay.safra.p2p;

final class P2pCompat {
    private P2pCompat() {
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
