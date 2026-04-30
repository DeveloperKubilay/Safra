package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.GameRules;
import net.minecraft.world.server.ServerWorld;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ForgeLanGameRules {
    private ForgeLanGameRules() {
    }

    public static GameRules createEditableGameRules(Minecraft client, Map<String, String> snapshot) {
        GameRules copy = new GameRules();
        if (!snapshot.isEmpty()) {
            apply(copy, snapshot);
        }
        return copy;
    }

    public static Map<String, String> createDefaultSnapshot(Minecraft client) {
        return serialize(new GameRules());
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        GameRules.visitGameRuleTypes(new GameRules.IRuleEntryVisitor() {
            @Override
            public <T extends GameRules.RuleValue<T>> void visit(GameRules.RuleKey<T> key, GameRules.RuleType<T> type) {
                values.put(key.getId(), rules.getRule(key).toString());
            }
        });
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (ServerWorld world : server.getAllLevels()) {
            apply(world.getGameRules(), snapshot);
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot) {
        CompoundNBT tag = new CompoundNBT();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            tag.putString(entry.getKey(), entry.getValue());
        }
        rules.loadFromTag(tag);
    }
}
