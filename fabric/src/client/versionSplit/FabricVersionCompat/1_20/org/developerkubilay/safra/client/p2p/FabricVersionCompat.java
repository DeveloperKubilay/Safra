package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class FabricVersionCompat {
    private FabricVersionCompat() {
    }

    public static ServerInfo copyServerInfo(ServerInfo originalServerInfo, String address) {
        try {
            ServerInfo copy = instantiateServerInfo(originalServerInfo, address);
            copy.copyWithSettingsFrom(originalServerInfo);
            copy.address = address;
            return copy;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create compatible ServerInfo copy", exception);
        }
    }

    public static void startConnect(Screen parent, MinecraftClient client, ServerAddress serverAddress,
                                    ServerInfo serverInfo, boolean quickPlay) {
        try {
            ConnectScreen.connect(parent, client, serverAddress, serverInfo, quickPlay);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to invoke compatible ConnectScreen.connect", throwable);
        }
    }

    public static void setWidgetBounds(Object widget, int width, int height, int x, int y) {
        try {
            Method setBoundsMethod = findInstanceMethod(
                widget.getClass(),
                int.class,
                int.class,
                int.class,
                int.class
            );
            if (setBoundsMethod != null) {
                setBoundsMethod.invoke(widget, width, height, x, y);
                return;
            }

            if (widget instanceof ClickableWidget clickableWidget) {
                clickableWidget.setWidth(width);
                clickableWidget.setX(x);
                clickableWidget.setY(y);
                setWidgetHeight(clickableWidget, height);
                return;
            }

            throw new NoSuchMethodException("Could not find a compatible widget bounds setter overload");
        } catch (InvocationTargetException exception) {
            throw rethrow("Failed to invoke compatible widget bounds setter", exception);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to resolve compatible widget bounds setter", exception);
        }
    }

    private static void setWidgetHeight(ClickableWidget widget, int height) throws ReflectiveOperationException {
        Class<?> type = widget.getClass();
        while (type != null) {
            for (String fieldName : new String[]{"height", "field_22758"}) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.setInt(widget, height);
                    return;
                } catch (NoSuchFieldException ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    private static ServerInfo instantiateServerInfo(ServerInfo originalServerInfo, String address)
        throws ReflectiveOperationException {
        for (Constructor<?> constructor : ServerInfo.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length != 3
                || parameterTypes[0] != String.class
                || parameterTypes[1] != String.class) {
                continue;
            }

            Object compatibilityValue = resolveThirdServerInfoArgument(originalServerInfo, parameterTypes[2]);
            if (compatibilityValue == null && parameterTypes[2].isPrimitive()) {
                continue;
            }
            return (ServerInfo) constructor.newInstance(originalServerInfo.name, address, compatibilityValue);
        }

        throw new NoSuchMethodException("Could not find a compatible ServerInfo constructor");
    }

    private static Object resolveThirdServerInfoArgument(ServerInfo originalServerInfo, Class<?> thirdParameterType)
        throws ReflectiveOperationException {
        if (thirdParameterType == boolean.class || thirdParameterType == Boolean.class) {
            Method isLocalMethod = findZeroArgInstanceMethodReturning(ServerInfo.class, boolean.class, Boolean.class);
            if (isLocalMethod == null) {
                throw new NoSuchMethodException("Could not find a compatible ServerInfo local flag accessor");
            }
            return isLocalMethod.invoke(originalServerInfo);
        }

        Field serverTypeField = findInstanceField(ServerInfo.class, thirdParameterType, "serverType", "field_3761");
        Object serverType = readFieldValue(serverTypeField, originalServerInfo, thirdParameterType);
        if (serverType != null) {
            return serverType;
        }

        serverTypeField = findAssignableInstanceField(ServerInfo.class, thirdParameterType);
        serverType = readFieldValue(serverTypeField, originalServerInfo, thirdParameterType);
        if (serverType != null) {
            return serverType;
        }

        for (Method method : ServerInfo.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0
                || !thirdParameterType.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                serverType = method.invoke(originalServerInfo);
                if (thirdParameterType.isInstance(serverType)) {
                    return serverType;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        throw new NoSuchMethodException("Unsupported ServerInfo constructor parameter type: " + thirdParameterType.getName());
    }

    private static Field findInstanceField(Class<?> owner, Class<?> fieldType, String... candidateNames) {
        Class<?> type = owner;
        while (type != null) {
            for (String candidateName : candidateNames) {
                try {
                    Field field = type.getDeclaredField(candidateName);
                    if (Modifier.isStatic(field.getModifiers()) || !fieldType.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Field findAssignableInstanceField(Class<?> owner, Class<?> fieldType) {
        Class<?> type = owner;
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !fieldType.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return field;
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object readFieldValue(Field field, Object instance, Class<?> fieldType) throws IllegalAccessException {
        if (field == null) {
            return null;
        }
        Object value = field.get(instance);
        return fieldType.isInstance(value) ? value : null;
    }

    private static Method findInstanceMethod(Class<?> owner, Class<?>... parameterTypes) {
        for (Method method : owner.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                || !matchesParameters(method.getParameterTypes(), parameterTypes)) {
                continue;
            }
            return method;
        }
        return null;
    }

    private static Method findZeroArgInstanceMethodReturning(Class<?> owner, Class<?>... returnTypes) {
        for (Method method : owner.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            for (Class<?> returnType : returnTypes) {
                if (method.getReturnType() == returnType) {
                    return method;
                }
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

    private static RuntimeException rethrow(String message, InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(message, cause == null ? exception : cause);
    }
}
