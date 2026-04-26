package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;
import org.developerkubilay.safra.mixin.client.GameRulesRuleAccessor;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FabricLanGameRules {
    private FabricLanGameRules() {
    }

    public static GameRules createEditableGameRules(MinecraftClient client, Map<String, String> snapshot) {
        IntegratedServer server = client.getServer();
        if (server == null || client.world == null) {
            throw new IllegalStateException("Integrated server is not available");
        }

        GameRules copy = server.getOverworld().getGameRules().copy();
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null) {
            throw new IllegalStateException("Integrated server is not available");
        }
        return serialize(server.getOverworld().getGameRules().copy());
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        rules.accept(new GameRules.Visitor() {
            @Override
            public <T extends GameRules.Rule<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                values.put(key.getName(), rules.get(key).serialize());
            }
        });
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerWorld world : server.getWorlds()) {
            GameRules updatedRules = world.getGameRules().copy();
            apply(updatedRules, snapshot);
            world.getGameRules().setAllValues(updatedRules, server);
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot) {
        rules.accept(new GameRules.Visitor() {
            @Override
            public <T extends GameRules.Rule<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                String serializedValue = snapshot.get(key.getName());
                if (serializedValue == null) {
                    return;
                }
                ((GameRulesRuleAccessor) rules.get(key)).safra$deserialize(serializedValue);
            }
        });
    }
}
