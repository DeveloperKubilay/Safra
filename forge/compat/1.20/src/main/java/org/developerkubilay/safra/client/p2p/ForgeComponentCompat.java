package org.developerkubilay.safra.client.p2p;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class ForgeComponentCompat {
    private ForgeComponentCompat() {
    }

    public static Component translatable(String key, Object... args) {
        Object value = call(
            new String[]{"translatable", "m_237115_"},
            new Class<?>[]{String.class, Object[].class},
            new Object[]{key, args}
        );
        if (value instanceof Component component) {
            return component;
        }

        value = callBySignature(new Object[]{key, args}, String.class, Object[].class);
        if (value instanceof Component component) {
            return component;
        }

        value = call(
            new String[]{"translatable", "m_237110_"},
            new Class<?>[]{String.class},
            new Object[]{key}
        );
        if (value instanceof Component component) {
            return component;
        }

        value = callBySignature(new Object[]{key}, String.class);
        if (value instanceof Component component) {
            return component;
        }

        return literal(key);
    }

    public static Component empty() {
        Object value = call(new String[]{"empty", "m_237113_"}, new Class<?>[0], new Object[0]);
        if (value instanceof Component component) {
            return component;
        }
        value = callBySignature(new Object[0]);
        if (value instanceof Component component) {
            return component;
        }
        return literal("");
    }

    public static Component literal(String text) {
        Object value = call(
            new String[]{"literal", "m_237113_"},
            new Class<?>[]{String.class},
            new Object[]{text}
        );
        if (value instanceof Component component) {
            return component;
        }
        value = callBySignature(new Object[]{text}, String.class);
        if (value instanceof Component component) {
            return component;
        }
        throw new IllegalStateException("Could not resolve a compatible Component literal factory");
    }

    private static Object call(String[] names, Class<?>[] parameterTypes, Object[] args) {
        for (String name : names) {
            try {
                Method method = Component.class.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(null, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Object callBySignature(Object[] args, Class<?>... parameterTypes) {
        for (Method method : Component.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] methodParameterTypes = method.getParameterTypes();
            if (methodParameterTypes.length != parameterTypes.length || !Component.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (methodParameterTypes[i] != parameterTypes[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(null, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
