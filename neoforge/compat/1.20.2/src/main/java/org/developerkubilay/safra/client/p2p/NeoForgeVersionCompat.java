package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class NeoForgeVersionCompat {
    private NeoForgeVersionCompat() {
    }

    public static ServerData copyServerData(ServerData originalServerInfo, String address) {
        try {
            ServerData copy = instantiateServerData(originalServerInfo, address);
            copy.copyFrom(originalServerInfo);
            copy.ip = address;
            return copy;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to create compatible ServerData copy", exception);
        }
    }

    public static void startConnect(Screen parent, Minecraft client, ServerAddress serverAddress,
                                    ServerData serverInfo, boolean quickPlay) {
        try {
            for (Method method : ConnectScreen.class.getDeclaredMethods()) {
                if (!method.getName().equals("startConnecting")) {
                    continue;
                }

                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 5) {
                    method.invoke(null, parent, client, serverAddress, serverInfo, quickPlay);
                    return;
                }
                if (parameterTypes.length == 6) {
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

    private static ServerData instantiateServerData(ServerData originalServerInfo, String address)
        throws ReflectiveOperationException {
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
            return (ServerData) constructor.newInstance(originalServerInfo.name, address, compatibilityValue);
        }

        throw new NoSuchMethodException("Could not find a compatible ServerData constructor");
    }

    private static Object resolveThirdServerDataArgument(ServerData originalServerInfo, Class<?> thirdParameterType)
        throws ReflectiveOperationException {
        if (thirdParameterType == boolean.class || thirdParameterType == Boolean.class) {
            Method isLanMethod = ServerData.class.getMethod("isLan");
            return isLanMethod.invoke(originalServerInfo);
        }

        Method typeMethod = ServerData.class.getMethod("type");
        Object serverType = typeMethod.invoke(originalServerInfo);
        if (thirdParameterType.isInstance(serverType)) {
            return serverType;
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
}
