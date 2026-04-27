package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NeoForgeLanGameRules {
    private NeoForgeLanGameRules() {
    }

    public static GameRules createEditableGameRules(Minecraft client, Map<String, String> snapshot) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.level == null) {
            throw new IllegalStateException("Integrated server is not available");
        }

        GameRules copy = server.overworld().getGameRules().copy();
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot, null);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(Minecraft client) {
        if (client.level == null) {
            throw new IllegalStateException("Client level is not available");
        }
        return serialize(new GameRules());
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
            apply(level.getGameRules(), snapshot, server);
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
}
