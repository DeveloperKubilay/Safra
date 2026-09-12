package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.CookieStorage;
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
        startConnect(parent, client, serverAddress, serverInfo, quickPlay, null);
    }

    public static void startConnect(Screen parent, MinecraftClient client, ServerAddress serverAddress,
                                    ServerInfo serverInfo, boolean quickPlay, CookieStorage cookieStorage) {
        try {
            Method connectMethod = findStaticMethod(
                ConnectScreen.class,
                Screen.class,
                MinecraftClient.class,
                ServerAddress.class,
                ServerInfo.class,
                boolean.class,
                CookieStorage.class
            );
            if (connectMethod != null) {
                connectMethod.invoke(null, parent, client, serverAddress, serverInfo, quickPlay, cookieStorage);
                return;
            }

            Method legacyConnectMethod = findStaticMethod(
                ConnectScreen.class,
                MinecraftClient.class,
                ServerAddress.class,
                ServerInfo.class,
                CookieStorage.class
            );
            if (legacyConnectMethod != null) {
                legacyConnectMethod.invoke(null, client, serverAddress, serverInfo, cookieStorage);
                return;
            }

            throw new NoSuchMethodException("Could not find a compatible ConnectScreen.connect overload");
        } catch (RuntimeException exception) {
            throw exception;
        } catch (InvocationTargetException exception) {
            throw rethrow("Failed to invoke compatible ConnectScreen.connect", exception);
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
            Method isLocalMethod = findInstanceMethod(ServerInfo.class, boolean.class);
            if (isLocalMethod == null) {
                throw new NoSuchMethodException("Could not find a compatible ServerInfo local flag accessor");
            }
            return isLocalMethod.invoke(originalServerInfo);
        }

        Field matchingField = findInstanceField(ServerInfo.class, thirdParameterType, "serverType", "field_3761");
        if (matchingField != null) {
            Object value = matchingField.get(originalServerInfo);
            if (value != null) {
                return value;
            }
        }

        for (Method method : ServerInfo.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0
                || !thirdParameterType.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Object serverType = method.invoke(originalServerInfo);
            if (thirdParameterType.isInstance(serverType)) {
                return serverType;
            }
        }

        throw new NoSuchMethodException("Unsupported ServerInfo constructor parameter type: " + thirdParameterType.getName());
    }

    private static Field findInstanceField(Class<?> owner, Class<?> fieldType, String... fieldNames) {
        Class<?> type = owner;
        while (type != null) {
            for (String fieldName : fieldNames) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    if (field.getType() != fieldType) {
                        continue;
                    }
                    field.setAccessible(true);
                    return field;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Method findStaticMethod(Class<?> owner, Class<?>... parameterTypes) {
        for (Method method : owner.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                || !matchesParameters(method.getParameterTypes(), parameterTypes)) {
                continue;
            }
            return method;
        }
        return null;
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
