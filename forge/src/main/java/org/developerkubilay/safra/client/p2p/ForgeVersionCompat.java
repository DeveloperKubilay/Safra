package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.multiplayer.ServerData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class ForgeVersionCompat {
    private ForgeVersionCompat() {
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
}
