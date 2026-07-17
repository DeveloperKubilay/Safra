package org.developerkubilay.safra.client.p2p;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.UnaryOperator;

public final class ForgeComponentCompat {
    private ForgeComponentCompat() {
    }

    public static Component translatable(String key, Object... args) {
        Object value = call(
            new String[]{"translatable", "m_237115_"},
            new Class<?>[]{String.class, Object[].class},
            new Object[]{key, args}
        );
        if (value instanceof Component component) {
            return component;
        }

        value = callBySignature(new Object[]{key, args}, String.class, Object[].class);
        if (value instanceof Component component) {
            return component;
        }

        value = call(
            new String[]{"translatable", "m_237110_"},
            new Class<?>[]{String.class},
            new Object[]{key}
        );
        if (value instanceof Component component) {
            return component;
        }

        value = callBySignature(new Object[]{key}, String.class);
        if (value instanceof Component component) {
            return component;
        }

        return literal(key);
    }

    public static Component empty() {
        Object value = call(new String[]{"empty", "m_237113_"}, new Class<?>[0], new Object[0]);
        if (value instanceof Component component) {
            return component;
        }
        value = callBySignature(new Object[0]);
        if (value instanceof Component component) {
            return component;
        }
        return literal("");
    }

    public static Component literal(String text) {
        Object value = call(
            new String[]{"literal", "m_237113_"},
            new Class<?>[]{String.class},
            new Object[]{text}
        );
        if (value instanceof Component component) {
            return component;
        }
        value = callBySignature(new Object[]{text}, String.class);
        if (value instanceof Component component) {
            return component;
        }
        throw new IllegalStateException("Could not resolve a compatible Component literal factory");
    }

