package org.developerkubilay.safra.client;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class ForgeClientCompat {
    private ForgeClientCompat() {
    }

    public static Component translatable(String key, Object... args) {
        try {
            Method method = Component.class.getMethod("translatable", String.class, Object[].class);
            return (Component) method.invoke(null, key, args);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.network.chat.TranslatableComponent");
            Constructor<?> constructor = clazz.getConstructor(String.class, Object[].class);
            return (Component) constructor.newInstance(key, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create translatable component", exception);
        }
    }

    public static Component literal(String value) {
        try {
            Method method = Component.class.getMethod("literal", String.class);
            return (Component) method.invoke(null, value);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.network.chat.TextComponent");
            Constructor<?> constructor = clazz.getConstructor(String.class);
            return (Component) constructor.newInstance(value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create literal component", exception);
        }
    }
}
