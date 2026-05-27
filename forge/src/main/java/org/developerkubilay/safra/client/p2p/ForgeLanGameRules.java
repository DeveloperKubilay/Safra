package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.GameRules;
import net.minecraft.world.WorldServer;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ForgeLanGameRules {
    private ForgeLanGameRules() {
    }

    public static Map<String, String> createDefaultSnapshot(Minecraft client) {
        if (client == null || client.theWorld == null) {
            return new LinkedHashMap<String, String>();
        }
        return serialize(client.theWorld.getGameRules());
    }

    public static Map<String, String> serialize(GameRules rules) {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        if (rules == null) {
            return values;
        }
        for (String rule : rules.getRules()) {
            values.put(rule, rules.getString(rule));
        }
        return values;
    }

    public static void applyToServer(MinecraftServer server, Map<String, String> snapshot) {
        if (server == null || snapshot == null || snapshot.isEmpty() || server.worldServers == null) {
            return;
        }
        for (WorldServer world : server.worldServers) {
            if (world == null) {
                continue;
            }
            apply(world.getGameRules(), snapshot);
        }
    }

    private static void apply(GameRules rules, Map<String, String> snapshot) {
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            rules.setOrCreateGameRule(entry.getKey(), entry.getValue());
        }
    }
}
