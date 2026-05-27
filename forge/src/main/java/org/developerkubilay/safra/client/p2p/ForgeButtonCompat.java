package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class ForgeButtonCompat {
    private ForgeButtonCompat() {
    }

    public static Button create(Component message, Button.OnPress onPress, int x, int y, int width, int height) {
        Object builder = createBuilder(message, onPress);
        if (builder != null) {
            configureBuilder(builder, x, y, width, height);
            Button built = build(builder);
            if (built != null) {
                return built;
            }
        }

        Button constructed = constructButton(message, onPress, x, y, width, height);
        if (constructed != null) {
            return constructed;
        }

        throw new IllegalStateException("Could not resolve a compatible Button builder");
    }

    private static Object createBuilder(Component message, Button.OnPress onPress) {
        for (Method method : Button.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes[0] != Component.class || !parameterTypes[1].isInstance(onPress)) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(null, message, onPress);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static void configureBuilder(Object builder, int x, int y, int width, int height) {
        for (Method method : builder.getClass().getMethods()) {
            if (method.getParameterCount() != 4 || !method.getReturnType().isInstance(builder)) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes[0] != int.class || parameterTypes[1] != int.class || parameterTypes[2] != int.class || parameterTypes[3] != int.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(builder, x, y, width, height);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static Button build(Object builder) {
        for (Method method : builder.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !Button.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                return (Button) method.invoke(builder);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Button constructButton(Component message, Button.OnPress onPress, int x, int y, int width, int height) {
        for (Constructor<?> constructor : Button.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length < 6
                || parameterTypes[0] != int.class
                || parameterTypes[1] != int.class
                || parameterTypes[2] != int.class
                || parameterTypes[3] != int.class
                || parameterTypes[4] != Component.class
                || !parameterTypes[5].isInstance(onPress)) {
                continue;
            }
            Object[] args = new Object[parameterTypes.length];
            args[0] = x;
            args[1] = y;
            args[2] = width;
            args[3] = height;
            args[4] = message;
            args[5] = onPress;
            for (int i = 6; i < parameterTypes.length; i++) {
                args[i] = resolveDefaultValue(parameterTypes[i]);
            }
            try {
                constructor.setAccessible(true);
                return (Button) constructor.newInstance(args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Object resolveDefaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            Object constant = findStaticConstant(type);
            if (constant != null) {
                return constant;
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static Object findStaticConstant(Class<?> type) {
        for (Field field : Button.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
