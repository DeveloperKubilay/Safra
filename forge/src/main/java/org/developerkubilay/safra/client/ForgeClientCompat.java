package org.developerkubilay.safra.client;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;

public final class ForgeClientCompat {
    private ForgeClientCompat() {
    }

    public static Component translatable(String key, Object... args) {
        try {
            Method factoryMethod = findStaticFactoryMethod(String.class, Object[].class);
            if (factoryMethod != null) {
                return (Component) factoryMethod.invoke(null, key, args);
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> legacyClass = Class.forName("net.minecraft.network.chat.TranslatableComponent");
            Constructor<?> constructor = legacyClass.getConstructor(String.class, Object[].class);
            return (Component) constructor.newInstance(key, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create compatible translatable component", exception);
        }
    }

    public static Component literal(String value) {
        try {
            Method factoryMethod = findStaticFactoryMethod(String.class);
            if (factoryMethod != null) {
                return (Component) factoryMethod.invoke(null, value);
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> legacyClass = Class.forName("net.minecraft.network.chat.TextComponent");
            Constructor<?> constructor = legacyClass.getConstructor(String.class);
            return (Component) constructor.newInstance(value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create compatible literal component", exception);
        }
    }

    private static Method findStaticFactoryMethod(Class<?>... parameterTypes) {
        for (Method method : Component.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                || !Component.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (matchesParameters(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        return null;
    }

    private static boolean matchesParameters(Class<?>[] actualTypes, Class<?>[] expectedTypes) {
        if (actualTypes.length != expectedTypes.length) {
            return false;
        }

        for (int index = 0; index < actualTypes.length; index++) {
            if (actualTypes[index] != expectedTypes[index]) {
                return false;
            }
        }

        return true;
    }
}