    public static Component style(Component component, ChatFormatting... formatting) {
        if (component == null) {
            return empty();
        }

        Object styled = callInstance(
            component,
            new Class<?>[]{ChatFormatting[].class},
            new Object[]{formatting},
            "withStyle",
            "m_130944_"
        );
        if (styled instanceof Component result) {
            return result;
        }

        Object currentComponent = component;
        for (ChatFormatting value : formatting) {
            styled = callInstance(
                currentComponent,
                new Class<?>[]{ChatFormatting.class},
                new Object[]{value},
                "withStyle",
                "m_130940_"
            );
            if (styled instanceof Component result) {
                currentComponent = result;
            }
        }
        if (currentComponent instanceof Component result && currentComponent != component) {
            return result;
        }

        for (Method method : component.getClass().getMethods()) {
            if (!"withStyle".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1 && parameterTypes[0] == java.util.function.UnaryOperator.class) {
                try {
                    method.setAccessible(true);
                    Object styledComponent = method.invoke(component, (java.util.function.UnaryOperator<Object>) style -> {
                        Object current = style;
                        for (ChatFormatting value : formatting) {
                            current = applyStyleMethod(
                                current,
                                new String[]{"applyFormat", "applyLegacyFormat", "withColor", "m_131140_", "m_131157_", "m_131164_", "m_131152_"},
                                ChatFormatting.class,
                                value
                            );
                        }
                        return current;
                    });
                    if (styledComponent instanceof Component result) {
                        return result;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }

        return component;
    }

    public static Component copyableLiteral(String text, String hoverKey) {
        Component parsed = parseComponentJson(buildCopyableJson(text, hoverKey));
        if (parsed != null) {
            return parsed;
        }

        Component component = literal(text);
        Component hoverText = translatable(hoverKey);
        ClickEvent clickEvent = createClickEvent("COPY_TO_CLIPBOARD", "copy_to_clipboard", text);
        HoverEvent hoverEvent = createHoverEvent("SHOW_TEXT", "show_text", hoverText);

        for (Method method : component.getClass().getMethods()) {
            if (!"withStyle".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1 || parameterTypes[0] != UnaryOperator.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object styled = method.invoke(component, (UnaryOperator<Object>) style -> {
                    Object value = applyStyleMethod(style, new String[]{"withInsertion"}, String.class, text);
                    if (clickEvent != null) {
                        value = applyStyleMethod(value, new String[]{"withClickEvent"}, ClickEvent.class, clickEvent);
                    }
                    if (hoverEvent != null) {
                        value = applyStyleMethod(value, new String[]{"withHoverEvent"}, HoverEvent.class, hoverEvent);
                    }
                    return value;
                });
                if (styled instanceof Component result) {
                    return style(result, ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

return style(component, ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
    }

    public static Component clickableUrl(String url) {
        Component component = literal(url);
        ClickEvent clickEvent = createClickEvent("OPEN_URL", "open_url", url);
        for (Method method : component.getClass().getMethods()) {
            if (!"withStyle".equals(method.getName()) || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != UnaryOperator.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object styled = method.invoke(component, (UnaryOperator<Object>) style ->
                    clickEvent == null ? style : applyStyleMethod(style, new String[]{"withClickEvent"}, ClickEvent.class, clickEvent)
                );
                if (styled instanceof Component result) {
                    return style(result, ChatFormatting.BLUE, ChatFormatting.UNDERLINE);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return style(component, ChatFormatting.BLUE, ChatFormatting.UNDERLINE);
    }

    private static Object call(String[] names, Class<?>[] parameterTypes, Object[] args) {
        for (String name : names) {
            try {
                Method method = Component.class.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(null, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Object callBySignature(Object[] args, Class<?>... parameterTypes) {
        for (Method method : Component.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] methodParameterTypes = method.getParameterTypes();
            if (methodParameterTypes.length != parameterTypes.length || !Component.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (methodParameterTypes[i] != parameterTypes[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(null, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Object applyStyleMethod(Object style, String[] names, Class<?> parameterType, Object value) {
        if (style == null) {
            return null;
        }

        for (String name : names) {
            try {
                Method method = style.getClass().getMethod(name, parameterType);
                method.setAccessible(true);
                return method.invoke(style, value);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (Method method : style.getClass().getMethods()) {
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != parameterType) {
                continue;
            }
            if (!Style.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(style, value);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return style;
    }

    private static ClickEvent createClickEvent(String enumName, String actionName, String value) {
        Object action = resolveEnumConstant(ClickEvent.Action.class, enumName, actionName);
        if (!(action instanceof ClickEvent.Action clickAction)) {
            return null;
        }
        return new ClickEvent(clickAction, value);
    }

    private static HoverEvent createHoverEvent(String enumName, String actionName, Component value) {
        Object action = resolveEnumConstant(HoverEvent.Action.class, enumName, actionName);
        if (!(action instanceof HoverEvent.Action<?> hoverAction)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        HoverEvent.Action<Component> typedAction = (HoverEvent.Action<Component>) hoverAction;
        return new HoverEvent(typedAction, value);
    }

    private static Object resolveEnumConstant(Class<?> enumType, String... expectedNames) {
        Object[] constants = enumType.getEnumConstants();
        if (constants == null) {
            return null;
        }

        for (Object constant : constants) {
            if (!(constant instanceof Enum<?> enumValue)) {
                continue;
            }
            String enumValueName = enumValue.name();
            for (String expectedName : expectedNames) {
                if (expectedName.equals(enumValueName)) {
                    return constant;
                }
            }
            Object resolvedName = callInstance(constant, new Class<?>[0], new Object[0], "getName", "m_130662_", "m_130661_");
            if (resolvedName instanceof String stringName) {
                for (String expectedName : expectedNames) {
                    if (expectedName.equalsIgnoreCase(stringName)) {
                        return constant;
                    }
                }
            }
        }

        return null;
    }

    private static Object callInstance(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Component parseComponentJson(String json) {
        for (Class<?> nestedClass : Component.class.getDeclaredClasses()) {
            if (!"Serializer".equals(nestedClass.getSimpleName())) {
                continue;
            }
            for (Method method : nestedClass.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!Component.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 1 || parameterTypes[0] != String.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(null, json);
                    if (value instanceof Component component) {
                        return component;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    private static String buildCopyableJson(String text, String hoverKey) {
        return "{\"text\":\"" + escapeJson(text)
            + "\",\"color\":\"aqua\""
            + ",\"underlined\":true"
            + ",\"insertion\":\"" + escapeJson(text)
            + "\",\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\"" + escapeJson(text)
            + "\"},\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"translate\":\"" + escapeJson(hoverKey)
            + "\"}}}";
    }

    private static String escapeJson(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }
}
