package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class ForgeGuiCompat {
    private ForgeGuiCompat() {
    }

    public static void fill(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
        if (invokeVoidMethod(
            guiGraphics,
            new Class<?>[]{int.class, int.class, int.class, int.class, int.class},
            new Object[]{left, top, right, bottom, color},
            "fill", "m_280509_"
        )) {
            return;
        }

        throw new IllegalStateException("Could not resolve GuiGraphics#fill");
    }

    public static void drawCenteredString(GuiGraphics guiGraphics, Font font, Component text, int x, int y, int color) {
        if (invokeMethod(
            guiGraphics,
            new Class<?>[]{Font.class, Component.class, int.class, int.class, int.class},
            new Object[]{font, text, x, y, color},
            "drawCenteredString", "m_280653_"
        ) != null) {
            return;
        }

        throw new IllegalStateException("Could not resolve GuiGraphics#drawCenteredString");
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

        for (Method method : target.getClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != parameterTypes.length
                || method.getReturnType() != void.class) {
                continue;
            }

            Class<?>[] resolvedParameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (resolvedParameterTypes[i] != parameterTypes[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(target, args);
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return false;
    }

    private static Object invokeMethod(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Method method = type.getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }

        for (Method method : target.getClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != parameterTypes.length) {
                continue;
            }

            Class<?>[] resolvedParameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (resolvedParameterTypes[i] != parameterTypes[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }

            try {
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return null;
    }
}
