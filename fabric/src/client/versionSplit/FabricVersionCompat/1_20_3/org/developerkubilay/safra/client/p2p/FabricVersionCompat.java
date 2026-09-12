package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
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
        try {
            ConnectScreen.connect(parent, client, serverAddress, serverInfo, quickPlay);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to invoke compatible ConnectScreen.connect", throwable);
        }
    }

    public static void setWidgetBounds(Object widget, int width, int height, int x, int y) {
        try {
            Method setBoundsMethod = findCompatibleBoundsMethod(widget.getClass());
            if (setBoundsMethod != null) {
                invokeBoundsMethod(setBoundsMethod, widget, width, height, x, y);
                return;
            }

            if (widget instanceof ClickableWidget clickableWidget) {
                setWidgetDimension(clickableWidget, width, height, x, y);
                return;
            }
        } catch (InvocationTargetException exception) {
            throw rethrow("Failed to invoke compatible widget bounds setter", exception);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to resolve compatible widget bounds setter", exception);
        }
    }

    private static void setWidgetDimension(ClickableWidget widget, int width, int height, int x, int y) throws ReflectiveOperationException {
        for (Method method : widget.getClass().getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                && (method.getName().equals("setBounds") || method.getName().equals("setDimensionsAndPosition"))
                && method.getParameterCount() == 4
                && method.getParameterTypes()[0] == int.class
                && method.getParameterTypes()[1] == int.class
                && method.getParameterTypes()[2] == int.class
                && method.getParameterTypes()[3] == int.class) {
                method.invoke(widget, x, y, width, height);
                return;
            }
        }

        boolean usedSetWidthHeight = invokeWidgetMethod(widget, "setWidth", width)
            && invokeWidgetMethod(widget, "setHeight", height);

        boolean usedSetXY = invokeWidgetMethod(widget, "setX", x)
            && invokeWidgetMethod(widget, "setY", y);

        if (usedSetWidthHeight && usedSetXY) {
            return;
        }

        setWidgetIntField(widget, "width", width);
        setWidgetIntField(widget, "height", height);
        setWidgetIntField(widget, "x", x);
        setWidgetIntField(widget, "y", y);
    }

    private static boolean invokeWidgetMethod(ClickableWidget widget, String methodName, int value) {
        Class<?> type = widget.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(methodName, int.class);
                method.setAccessible(true);
                method.invoke(widget, value);
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static void setWidgetIntField(ClickableWidget widget, String fieldName, int value) throws ReflectiveOperationException {
        Class<?> type = widget.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(widget, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
            type = type.getSuperclass();
        }
        String yarnField = getYarnFieldName(fieldName);
        if (yarnField != null) {
            type = widget.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(yarnField);
                    field.setAccessible(true);
                    field.setInt(widget, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                }
                type = type.getSuperclass();
            }
        }
    }

    private static String getYarnFieldName(String fieldName) {
        switch (fieldName) {
            case "width": return "field_22757";
            case "height": return "field_22758";
            case "x": return "field_22754";
            case "y": return "field_22755";
            default: return null;
        }
    }

    private static Method findCompatibleBoundsMethod(Class<?> widgetClass) {
        for (Method method : widgetClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != void.class
                || method.getParameterCount() != 4
                || method.getParameterTypes()[0] != int.class
                || method.getParameterTypes()[1] != int.class
                || method.getParameterTypes()[2] != int.class
                || method.getParameterTypes()[3] != int.class) {
                continue;
            }

            String name = method.getName();
            if (name.equals("setBounds")
                || name.equals("setDimensionsAndPosition")
                || name.equals("method_55444")) {
                return method;
            }
        }
        return null;
    }

    private static void invokeBoundsMethod(Method method, Object widget, int width, int height, int x, int y)
        throws InvocationTargetException, IllegalAccessException {
        String name = method.getName();
        if (name.equals("setBounds")) {
            method.invoke(widget, width, height, x, y);
            return;
        }

        method.invoke(widget, x, y, width, height);
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
