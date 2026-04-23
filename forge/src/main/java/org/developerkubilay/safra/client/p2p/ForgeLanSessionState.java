package org.developerkubilay.safra.client.p2p;

import org.developerkubilay.safra.client.config.SafraClientConfig;

public final class ForgeLanSessionState {
    private static volatile boolean p2pEnabled = true;
    private static volatile boolean onlineModeEnabled = true;

    private ForgeLanSessionState() {
    }

    public static void loadFromConfig() {
        SafraClientConfig config = SafraClientConfig.get();
        p2pEnabled = config.isOpenToLanP2pEnabled();
        onlineModeEnabled = config.isOpenToLanOnlineModeEnabled();
    }

    public static boolean isP2pEnabled() {
        return p2pEnabled;
    }

    public static void setP2pEnabled(boolean enabled) {
        p2pEnabled = enabled;
        SafraClientConfig.get().setOpenToLanP2pEnabled(enabled);
    }

    public static boolean isOnlineModeEnabled() {
        return onlineModeEnabled;
    }

    public static void setOnlineModeEnabled(boolean enabled) {
        onlineModeEnabled = enabled;
        SafraClientConfig.get().setOpenToLanOnlineModeEnabled(enabled);
    }
}
