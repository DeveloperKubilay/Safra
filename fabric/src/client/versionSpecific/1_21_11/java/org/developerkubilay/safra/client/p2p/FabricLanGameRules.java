package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.minecraft.world.level.gamerules.GameRules;
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

        GameRules copy = server.overworld().getGameRules().copy(client.level.enabledFeatures());
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot, null);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(Minecraft client) {
        if (client.level == null) {
            throw new IllegalStateException("Client world is not available");
        }
        return serialize(new GameRules(client.level.enabledFeatures()));
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        rules.visitGameRuleTypes(new GameRuleTypeVisitor() {
            @Override
            public void visit(GameRule rule) {
                values.put(rule.getIdentifier().toString(), rule.serialize(rules.get(rule)));
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
        rules.visitGameRuleTypes(new GameRuleTypeVisitor() {
            @Override
            public void visit(GameRule rule) {
                String serializedValue = snapshot.get(rule.getIdentifier().toString());
                if (serializedValue == null) {
                    return;
                }
                rule.deserialize(serializedValue).result().ifPresent(value -> rules.set(rule, value, server));
            }
        });
    }
}
