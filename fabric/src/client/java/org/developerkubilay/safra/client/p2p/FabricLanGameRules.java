package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FabricLanGameRules {
    private static final Method INT_RULE_DESERIALIZE = findIntRuleDeserialize();
    private static final Class<?> GAME_RULES_VISITOR_CLASS = findGameRulesVisitorClass();
    private static final Method GAME_RULES_ACCEPT = findGameRulesAcceptMethod();

    private FabricLanGameRules() {
    }

    public static GameRules createEditableGameRules(MinecraftClient client, Map<String, String> snapshot) {
        IntegratedServer server = client.getServer();
        if (server == null || client.world == null) {
            throw new IllegalStateException("Integrated server is not available");
        }

        GameRules copy = server.getOverworld().getGameRules().copy();
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot, null);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(MinecraftClient client) {
        if (client.world == null) {
            throw new IllegalStateException("Client world is not available");
        }
        return serialize(new GameRules());
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        visitRules((key, type) -> {
            try {
                values.put(key.getName(), rules.get(key).serialize());
            } catch (RuntimeException ignored) {
            }
        });
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerWorld world : server.getWorlds()) {
            apply(world.getGameRules(), snapshot, server);
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot, MinecraftServer server) {
        visitRules((key, type) -> {
            String serializedValue = snapshot.get(key.getName());
            if (serializedValue == null) {
                return;
            }

            GameRules.Rule<?> rule = rules.get(key);
            if (rule instanceof GameRules.BooleanRule booleanRule) {
                booleanRule.set(Boolean.parseBoolean(serializedValue), server);
            } else if (rule instanceof GameRules.IntRule intRule) {
                applyIntRule(intRule, serializedValue);
            }
        });
    }

    private static void applyIntRule(GameRules.IntRule intRule, String serializedValue) {
        if (INT_RULE_DESERIALIZE == null) {
            return;
        }
        try {
            INT_RULE_DESERIALIZE.invoke(intRule, serializedValue);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Method findIntRuleDeserialize() {
        try {
            Method method = GameRules.IntRule.class.getDeclaredMethod("deserialize", String.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static Class<?> findGameRulesVisitorClass() {
        for (Class<?> nestedClass : GameRules.class.getDeclaredClasses()) {
            if ("Visitor".equals(nestedClass.getSimpleName())) {
                return nestedClass;
            }
        }
        return null;
    }

    private static Method findGameRulesAcceptMethod() {
        if (GAME_RULES_VISITOR_CLASS == null) {
            return null;
        }
        try {
            return GameRules.class.getDeclaredMethod("accept", GAME_RULES_VISITOR_CLASS);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static void visitRules(RuleVisitor visitor) {
        if (GAME_RULES_ACCEPT == null || GAME_RULES_VISITOR_CLASS == null) {
            return;
        }
        try {
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                GAME_RULES_VISITOR_CLASS.getClassLoader(),
                new Class<?>[]{GAME_RULES_VISITOR_CLASS},
                (instance, method, args) -> {
                    if ("visit".equals(method.getName()) && args != null && args.length == 2) {
                        visitor.visit((GameRules.Key<?>) args[0], args[1]);
                    }
                    return null;
                }
            );
            GAME_RULES_ACCEPT.invoke(null, proxy);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @FunctionalInterface
    private interface RuleVisitor {
        void visit(GameRules.Key<?> key, Object type);
    }
}
