package org.developerkubilay.safra.client;

import java.lang.reflect.Method;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

public final class FabricScreenCompat {
    private static volatile Class<?> cachedClientClass;
    private static volatile Method cachedOpenMethod;

    private FabricScreenCompat() {
    }

    public static void open(MinecraftClient client, Screen screen) {
        if (client == null) {
            return;
        }
        try {
            resolveOpenMethod(client.getClass()).invoke(client, screen);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not open MinecraftClient screen", exception);
        }
    }

    private static Method resolveOpenMethod(Class<?> clientClass) {
        Method cachedMethod = cachedOpenMethod;
        if (cachedMethod != null && cachedClientClass == clientClass) {
            return cachedMethod;
        }

        Method resolved = findNamedMethod(clientClass, "setScreen");
        if (resolved == null) {
            resolved = findNamedMethod(clientClass, "openScreen");
        }
        if (resolved == null) {
            resolved = findNamedMethod(clientClass, "method_1507");
        }
        if (resolved == null) {
            resolved = findNamedMethod(clientClass, "method_29283");
        }
        if (resolved == null) {
            resolved = findCompatibleMethod(clientClass.getMethods());
        }
        if (resolved == null) {
            resolved = findCompatibleMethod(clientClass.getDeclaredMethods());
        }
        if (resolved == null) {
            throw new IllegalStateException("No compatible MinecraftClient screen method found");
        }

        resolved.setAccessible(true);
        cachedClientClass = clientClass;
        cachedOpenMethod = resolved;
        return resolved;
    }

    private static Method findNamedMethod(Class<?> clientClass, String name) {
        try {
            return clientClass.getMethod(name, Screen.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findCompatibleMethod(Method[] methods) {
        for (Method method : methods) {
            if (method.getReturnType() != Void.TYPE || method.getParameterCount() != 1) {
                continue;
            }
            if (!Screen.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            return method;
        }
        return null;
    }
}
