package org.developerkubilay.safra.client;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class ForgeClientCompat {
    private ForgeClientCompat() {
    }

    public static Component translatable(String key, Object... args) {
        try {
            Method factoryMethod = Component.class.getMethod("translatable", String.class, Object[].class);
            return (Component) factoryMethod.invoke(null, key, args);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> legacyClass = Class.forName("net.minecraft.network.chat.TranslatableComponent");
            Constructor<?> constructor = legacyClass.getConstructor(String.class, Object[].class);
            return (Component) constructor.newInstance(key, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Compatible translatable component olusturulamadi", exception);
        }
    }

    public static Component literal(String value) {
        try {
            Method factoryMethod = Component.class.getMethod("literal", String.class);
            return (Component) factoryMethod.invoke(null, value);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> legacyClass = Class.forName("net.minecraft.network.chat.TextComponent");
            Constructor<?> constructor = legacyClass.getConstructor(String.class);
            return (Component) constructor.newInstance(value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Compatible literal component olusturulamadi", exception);
        }
    }
}
