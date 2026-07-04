package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
            Method builderMethod = null;
            try {
                builderMethod = net.minecraft.client.gui.widget.ButtonWidget.class.getMethod("builder", Text.class, net.minecraft.client.gui.widget.ButtonWidget.PressAction.class);
            } catch (Exception e) {
                try {
                    builderMethod = net.minecraft.client.gui.widget.ButtonWidget.class.getMethod("method_46430", Text.class, net.minecraft.client.gui.widget.ButtonWidget.PressAction.class);
                } catch (Exception ignored) {}
            }

            if (builderMethod != null) {
                Object builder = builderMethod.invoke(null, text, action);
                Method dimensionsMethod;
                try {
                    dimensionsMethod = builder.getClass().getMethod("dimensions", int.class, int.class, int.class, int.class);
                } catch (Exception e) {
                    dimensionsMethod = builder.getClass().getMethod("method_46434", int.class, int.class, int.class, int.class);
                }
                builder = dimensionsMethod.invoke(builder, x, y, width, height);

                Method buildMethod;
                try {
                    buildMethod = builder.getClass().getMethod("build");
                } catch (Exception e) {
                    buildMethod = builder.getClass().getMethod("method_46431");
                }
                return (net.minecraft.client.gui.widget.ButtonWidget) buildMethod.invoke(builder);
            }
        } catch (Exception ignored) {
        }
        
        try {
            Constructor<?> constructor = net.minecraft.client.gui.widget.ButtonWidget.class.getConstructor(int.class, int.class, int.class, int.class, Text.class, net.minecraft.client.gui.widget.ButtonWidget.PressAction.class);
            return (net.minecraft.client.gui.widget.ButtonWidget) constructor.newInstance(x, y, width, height, text, action);
        } catch (Exception e) {
            throw new RuntimeException("Could not create button", e);
        }
    }

    public static void setX(net.minecraft.client.gui.widget.ClickableWidget widget, int x) {
        try {
            Method setXMethod = null;
            try {
                setXMethod = net.minecraft.client.gui.widget.ClickableWidget.class.getMethod("setX", int.class);
            } catch (Exception e) {
                try {
                    setXMethod = net.minecraft.client.gui.widget.ClickableWidget.class.getMethod("method_46419", int.class);
                } catch (Exception ignored) {}
            }
            if (setXMethod != null) {
                setXMethod.invoke(widget, x);
                return;
            }
        } catch (Exception ignored) {}

        try {
            Field xField = net.minecraft.client.gui.widget.ClickableWidget.class.getField("x");
            xField.set(widget, x);
        } catch (Exception e) {
            try {
                Field xField = net.minecraft.client.gui.widget.ClickableWidget.class.getField("field_22758");
                xField.set(widget, x);
            } catch (Exception ignored) {}
        }
    }

    public static void setY(net.minecraft.client.gui.widget.ClickableWidget widget, int y) {
        try {
            Method setYMethod = null;
            try {
                setYMethod = net.minecraft.client.gui.widget.ClickableWidget.class.getMethod("setY", int.class);
            } catch (Exception e) {
                try {
                    setYMethod = net.minecraft.client.gui.widget.ClickableWidget.class.getMethod("method_46421", int.class);
                } catch (Exception ignored) {}
            }
            if (setYMethod != null) {
                setYMethod.invoke(widget, y);
                return;
            }
        } catch (Exception ignored) {}

        try {
            Field yField = net.minecraft.client.gui.widget.ClickableWidget.class.getField("y");
            yField.set(widget, y);
        } catch (Exception e) {
            try {
                Field yField = net.minecraft.client.gui.widget.ClickableWidget.class.getField("field_22759");
                yField.set(widget, y);
            } catch (Exception ignored) {}
        }
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
}
