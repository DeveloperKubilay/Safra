package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FabricLanGameRules {
    private FabricLanGameRules() {
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
            throw new IllegalStateException("Client world is not available");
        }
        return serialize(new GameRules());
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                values.put(key.getId(), rules.getRule(key).serialize());
            }

            @Override
            public void visitInteger(GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
                values.put(key.getId(), rules.getRule(key).serialize());
            }
        });
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerLevel world : server.getAllLevels()) {
            apply(world.getGameRules(), snapshot, server);
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot, MinecraftServer server) {
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                String serializedValue = snapshot.get(key.getId());
                if (serializedValue != null) {
                    rules.getRule(key).set(Boolean.parseBoolean(serializedValue), server);
                }
            }

            @Override
            public void visitInteger(GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
                String serializedValue = snapshot.get(key.getId());
                if (serializedValue == null) {
                    return;
                }

                GameRules.IntegerValue value = rules.getRule(key);
                if (value.tryDeserialize(serializedValue)) {
                    value.set(value.get(), server);
                }
            }
        });
    }
}
