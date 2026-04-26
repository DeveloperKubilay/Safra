package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import org.developerkubilay.safra.client.config.SafraClientConfig;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ForgeLanSessionState {
    private static volatile boolean p2pEnabled = true;
    private static volatile boolean onlineModeEnabled = false;
    private static volatile boolean allowCommandsEnabled;
    private static volatile Map<String, String> gameRuleSnapshot = Map.of();
    private static volatile Map<String, String> defaultGameRuleSnapshot = Map.of();

    private ForgeLanSessionState() {
    }

    public static void loadFromConfig() {
        SafraClientConfig config = SafraClientConfig.get();
        p2pEnabled = config.isOpenToLanP2pEnabled();
        onlineModeEnabled = config.isOpenToLanOnlineModeEnabled();
        allowCommandsEnabled = config.isOpenToLanAllowCommandsEnabled();
        gameRuleSnapshot = new LinkedHashMap<>(config.getOpenToLanGameRules());
    }

    public static void initializeGameRules(Minecraft client) {
        if (defaultGameRuleSnapshot.isEmpty()) {
            defaultGameRuleSnapshot = new LinkedHashMap<>(ForgeLanGameRules.createDefaultSnapshot(client));
        }
        if (gameRuleSnapshot.isEmpty()) {
            gameRuleSnapshot = new LinkedHashMap<>(defaultGameRuleSnapshot);
        }
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

    public static boolean isAllowCommandsEnabled() {
        return allowCommandsEnabled;
    }

    public static void setAllowCommandsEnabled(boolean enabled) {
        allowCommandsEnabled = enabled;
        SafraClientConfig.get().setOpenToLanAllowCommandsEnabled(enabled);
    }

    public static Map<String, String> getGameRuleSnapshot() {
        return new LinkedHashMap<>(gameRuleSnapshot);
    }

    public static void setGameRuleSnapshot(Map<String, String> snapshot) {
        gameRuleSnapshot = new LinkedHashMap<>(snapshot);
        SafraClientConfig.get().setOpenToLanGameRules(gameRuleSnapshot);
    }

    public static void resetServerSettings() {
        allowCommandsEnabled = false;
        gameRuleSnapshot = defaultGameRuleSnapshot.isEmpty()
            ? Map.of()
            : new LinkedHashMap<>(defaultGameRuleSnapshot);
        SafraClientConfig config = SafraClientConfig.get();
        config.setOpenToLanAllowCommandsEnabled(false);
        config.setOpenToLanGameRules(gameRuleSnapshot);
    }
}
