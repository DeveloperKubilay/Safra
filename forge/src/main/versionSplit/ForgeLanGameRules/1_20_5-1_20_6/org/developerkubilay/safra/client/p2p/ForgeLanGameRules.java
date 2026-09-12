package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ForgeLanGameRules {
    private ForgeLanGameRules() {
    }

    public static GameRules createEditableGameRules(Minecraft client, Map<String, String> snapshot) {
        IntegratedServer server = client.getSingleplayerServer();
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
        IntegratedServer server = client.getSingleplayerServer();
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
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                values.put(key.getId(), rules.getRule(key).serialize());
            }
        });
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            GameRules rules = getLevelRules(level);
            if (rules != null) {
                apply(rules, snapshot, server);
            }
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot, MinecraftServer server) {
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String serializedValue = snapshot.get(key.getId());
                if (serializedValue == null) {
                    return;
                }

                GameRules.Value<?> rule = rules.getRule(key);
                if (rule instanceof GameRules.BooleanValue booleanRule) {
                    booleanRule.set(Boolean.parseBoolean(serializedValue), server);
                } else if (rule instanceof GameRules.IntegerValue intRule) {
                    try {
                        intRule.set(Integer.parseInt(serializedValue), server);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        });
    }

    private static GameRules getServerRules(MinecraftServer server) {
        Object rules = call(server, "getGameRules", "m_129900_");
        if (rules instanceof GameRules gameRules) {
            return gameRules;
        }
        return getLevelRules(server.overworld());
    }

    private static GameRules getLevelRules(ServerLevel level) {
        if (level == null) {
            return null;
        }
        Object rules = call(level, "getGameRules", "m_46469_");
        return rules instanceof GameRules gameRules ? gameRules : null;
    }

    private static GameRules copyRules(GameRules rules) {
        Object copy = call(rules, "copy", "m_46202_");
        if (copy instanceof GameRules gameRules) {
            return gameRules;
        }
        throw new IllegalStateException("GameRules copy accessor is not available");
    }

    private static Object call(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
