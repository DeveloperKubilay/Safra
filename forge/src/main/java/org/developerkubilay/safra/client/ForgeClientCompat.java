package org.developerkubilay.safra.client;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;

public final class ForgeClientCompat {
    private ForgeClientCompat() {
    }

    public static Component translatable(String key, Object... args) {
        try {
            Method factoryMethod = findStaticFactoryMethod(String.class, Object[].class);
            if (factoryMethod != null) {
                return (Component) factoryMethod.invoke(null, key, args);
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> legacyClass = Class.forName("net.minecraft.network.chat.TranslatableComponent");
            Constructor<?> constructor = legacyClass.getConstructor(String.class, Object[].class);
            return (Component) constructor.newInstance(key, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create compatible translatable component", exception);
        }
    }

    public static Component literal(String value) {
        try {
            Method factoryMethod = findStaticFactoryMethod(String.class);
            if (factoryMethod != null) {
                return (Component) factoryMethod.invoke(null, value);
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> legacyClass = Class.forName("net.minecraft.network.chat.TextComponent");
            Constructor<?> constructor = legacyClass.getConstructor(String.class);
            return (Component) constructor.newInstance(value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create compatible literal component", exception);
        }
    }

    private static Method findStaticFactoryMethod(Class<?>... parameterTypes) {
        for (Method method : Component.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                || !Component.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (matchesParameters(method.getParameterTypes(), parameterTypes)) {
                return method;
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

    public static net.minecraft.client.gui.components.Button createButton(int x, int y, int width, int height, Component text, net.minecraft.client.gui.components.Button.OnPress action) {
        try {
            Method builderMethod = null;
            for (Method method : net.minecraft.client.gui.components.Button.class.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 2) {
                    if (method.getParameterTypes()[0] == Component.class && method.getParameterTypes()[1] == net.minecraft.client.gui.components.Button.OnPress.class) {
                        builderMethod = method;
                        break;
                    }
                }
            }
            if (builderMethod != null) {
                Object builder = builderMethod.invoke(null, text, action);
                Method boundsMethod = null;
                for (Method method : builder.getClass().getMethods()) {
                    if (method.getParameterCount() == 4 && method.getParameterTypes()[0] == int.class) {
                        boundsMethod = method;
                        break;
                    }
                }
                if (boundsMethod != null) {
                    builder = boundsMethod.invoke(builder, x, y, width, height);
                }
                Method buildMethod = null;
                for (Method method : builder.getClass().getMethods()) {
                    if (method.getParameterCount() == 0 && net.minecraft.client.gui.components.Button.class.isAssignableFrom(method.getReturnType())) {
                        buildMethod = method;
                        break;
                    }
                }
                if (buildMethod != null) {
                    return (net.minecraft.client.gui.components.Button) buildMethod.invoke(builder);
                }
            }
        } catch (Exception ignored) {
        }
        
        try {
            Constructor<?> constructor = net.minecraft.client.gui.components.Button.class.getConstructor(int.class, int.class, int.class, int.class, Component.class, net.minecraft.client.gui.components.Button.OnPress.class);
            return (net.minecraft.client.gui.components.Button) constructor.newInstance(x, y, width, height, text, action);
        } catch (Exception e) {
            throw new RuntimeException("Could not create button", e);
        }
    }

    public static void setX(net.minecraft.client.gui.components.AbstractWidget widget, int x) {
        try {
            Method setXMethod = net.minecraft.client.gui.components.AbstractWidget.class.getMethod("setX", int.class);
            setXMethod.invoke(widget, x);
            return;
        } catch (Exception ignored) {}

        try {
            java.lang.reflect.Field xField = net.minecraft.client.gui.components.AbstractWidget.class.getField("x");
            xField.set(widget, x);
        } catch (Exception e) {}
    }

    public static void setY(net.minecraft.client.gui.components.AbstractWidget widget, int y) {
        try {
            Method setYMethod = net.minecraft.client.gui.components.AbstractWidget.class.getMethod("setY", int.class);
            setYMethod.invoke(widget, y);
            return;
        } catch (Exception ignored) {}

        try {
            java.lang.reflect.Field yField = net.minecraft.client.gui.components.AbstractWidget.class.getField("y");
            yField.set(widget, y);
        } catch (Exception e) {}
    }

    public static void drawCenteredString(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.gui.Font font, Component component, int x, int y, int color) {
        try {
            Class<?> guiComponentClass;
            try {
                guiComponentClass = Class.forName("net.minecraft.client.gui.GuiComponent");
            } catch (ClassNotFoundException e) {
                guiComponentClass = Class.forName("net.minecraft.client.gui.GuiGraphics");
            }
            
            for (Method method : guiComponentClass.getMethods()) {
                if (method.getName().equals("drawCenteredString")) {
                    if (method.getParameterCount() == 6 && method.getParameterTypes()[2] == Component.class) {
                        method.invoke(null, poseStack, font, component, x, y, color);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
