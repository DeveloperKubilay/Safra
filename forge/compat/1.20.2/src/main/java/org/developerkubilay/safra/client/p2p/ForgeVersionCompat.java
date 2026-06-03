package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import com.mojang.blaze3d.platform.Window;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class ForgeVersionCompat {
    private ForgeVersionCompat() {
    }

    public static ServerData copyServerData(ServerData originalServerInfo, String address) {
        try {
            ServerData copy = instantiateServerData(originalServerInfo, address);
            copyServerSettings(copy, originalServerInfo);
            setServerAddress(copy, address);
            return copy;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create compatible ServerData copy", exception);
        }
    }

    public static String getServerAddress(ServerData serverData) {
        Object value = getFieldValue(serverData, "ip", "f_105363_");
        if (value instanceof String address) {
            return address;
        }
        throw new IllegalStateException("Failed to resolve compatible ServerData address field");
    }

    public static void setServerAddress(ServerData serverData, String address) {
        if (!setFieldValue(serverData, address, "ip", "f_105363_")) {
            throw new IllegalStateException("Failed to resolve compatible ServerData address field");
        }
    }

    public static void startConnect(Screen parent, Minecraft client, ServerAddress serverAddress,
                                    ServerData serverInfo, boolean quickPlay) {
        try {
            for (Method method : ConnectScreen.class.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 5 && parameterTypes.length != 6) {
                    continue;
                }
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || parameterTypes[0] != Screen.class
                    || parameterTypes[1] != Minecraft.class
                    || parameterTypes[2] != ServerAddress.class
                    || parameterTypes[3] != ServerData.class
                    || parameterTypes[4] != boolean.class) {
                    continue;
                }

                method.setAccessible(true);
                if (parameterTypes.length == 5) {
                    method.invoke(null, parent, client, serverAddress, serverInfo, quickPlay);
                    return;
                }
                if (!parameterTypes[5].isPrimitive()) {
                    method.invoke(null, parent, client, serverAddress, serverInfo, quickPlay, null);
                    return;
                }
            }
        } catch (InvocationTargetException exception) {
            throw rethrow("Failed to invoke compatible ConnectScreen.startConnecting", exception);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to resolve compatible ConnectScreen.startConnecting", exception);
        }

        throw new IllegalStateException("Could not find a compatible ConnectScreen.startConnecting method");
    }

    public static ServerAddress parseServerAddress(String address) {
        for (Method method : ServerAddress.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != ServerAddress.class
                || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != String.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                Object result = method.invoke(null, address);
                if (result instanceof ServerAddress serverAddress) {
                    return serverAddress;
                }
            } catch (InvocationTargetException exception) {
                throw rethrow("Failed to invoke compatible ServerAddress parser", exception);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        throw new IllegalStateException("Could not find a compatible ServerAddress parser");
    }

    public static void setScreen(Minecraft client, Screen screen) {
        if (invokeVoidMethod(client, new Class<?>[]{Screen.class}, new Object[]{screen}, "setScreen", "method_1507", "m_91152_")) {
            return;
        }

        if (invokeVoidMethod(client, new Class<?>[]{Screen.class}, new Object[]{screen}, "setScreenAndRender", "method_29970", "m_91346_")) {
            return;
        }

        throw new IllegalStateException("Could not find a compatible Minecraft.setScreen method");
    }

    public static void initScreen(Screen screen, Minecraft client, int width, int height) {
        if (width <= 0 || height <= 0) {
            Window window = getWindow(client);
            width = getGuiScaledWidth(window);
            height = getGuiScaledHeight(window);
        }

        if (invokeVoidMethod(
            screen,
            new Class<?>[]{Minecraft.class, int.class, int.class},
            new Object[]{client, width, height},
            "init", "method_25423", "m_6575_"
        )) {
            return;
        }

        for (Method method : screen.getClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != void.class
                || method.getParameterCount() != 3
                || method.getParameterTypes()[0] != Minecraft.class
                || method.getParameterTypes()[1] != int.class
                || method.getParameterTypes()[2] != int.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(screen, client, width, height);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        throw new IllegalStateException("Could not find a compatible Screen.init method");
    }

    private static Window getWindow(Minecraft client) {
        Object window = invokeFirstCompatibleAccessor(client, Window.class, "getWindow", "m_91268_");
        if (window instanceof Window resolved) {
            return resolved;
        }

        Object fieldValue = getFieldValue(client, "window", "f_90990_");
        if (fieldValue instanceof Window resolved) {
            return resolved;
        }

        throw new IllegalStateException("Could not find a compatible Minecraft window accessor");
    }

    private static int getGuiScaledWidth(Window window) {
        Object value = invokeFirstCompatibleAccessor(window, int.class, "getGuiScaledWidth", "m_85441_");
        if (value instanceof Integer resolved) {
            return resolved;
        }
        throw new IllegalStateException("Could not find a compatible Window#getGuiScaledWidth method");
    }

    private static int getGuiScaledHeight(Window window) {
        Object value = invokeFirstCompatibleAccessor(window, int.class, "getGuiScaledHeight", "m_85442_");
        if (value instanceof Integer resolved) {
            return resolved;
        }
        throw new IllegalStateException("Could not find a compatible Window#getGuiScaledHeight method");
    }

    private static ServerData instantiateServerData(ServerData originalServerInfo, String address)
        throws ReflectiveOperationException {
        String serverName = getServerName(originalServerInfo);
        for (Constructor<?> constructor : ServerData.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length != 3
                || parameterTypes[0] != String.class
                || parameterTypes[1] != String.class) {
                continue;
            }

            Object compatibilityValue = resolveThirdServerDataArgument(originalServerInfo, parameterTypes[2]);
            if (compatibilityValue == null && parameterTypes[2].isPrimitive()) {
                continue;
            }
            return (ServerData) constructor.newInstance(serverName, address, compatibilityValue);
        }

        throw new NoSuchMethodException("Could not find a compatible ServerData constructor");
    }

    private static String getServerName(ServerData serverData) {
        Object value = getFieldValue(serverData, "name", "f_105362_");
        if (value instanceof String name) {
            return name;
        }
        throw new IllegalStateException("Failed to resolve compatible ServerData name field");
    }

    private static void copyServerSettings(ServerData target, ServerData source) throws ReflectiveOperationException {
        if (invokeVoidMethod(target, new Class<?>[]{ServerData.class}, new Object[]{source}, "copyFrom", "copyWithSettingsFrom")) {
            return;
        }

        copyFieldValue(source, target, "resourcePackStatus", "f_105367_");
        copyFieldValue(source, target, "iconB64", "f_105368_");
        copyFieldValue(source, target, "lan", "f_105364_");
        copyFieldValue(source, target, "status", "f_105365_");
        copyFieldValue(source, target, "motd", "f_105366_");
        copyFieldValue(source, target, "ping", "f_105369_");
        copyFieldValue(source, target, "protocol", "f_105370_");
        copyFieldValue(source, target, "version", "f_105371_");
        copyFieldValue(source, target, "playerList", "f_244289_");
        copyFieldValue(source, target, "packStatus", "f_105367_");
        copyFieldValue(source, target, "type", "serverType");
        copyFieldValue(source, target, "enforcesSecureChat", "f_243993_");
    }

    private static Object resolveThirdServerDataArgument(ServerData originalServerInfo, Class<?> thirdParameterType)
        throws ReflectiveOperationException {
        if (thirdParameterType == boolean.class || thirdParameterType == Boolean.class) {
            Object value = invokeFirstCompatibleAccessor(originalServerInfo, boolean.class, "isLan", "isLocal");
            if (value != null) {
                return value;
            }
        }

        Object accessorValue = invokeFirstCompatibleAccessor(originalServerInfo, thirdParameterType, "type", "getType");
        if (thirdParameterType.isInstance(accessorValue)) {
            return accessorValue;
        }

        Object fieldValue = findFirstAssignableFieldValue(originalServerInfo, thirdParameterType, "name", "ip", "f_105362_", "f_105363_");
        if (thirdParameterType.isInstance(fieldValue)) {
            return fieldValue;
        }

        throw new NoSuchMethodException("Unsupported ServerData constructor parameter type: " + thirdParameterType.getName());
    }

    private static RuntimeException rethrow(String message, InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(message, cause == null ? exception : cause);
    }

    private static boolean invokeVoidMethod(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Method method = type.getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    method.invoke(target, args);
                    return true;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static Object invokeFirstCompatibleAccessor(Object target, Class<?> expectedType, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    if (method.getParameterCount() != 0 || !expectedType.isAssignableFrom(method.getReturnType())) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object findFirstAssignableFieldValue(Object target, Class<?> expectedType, String... excludedNames) {
        Set<String> excluded = new HashSet<>(Arrays.asList(excludedNames));
        Class<?> type = target.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                    || excluded.contains(field.getName())
                    || !expectedType.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) {
                        return value;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object getFieldValue(Object target, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static void copyFieldValue(Object source, Object target, String... names) {
        Object value = getFieldValue(source, names);
        if (value != null) {
            setFieldValue(target, value, names);
        }
    }

    private static boolean setFieldValue(Object target, Object value, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return true;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
