package org.developerkubilay.safra.client.p2p;

import com.mojang.serialization.Dynamic;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
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
        return serialize(new GameRules());
    }

    public static Map<String, String> serialize(GameRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        CompoundTag tag = rules.createTag();
        for (String key : tag.getAllKeys()) {
            values.put(key, tag.getString(key));
        }
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
        CompoundTag tag = rules.createTag();
        snapshot.forEach(tag::putString);
        GameRules updatedRules = new GameRules(new Dynamic<>(NbtOps.INSTANCE, tag));
        rules.assignFrom(updatedRules, server);
    }
}
