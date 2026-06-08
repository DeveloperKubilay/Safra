package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class ForgeScreenCompat {
    private ForgeScreenCompat() {
    }

    public static Button addRenderableWidget(Screen screen, Button button) {
        for (Class<?> parameterType : new Class<?>[]{button.getClass(), Button.class, GuiEventListener.class}) {
            Button added = invoke(screen, button, parameterType);
            if (added != null) {
                return added;
            }
        }

        Class<?> type = button.getClass();
        while (type != null) {
            Button added = invoke(screen, button, type);
            if (added != null) {
                return added;
            }
            type = type.getSuperclass();
        }

        throw new IllegalStateException("Could not resolve Screen#addRenderableWidget for " + button.getClass().getName());
    }

    public static void clearWidgets(Screen screen) {
        if (invokeVoid(screen, new Class<?>[0], new Object[0], "clearWidgets", "m_169413_")) {
            return;
        }

        clearListField(screen, "children", "f_96540_");
        clearListField(screen, "narratables", "f_169368_");
        clearListField(screen, "renderables", "f_169369_");
    }

    public static void clearFocus(Screen screen) {
        if (invokeVoid(screen, new Class<?>[0], new Object[0], "clearFocus", "m_264131_")) {
            return;
        }

        invokeVoid(screen, new Class<?>[]{GuiEventListener.class}, new Object[]{null}, "setFocused", "m_7522_");
    }

    public static void renderWidgets(Screen screen, GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        Object renderables = getFieldValue(screen, "renderables", "f_169369_");
        if (!(renderables instanceof Iterable<?> iterable)) {
            return;
        }

        List<Object> snapshot = new ArrayList<>();
        for (Object renderable : iterable) {
            if (renderable != null) {
                snapshot.add(renderable);
            }
        }

        for (Object renderable : snapshot) {
            if (invoke(renderable, new Class<?>[]{GuiGraphics.class, int.class, int.class, float.class}, new Object[]{guiGraphics, mouseX, mouseY, partialTick}, "render", "m_88315_")) {
                continue;
            }

            if (renderable instanceof Renderable
                && invoke(renderable, new Class<?>[]{GuiGraphics.class, int.class, int.class, float.class}, new Object[]{guiGraphics, mouseX, mouseY, partialTick}, "renderWidget", "m_87963_")) {
                continue;
            }
        }
    }

    private static Button invoke(Screen screen, Button button, Class<?> parameterType) {
        Class<?> type = screen.getClass();
        while (type != null) {
            for (String name : new String[]{"addRenderableWidget", "m_142416_"}) {
                try {
                    Method method = type.getDeclaredMethod(name, parameterType);
                    method.setAccessible(true);
                    Object result = method.invoke(screen, button);
                    return result instanceof Button added ? added : button;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean invokeVoid(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        return invoke(target, parameterTypes, args, names);
    }

    private static boolean invoke(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
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
                method.invoke(target, args);
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return false;
    }

    private static void clearListField(Screen screen, String... names) {
        Object value = getFieldValue(screen, names);
        if (value instanceof List<?> list) {
            list.clear();
        }
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
}
