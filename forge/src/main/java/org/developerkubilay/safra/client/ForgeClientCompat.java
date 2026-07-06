package org.developerkubilay.safra.client;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
            Constructor<?> constructor = net.minecraft.client.gui.components.Button.class.getConstructor(int.class, int.class, int.class, int.class, Component.class, net.minecraft.client.gui.components.Button.OnPress.class);
            return (net.minecraft.client.gui.components.Button) constructor.newInstance(x, y, width, height, text, action);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Method builderMethod = findBuilderMethod();
            if (builderMethod == null) {
                throw new NoSuchMethodException("No compatible Button builder method found");
            }

            Object builder = builderMethod.invoke(null, text, action);
            builder = applyBuilderBounds(builder, x, y, width, height);
            return (net.minecraft.client.gui.components.Button) invokeBuildMethod(builder);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create compatible button", exception);
        }
    }

    public static void setX(net.minecraft.client.gui.components.AbstractWidget widget, int x) {
        try {
            invokeCompatibleMethod(widget, new String[]{"setX"}, x);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        setIntField(widget, new String[]{"x"}, x);
    }

    public static void setY(net.minecraft.client.gui.components.AbstractWidget widget, int y) {
        try {
            invokeCompatibleMethod(widget, new String[]{"setY"}, y);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        setIntField(widget, new String[]{"y"}, y);
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

    private static Method findBuilderMethod() {
        for (String methodName : new String[]{"builder"}) {
            try {
                return net.minecraft.client.gui.components.Button.class.getMethod(methodName, Component.class, net.minecraft.client.gui.components.Button.OnPress.class);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (Method method : net.minecraft.client.gui.components.Button.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 2
                || method.getParameterTypes()[0] != Component.class
                || method.getParameterTypes()[1] != net.minecraft.client.gui.components.Button.OnPress.class) {
                continue;
            }
            return method;
        }
        return null;
    }

    private static Object invokeBuildMethod(Object builder) throws ReflectiveOperationException {
        for (Method method : builder.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                && net.minecraft.client.gui.components.Button.class.isAssignableFrom(method.getReturnType())) {
                return method.invoke(builder);
            }
        }
        throw new NoSuchMethodException("No compatible builder build method found");
    }

    private static Object applyBuilderBounds(Object builder, int x, int y, int width, int height) throws ReflectiveOperationException {
        try {
            return invokeCompatibleMethod(builder, new String[]{"bounds", "pos", "dimensions", "method_46434"}, x, y, width, height);
        } catch (NoSuchMethodException ignored) {
        }

        for (Method method : builder.getClass().getMethods()) {
            if (method.getParameterCount() != 4) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes[0] != int.class
                || parameterTypes[1] != int.class
                || parameterTypes[2] != int.class
                || parameterTypes[3] != int.class) {
                continue;
            }
            if (!method.getReturnType().isAssignableFrom(builder.getClass())
                && !builder.getClass().isAssignableFrom(method.getReturnType())) {
                continue;
            }
            return method.invoke(builder, x, y, width, height);
        }

        throw new NoSuchMethodException("No compatible bounds method found on " + builder.getClass().getName());
    }

    private static Object invokeCompatibleMethod(Object target, String[] candidateNames, Object... args) throws ReflectiveOperationException {
        Class<?>[] parameterTypes = extractParameterTypes(args);
        Class<?> currentClass = target.getClass();

        while (currentClass != null) {
            for (String methodName : candidateNames) {
                try {
                    Method method = currentClass.getDeclaredMethod(methodName, parameterTypes);
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (NoSuchMethodException ignored) {
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        throw new NoSuchMethodException("No compatible method found on " + target.getClass().getName());
    }

    private static Class<?>[] extractParameterTypes(Object[] args) {
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            Object arg = args[index];
            if (arg instanceof Integer) {
                parameterTypes[index] = int.class;
            } else {
                parameterTypes[index] = arg.getClass();
            }
        }
        return parameterTypes;
    }

    private static void setIntField(Object target, String[] candidateNames, int value) {
        Class<?> currentClass = target.getClass();

        while (currentClass != null) {
            for (String fieldName : candidateNames) {
                try {
                    Field field = currentClass.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.setInt(target, value);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            currentClass = currentClass.getSuperclass();
        }
    }
}
