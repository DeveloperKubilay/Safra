package org.developerkubilay.safra.client.p2p;

import net.minecraft.world.GameRules;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FabricLanSessionState {
    private static volatile boolean p2pEnabled = true;
    private static volatile boolean onlineModeEnabled = false;
    private static volatile boolean allowCommandsEnabled;
    private static volatile boolean fixedCodeEnabled;
    private static volatile String fixedCode = "";
    private static volatile Map<String, String> gameRuleSnapshot = Map.of();
    private static volatile Map<String, String> defaultGameRuleSnapshot = Map.of();

    private FabricLanSessionState() {
    }

    public static void loadFromConfig() {
        SafraClientConfig config = SafraClientConfig.get();
        p2pEnabled = config.isOpenToLanP2pEnabled();
        onlineModeEnabled = config.isOpenToLanOnlineModeEnabled();
        allowCommandsEnabled = config.isOpenToLanAllowCommandsEnabled();
        fixedCodeEnabled = config.isOpenToLanFixedCodeEnabled();
        fixedCode = config.getOpenToLanFixedCode();
        gameRuleSnapshot = new LinkedHashMap<>(config.getOpenToLanGameRules());
    }

    public static void initializeGameRules(MinecraftClient client, GameRules rules) {
        if (defaultGameRuleSnapshot.isEmpty()) {
            defaultGameRuleSnapshot = new LinkedHashMap<>(FabricLanGameRules.createDefaultSnapshot(client));
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

    public static boolean isFixedCodeEnabled() {
        return fixedCodeEnabled;
    }

    public static void setFixedCodeEnabled(boolean enabled) {
        fixedCodeEnabled = enabled;
        SafraClientConfig.get().setOpenToLanFixedCodeEnabled(enabled);
    }

    public static String getFixedCode() {
        if (fixedCode.isBlank()) {
            fixedCode = SafraClientConfig.get().ensureOpenToLanFixedCode();
        }
        return fixedCode;
    }

    public static String regenerateFixedCode() {
        fixedCode = org.developerkubilay.safra.p2p.P2pShareCode.createRendezvousCode(
            org.developerkubilay.safra.p2p.P2pShareCode.FIXED_RENDEZVOUS_CODE_LENGTH
        );
        SafraClientConfig.get().setOpenToLanFixedCode(fixedCode);
        return fixedCode;
    }

    public static Map<String, String> getGameRuleSnapshot() {
        return new LinkedHashMap<>(gameRuleSnapshot);
    }

    public static void setGameRuleSnapshot(Map<String, String> snapshot) {
        gameRuleSnapshot = new LinkedHashMap<>(snapshot);
        SafraClientConfig.get().setOpenToLanGameRules(gameRuleSnapshot);
    }

    public static void resetGameRules() {
        gameRuleSnapshot = defaultGameRuleSnapshot.isEmpty()
            ? Map.of()
            : new LinkedHashMap<>(defaultGameRuleSnapshot);
        SafraClientConfig.get().setOpenToLanGameRules(gameRuleSnapshot);
    }
}
