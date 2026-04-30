package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FabricLanGameRules {
    private FabricLanGameRules() {
    }

    public static GameRules createEditableGameRules(MinecraftClient client, Map<String, String> snapshot) {
        GameRules copy = new GameRules();
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(MinecraftClient client) {
        return serialize(new GameRules());
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        GameRules.forEachType(new GameRules.RuleConsumer() {
            @Override
            public <T extends GameRules.Rule<T>> void accept(GameRules.RuleKey<T> key, GameRules.RuleType<T> type) {
                values.put(key.getName(), rules.get(key).toString());
            }
        });
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerWorld world : server.getWorlds()) {
            apply(world.getGameRules(), snapshot);
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            tag.putString(entry.getKey(), entry.getValue());
        }
        rules.load(tag);
    }
}
