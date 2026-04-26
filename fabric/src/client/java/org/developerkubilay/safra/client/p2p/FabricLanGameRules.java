package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRuleVisitor;
import net.minecraft.world.rule.GameRules;

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

        GameRules copy = server.getOverworld().getGameRules().withEnabledFeatures(client.world.getEnabledFeatures());
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot, null);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(MinecraftClient client) {
        if (client.world == null) {
            throw new IllegalStateException("Client world is not available");
        }
        return serialize(new GameRules(client.world.getEnabledFeatures()));
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        rules.accept(new GameRuleVisitor() {
            @Override
            public void visit(GameRule rule) {
                values.put(rule.getId().toString(), rule.getValueName(rules.getValue(rule)));
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
        rules.accept(new GameRuleVisitor() {
            @Override
            public void visit(GameRule rule) {
                String serializedValue = snapshot.get(rule.getId().toString());
                if (serializedValue == null) {
                    return;
                }
                rule.deserialize(serializedValue).result().ifPresent(value -> rules.setValue(rule, value, server));
            }
        });
    }
}
