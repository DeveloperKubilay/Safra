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
            Method method = Text.class.getMethod("method_43469", String.class, Object[].class);
            return (MutableText) method.invoke(null, key, args);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Method method = Text.class.getMethod("method_43471", String.class);
            return (MutableText) method.invoke(null, key);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.class_2588");
            Constructor<?> constructor = clazz.getConstructor(String.class, Object[].class);
            return (MutableText) constructor.newInstance(key, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create translatable text", exception);
        }
    }

    public static MutableText literal(String value) {
        try {
            Method method = Text.class.getMethod("method_43470", String.class);
            return (MutableText) method.invoke(null, value);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.class_2585");
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
        for (String methodName : new String[]{"method_44292", "method_2996", "copyWithSettingsFrom", "copyFrom"}) {
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
            Method method = MinecraftClient.class.getMethod("method_44713");
            Object narrator = method.invoke(client);
            narrator.getClass().getMethod("method_37015", Text.class).invoke(narrator, text);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Field narratorField = MinecraftClient.class.getDeclaredField("field_39769");
            narratorField.setAccessible(true);
            Object narrator = narratorField.get(client);
            narrator.getClass().getMethod("method_37015", Text.class).invoke(narrator, text);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.class_333");
            Field instanceField = clazz.getField("field_2054");
            Object narrator = instanceField.get(null);
            clazz.getMethod("method_37015", Text.class).invoke(narrator, text);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not narrate message", exception);
        }
    }

    private static Text screenText(String fieldName, String fallbackKey) {
        String runtimeFieldName = switch (fieldName) {
            case "DONE" -> "field_24334";
            case "BACK" -> "field_24339";
            default -> null;
        };
        if (runtimeFieldName != null) {
            try {
                Class<?> clazz = Class.forName("net.minecraft.class_5244");
                Field field = clazz.getField(runtimeFieldName);
                return (Text) field.get(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return literal(fallbackKey);
    }
}
