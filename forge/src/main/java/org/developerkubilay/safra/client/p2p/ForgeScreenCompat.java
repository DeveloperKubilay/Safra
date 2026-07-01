package org.developerkubilay.safra.client.p2p;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class ForgeScreenCompat {
    private static final String[] WIDTH_FIELDS = {"width", "field_230708_k_"};
    private static final String[] HEIGHT_FIELDS = {"height", "field_230709_l_"};
    private static final String[] MINECRAFT_FIELDS = {"minecraft", "field_230706_i_"};
    private static final String[] BUTTONS_FIELDS = {"buttons", "field_230705_e_"};
    private static final String[] CHILDREN_FIELDS = {"children", "field_230707_j_"};
    private static final String[] ACTIVE_FIELDS = {"active", "field_230694_p_"};
    private static final String[] X_FIELDS = {"x", "field_230690_l_"};
    private static final String[] Y_FIELDS = {"y", "field_230691_m_"};
    private static final String[] WIDTH_BUTTON_FIELDS = {"width", "field_230689_k_"};

    private ForgeScreenCompat() {
    }

    public static int getWidth(Screen screen) {
        return getIntField(screen, WIDTH_FIELDS);
    }

    public static int getHeight(Screen screen) {
        return getIntField(screen, HEIGHT_FIELDS);
    }

    public static Minecraft getMinecraft(Screen screen) {
        Object value = getFieldValue(screen, MINECRAFT_FIELDS);
        return value instanceof Minecraft ? (Minecraft) value : null;
    }

    public static Button addButton(Screen screen, Button button) {
        if (invokeAddButton(screen, button)) {
            return button;
        }
        addToFieldList(screen, BUTTONS_FIELDS, button);
        addToFieldList(screen, CHILDREN_FIELDS, button);
        return button;
    }

    public static List<IGuiEventListener> getChildren(Screen screen) {
        Object value = getFieldValue(screen, CHILDREN_FIELDS);
        if (value instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<IGuiEventListener> list = (List<IGuiEventListener>) value;
            return list;
        }
        return java.util.Collections.emptyList();
    }

    public static void setButtonWidth(Button button, int width) {
        setIntField(button, width, WIDTH_BUTTON_FIELDS);
        invokeNoResult(button, new String[]{"setWidth"}, new Class<?>[]{int.class}, width);
    }

    public static void setButtonX(Button button, int x) {
        setIntField(button, x, X_FIELDS);
    }

    public static void setButtonY(Button button, int y) {
        setIntField(button, y, Y_FIELDS);
    }

    public static void setButtonActive(Button button, boolean active) {
        setBooleanField(button, active, ACTIVE_FIELDS);
    }

    public static void setButtonMessage(Button button, ITextComponent message) {
        if (invokeNoResult(button, new String[]{"setMessage", "func_238482_a_"}, new Class<?>[]{ITextComponent.class}, message)) {
            return;
        }
        setObjectField(button, message, new String[]{"message", "field_230688_j_"});
    }

    public static void renderBackground(Screen screen, MatrixStack matrixStack) {
        if (invokeNoResult(screen, new String[]{"renderBackground", "func_230446_a_"}, new Class<?>[]{MatrixStack.class}, matrixStack)) {
            return;
        }
        invokeNoResult(screen, new String[]{"func_230446_a_"}, new Class<?>[]{MatrixStack.class}, matrixStack);
    }

    private static boolean invokeAddButton(Screen screen, Button button) {
        return invokeNoResult(screen, new String[]{"addButton", "func_230480_a_"}, new Class<?>[]{Widget.class}, button)
            || invokeNoResult(screen, new String[]{"addButton", "func_230480_a_"}, new Class<?>[]{Button.class}, button);
    }

    private static boolean invokeNoResult(Object target, String[] names, Class<?>[] parameterTypes, Object... args) {
        for (String name : names) {
            try {
                Method method = findMethod(target.getClass(), name, parameterTypes);
                if (method == null) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(target, args);
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void addToFieldList(Object target, String[] fieldNames, Object value) {
        Object fieldValue = getFieldValue(target, fieldNames);
        if (fieldValue instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) fieldValue;
            if (!list.contains(value)) {
                list.add(value);
            }
        }
    }

    private static int getIntField(Object target, String[] names) {
        Object value = getFieldValue(target, names);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static Object getFieldValue(Object target, String[] names) {
        for (String name : names) {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static void setIntField(Object target, int value, String[] names) {
        for (String name : names) {
            Field field = findField(target.getClass(), name);
            if (field == null || field.getType() != int.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.setInt(target, value);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static void setBooleanField(Object target, boolean value, String[] names) {
        for (String name : names) {
            Field field = findField(target.getClass(), name);
            if (field == null || field.getType() != boolean.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.setBoolean(target, value);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static void setObjectField(Object target, Object value, String[] names) {
        for (String name : names) {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
