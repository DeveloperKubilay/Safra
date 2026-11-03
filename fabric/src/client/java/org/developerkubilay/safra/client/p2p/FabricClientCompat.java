package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class FabricClientCompat {
    private FabricClientCompat() {
    }

    public static MutableText translatable(String key, Object... args) {
        try {
            Method method = Text.class.getMethod("method_43469", String.class, Object[].class);
            return (MutableText) method.invoke(null, key, args);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Method method = Text.class.getMethod("method_43471", String.class);
            return (MutableText) method.invoke(null, key);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.class_2588");
            Constructor<?> constructor = clazz.getConstructor(String.class, Object[].class);
            return (MutableText) constructor.newInstance(key, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create translatable text", exception);
        }
    }

    public static MutableText literal(String value) {
        try {
            Method method = Text.class.getMethod("method_43470", String.class);
            return (MutableText) method.invoke(null, value);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.class_2585");
            Constructor<?> constructor = clazz.getConstructor(String.class);
            return (MutableText) constructor.newInstance(value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create literal text", exception);
        }
    }

    public static Text screenDone() {
        return screenText("DONE", "Done");
    }

    public static Text screenBack() {
        return screenText("BACK", "Back");
    }

    public static void copyServerInfo(ServerInfo target, ServerInfo source) {
        for (String methodName : new String[]{"method_44292", "method_2996", "copyWithSettingsFrom", "copyFrom"}) {
            try {
                Method method = ServerInfo.class.getMethod(methodName, ServerInfo.class);
                method.invoke(target, source);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (String fieldName : new String[]{"resourcePackPolicy", "icon"}) {
            try {
                Field field = ServerInfo.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, field.get(source));
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    public static void narrate(MinecraftClient client, Text text) {
        if (client == null || text == null) {
            return;
        }

        try {
            Method method = MinecraftClient.class.getMethod("method_44713");
            Object narrator = method.invoke(client);
            narrator.getClass().getMethod("method_37015", Text.class).invoke(narrator, text);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Field narratorField = MinecraftClient.class.getDeclaredField("field_39769");
            narratorField.setAccessible(true);
            Object narrator = narratorField.get(client);
            narrator.getClass().getMethod("method_37015", Text.class).invoke(narrator, text);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Class<?> clazz = Class.forName("net.minecraft.class_333");
            Field instanceField = clazz.getField("field_2054");
            Object narrator = instanceField.get(null);
            clazz.getMethod("method_37015", Text.class).invoke(narrator, text);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not narrate message", exception);
        }
    }

    private static Text screenText(String fieldName, String fallbackKey) {
        String runtimeFieldName = switch (fieldName) {
            case "DONE" -> "field_24334";
            case "BACK" -> "field_24339";
            default -> null;
        };
        if (runtimeFieldName != null) {
            try {
                Class<?> clazz = Class.forName("net.minecraft.class_5244");
                Field field = clazz.getField(runtimeFieldName);
                return (Text) field.get(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return literal(fallbackKey);
    }

    public static net.minecraft.client.gui.widget.ButtonWidget createButton(int x, int y, int width, int height, Text text, net.minecraft.client.gui.widget.ButtonWidget.PressAction action) {
        try {
            Constructor<?> constructor = net.minecraft.client.gui.widget.ButtonWidget.class.getConstructor(int.class, int.class, int.class, int.class, Text.class, net.minecraft.client.gui.widget.ButtonWidget.PressAction.class);
            return (net.minecraft.client.gui.widget.ButtonWidget) constructor.newInstance(x, y, width, height, text, action);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Method builderMethod = findBuilderMethod();
            if (builderMethod == null) {
                throw new NoSuchMethodException("No compatible ButtonWidget builder method found");
            }

            Object builder = builderMethod.invoke(null, text, action);
            invokeCompatibleMethod(builder, new String[]{"dimensions", "method_46434"}, x, y, width, height);
            return (net.minecraft.client.gui.widget.ButtonWidget) invokeCompatibleMethod(builder, new String[]{"build", "method_46431"});
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create compatible button", exception);
        }
    }

    public static void setX(net.minecraft.client.gui.widget.ClickableWidget widget, int x) {
        try {
            invokeCompatibleMethod(widget, new String[]{"setX", "method_46419"}, x);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        setIntField(widget, new String[]{"x", "field_22758"}, x);
    }

    public static void setY(net.minecraft.client.gui.widget.ClickableWidget widget, int y) {
        try {
            invokeCompatibleMethod(widget, new String[]{"setY", "method_46421"}, y);
            return;
        } catch (ReflectiveOperationException ignored) {
        }

        setIntField(widget, new String[]{"y", "field_22759"}, y);
    }

    public static void drawCenteredText(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.font.TextRenderer textRenderer, Text text, int centerX, int y, int color) {
        try {
            Class<?> helperClass;
            try {
                helperClass = Class.forName("net.minecraft.client.gui.DrawableHelper");
            } catch (ClassNotFoundException e) {
                helperClass = Class.forName("net.minecraft.client.gui.DrawContext");
            }
            
            for (Method method : helperClass.getMethods()) {
                if (method.getName().equals("drawCenteredText") || method.getName().equals("drawCenteredTextWithShadow") || method.getName().equals("method_25303")) {
                    if (method.getParameterCount() == 6 && method.getParameterTypes()[2] == Text.class) {
                        method.invoke(null, matrices, textRenderer, text, centerX, y, color);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static Method findBuilderMethod() {
        for (String methodName : new String[]{"builder", "method_46430"}) {
            try {
                return net.minecraft.client.gui.widget.ButtonWidget.class.getMethod(methodName, Text.class, net.minecraft.client.gui.widget.ButtonWidget.PressAction.class);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (Method method : net.minecraft.client.gui.widget.ButtonWidget.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 2
                || method.getParameterTypes()[0] != Text.class
                || method.getParameterTypes()[1] != net.minecraft.client.gui.widget.ButtonWidget.PressAction.class) {
                continue;
            }
            return method;
        }
        return null;
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
