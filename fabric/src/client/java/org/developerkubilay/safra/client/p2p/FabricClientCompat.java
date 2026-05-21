package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class FabricClientCompat {
    private FabricClientCompat() {
    }

    public static MutableText translatable(String key, Object... args) {
        try {
            Method method = Text.class.getMethod("translatable", String.class, Object[].class);
            return (MutableText) method.invoke(null, key, args);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.text.TranslatableText");
            Constructor<?> constructor = clazz.getConstructor(String.class, Object[].class);
            return (MutableText) constructor.newInstance(key, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create translatable text", exception);
        }
    }

    public static MutableText literal(String value) {
        try {
            Method method = Text.class.getMethod("literal", String.class);
            return (MutableText) method.invoke(null, value);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.text.LiteralText");
            Constructor<?> constructor = clazz.getConstructor(String.class);
            return (MutableText) constructor.newInstance(value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create literal text", exception);
        }
    }

    public static Text screenDone() {
        return screenText("DONE", "Done");
    }

    public static Text screenBack() {
        return screenText("BACK", "Back");
    }

    public static void copyServerInfo(ServerInfo target, ServerInfo source) {
        for (String methodName : new String[]{"copyWithSettingsFrom", "copyFrom"}) {
            try {
                Method method = ServerInfo.class.getMethod(methodName, ServerInfo.class);
                method.invoke(target, source);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (String fieldName : new String[]{"resourcePackPolicy", "icon"}) {
            try {
                Field field = ServerInfo.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, field.get(source));
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    public static void narrate(MinecraftClient client, Text text) {
        if (client == null || text == null) {
            return;
        }

        try {
            Method method = MinecraftClient.class.getMethod("getNarratorManager");
            Object narrator = method.invoke(client);
            narrator.getClass().getMethod("narrate", Text.class).invoke(narrator, text);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.client.util.NarratorManager");
            Field instanceField = clazz.getField("INSTANCE");
            Object narrator = instanceField.get(null);
            clazz.getMethod("narrate", Text.class).invoke(narrator, text);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not narrate message", exception);
        }
    }

    private static Text screenText(String fieldName, String fallbackKey) {
        for (String className : new String[]{
            "net.minecraft.client.gui.screen.ScreenTexts",
            "net.minecraft.screen.ScreenTexts"
        }) {
            try {
                Class<?> clazz = Class.forName(className);
                Field field = clazz.getField(fieldName);
                return (Text) field.get(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return literal(fallbackKey);
    }
}
