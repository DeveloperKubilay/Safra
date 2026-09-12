package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

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
}
