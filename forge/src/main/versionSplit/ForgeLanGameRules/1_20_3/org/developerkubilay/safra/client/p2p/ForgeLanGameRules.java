package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ForgeLanGameRules {
    private ForgeLanGameRules() {
    }

    public static GameRules createEditableGameRules(Minecraft client, Map<String, String> snapshot) {
        IntegratedServer server = getSingleplayerServer(client);
        if (server == null) {
            throw new IllegalStateException("Integrated server is not available");
        }

        GameRules rules = getServerRules(server);
        if (rules == null) {
            throw new IllegalStateException("Integrated server game rules are not available");
        }

        GameRules copy = copyRules(rules);
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot, null);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(Minecraft client) {
        IntegratedServer server = getSingleplayerServer(client);
        if (server != null) {
            GameRules rules = getServerRules(server);
            if (rules != null) {
                return serialize(copyRules(rules));
            }
        }
        return Map.of();
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!visitRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                GameRules.Value<?> rule = getRuleValue(rules, key);
                String serialized = rule == null ? null : serializeValue(rule);
                if (serialized != null) {
                    values.put(key.getId(), serialized);
                }
            }
        })) {
            return Map.of();
        }
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerLevel level : levels(server)) {
            GameRules rules = getLevelRules(level);
            if (rules != null) {
                apply(rules, snapshot, server);
            }
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot, MinecraftServer server) {
        visitRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String serializedValue = snapshot.get(key.getId());
                if (serializedValue == null) {
                    return;
                }

                GameRules.Value<?> rule = getRuleValue(rules, key);
                if (rule == null) {
                    return;
                }
                if (rule instanceof GameRules.BooleanValue booleanRule) {
                    call(booleanRule, new Class<?>[]{boolean.class, MinecraftServer.class}, new Object[]{Boolean.parseBoolean(serializedValue), server}, "set", "m_46246_");
                } else if (rule instanceof GameRules.IntegerValue intRule) {
                    try {
                        call(intRule, new Class<?>[]{int.class, MinecraftServer.class}, new Object[]{Integer.parseInt(serializedValue), server}, "set", "m_151489_");
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        });
    }

    public static IntegratedServer getSingleplayerServer(Minecraft client) {
        Object server = call(client, new Class<?>[0], new Object[0], "getSingleplayerServer", "m_91092_", "m_91090_");
        if (server == null) {
            server = getField(client, "singleplayerServer", "f_91007_");
        }
        return server instanceof IntegratedServer integratedServer ? integratedServer : null;
    }

    public static int getServerPort(IntegratedServer server) {
        Object port = call(server, new Class<?>[0], new Object[0], "getServerPort", "method_3756", "M", "m_7448_", "getPort");
        return port instanceof Integer value ? value : -1;
    }

    private static GameRules getServerRules(MinecraftServer server) {
        Object rules = call(server, new Class<?>[0], new Object[0], "getGameRules", "m_129900_");
        if (rules instanceof GameRules gameRules) {
            return gameRules;
        }

        ServerLevel level = firstLevel(server);
        return getLevelRules(level);
    }

    private static GameRules copyRules(GameRules rules) {
        Object copy = call(rules, new Class<?>[0], new Object[0], "copy", "m_46202_");
        if (copy instanceof GameRules gameRules) {
            return gameRules;
        }
        throw new IllegalStateException("GameRules copy accessor is not available");
    }

    private static boolean visitRuleTypes(GameRules.GameRuleTypeVisitor visitor) {
        return call(GameRules.class, new Class<?>[]{GameRules.GameRuleTypeVisitor.class}, new Object[]{visitor}, "visitGameRuleTypes", "m_46164_") != null;
    }

    private static GameRules.Value<?> getRuleValue(GameRules rules, GameRules.Key<?> key) {
        Object value = call(rules, new Class<?>[]{GameRules.Key.class}, new Object[]{key}, "getRule", "m_46170_");
        return value instanceof GameRules.Value<?> rule ? rule : null;
    }

    private static String serializeValue(GameRules.Value<?> rule) {
        Object value = call(rule, new Class<?>[0], new Object[0], "serialize", "m_5831_");
        return value instanceof String serialized ? serialized : null;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<ServerLevel> levels(MinecraftServer server) {
        Object iterable = call(server, new Class<?>[0], new Object[0], "getAllLevels", "m_129785_");
        if (iterable instanceof Iterable<?>) {
            return (Iterable<ServerLevel>) iterable;
        }
        ServerLevel level = firstLevel(server);
        return level == null ? java.util.List.of() : java.util.List.of(level);
    }

    private static ServerLevel firstLevel(MinecraftServer server) {
        Object overworld = call(server, new Class<?>[0], new Object[0], "overworld", "m_129783_");
        if (overworld instanceof ServerLevel level) {
            return level;
        }
        for (ServerLevel level : levelsWithoutFallback(server)) {
            return level;
        }
        return null;
    }

    private static GameRules getLevelRules(ServerLevel level) {
        if (level == null) {
            return null;
        }
        Object rules = call(level, new Class<?>[0], new Object[0], "getGameRules", "m_46469_");
        return rules instanceof GameRules gameRules ? gameRules : null;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<ServerLevel> levelsWithoutFallback(MinecraftServer server) {
        Object iterable = call(server, new Class<?>[0], new Object[0], "getAllLevels", "m_129785_");
        if (iterable instanceof Iterable<?>) {
            return (Iterable<ServerLevel>) iterable;
        }
        return java.util.List.of();
    }

    private static Object getField(Object target, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object call(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        Object instance = target instanceof Class<?> ? null : target;
        for (String name : names) {
            try {
                Method method = type.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(instance, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
