package org.developerkubilay.safra.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class FabricScreenCompat {
    private static final Method SET_SCREEN_METHOD = resolveMethod("setScreen");
    private static final Method OPEN_SCREEN_METHOD = resolveMethod("openScreen");

    private FabricScreenCompat() {
    }

    public static void open(MinecraftClient client, Screen screen) {
        if (client == null) {
            return;
        }

        Method method = SET_SCREEN_METHOD != null ? SET_SCREEN_METHOD : OPEN_SCREEN_METHOD;
        if (method == null) {
            throw new IllegalStateException("No compatible MinecraftClient screen method found");
        }

        try {
            method.invoke(client, screen);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new RuntimeException("Failed to switch Fabric screen", exception);
        }
    }

    private static Method resolveMethod(String name) {
        try {
            return MinecraftClient.class.getMethod(name, Screen.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
