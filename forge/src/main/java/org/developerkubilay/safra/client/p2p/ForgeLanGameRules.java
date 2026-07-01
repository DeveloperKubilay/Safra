package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameRules;
import net.minecraft.world.server.ServerWorld;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ForgeLanGameRules {
    private ForgeLanGameRules() {
    }

    public static GameRules createEditableGameRules(Minecraft client, Map<String, String> snapshot) {
        IntegratedServer server = client.getIntegratedServer();
        if (server == null) {
            throw new IllegalStateException("Integrated server is not available");
        }

        GameRules copy = new GameRules();
        apply(copy, serialize(server.func_241755_D_().getGameRules()), null);
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot, null);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(Minecraft client) {
        IntegratedServer server = client.getIntegratedServer();
        if (server == null) {
            throw new IllegalStateException("Integrated server is not available");
        }
        return serialize(server.func_241755_D_().getGameRules());
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        GameRules.visitAll(new GameRules.IRuleEntryVisitor() {
            @Override
            public <T extends GameRules.RuleValue<T>> void visit(GameRules.RuleKey<T> key, GameRules.RuleType<T> type) {
                values.put(key.getName(), rules.get(key).stringValue());
            }
        });
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerWorld level : server.getWorlds()) {
            apply(level.getGameRules(), snapshot, server);
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot, MinecraftServer server) {
        GameRules.visitAll(new GameRules.IRuleEntryVisitor() {
            @Override
            public <T extends GameRules.RuleValue<T>> void visit(GameRules.RuleKey<T> key, GameRules.RuleType<T> type) {
                String serializedValue = snapshot.get(key.getName());
                if (serializedValue == null) {
                    return;
                }

                GameRules.RuleValue<?> rule = rules.get(key);
                if (rule instanceof GameRules.BooleanValue) {
                    GameRules.BooleanValue booleanRule = (GameRules.BooleanValue) rule;
                    booleanRule.set(Boolean.parseBoolean(serializedValue), server);
                } else if (rule instanceof GameRules.IntegerValue) {
                    GameRules.IntegerValue intRule = (GameRules.IntegerValue) rule;
                    safra$applyIntegerRule(intRule, serializedValue, server);
                }
            }
        });
    }

    private static void safra$applyIntegerRule(GameRules.IntegerValue intRule, String serializedValue, MinecraftServer server) {
        if (safra$invokeIntRule(intRule, "parseIntValue", serializedValue)) {
            return;
        }
        if (safra$invokeIntRule(intRule, "func_223568_b", serializedValue)) {
            return;
        }

        try {
            int value = Integer.parseInt(serializedValue);
            Method setMethod = intRule.getClass().getMethod("set", int.class, MinecraftServer.class);
            setMethod.setAccessible(true);
            setMethod.invoke(intRule, value, server);
        } catch (ReflectiveOperationException | NumberFormatException ignored) {
        }
    }

    private static boolean safra$invokeIntRule(GameRules.IntegerValue intRule, String methodName, String serializedValue) {
        try {
            Method method = intRule.getClass().getMethod(methodName, String.class);
            method.setAccessible(true);
            method.invoke(intRule, serializedValue);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
