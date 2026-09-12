package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

@Mixin(EditServerScreen.class)
abstract class EditServerScreenMixin extends Screen {
    @Unique private boolean safra$p2pEnabled;
    @Unique private boolean safra$p2pInitialized;

    protected EditServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = {"init", "m_7856_"}, at = @At("TAIL"), remap = false, require = 0)
    private void safra$initP2pUi(CallbackInfo ci) {
        EditBox ipEdit = this.safra$getField(EditBox.class, "f_96010_");
        if (ipEdit == null) {
            return;
        }

        this.safra$setEditMaxLength(ipEdit, 200);
        String currentAddress = this.safra$getEditValue(ipEdit);
        boolean storedAddress = P2pManager.isP2pStoredAddress(currentAddress);
        boolean likelyP2pAddress = P2pManager.isLikelyP2pAddress(currentAddress);
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = likelyP2pAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            try {
                this.safra$setEditValue(ipEdit, P2pManager.toDisplayAddress(currentAddress));
            } catch (IllegalArgumentException ignored) {
                this.safra$p2pEnabled = false;
                this.safra$setEditValue(ipEdit, "");
            }
        }

        this.safra$setEditX(ipEdit, this.safra$getScreenWidth() / 2 - 98);
        this.safra$setEditWidth(ipEdit, 140);
        this.safra$addRenderableWidgetCompat(this.safra$createButton(this.safra$getScreenWidth() / 2 + 45, 105, 55, 23, this.safra$getToggleText(), button -> {
            this.safra$p2pEnabled = !this.safra$p2pEnabled;
            SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
            this.safra$setButtonMessage(button, this.safra$getToggleText());
            this.safra$refreshAddressField();
            this.safra$updateValidation();
        }));

        this.safra$p2pInitialized = true;
        this.safra$refreshAddressField();
        this.safra$updateValidation();
    }

    @Inject(method = {"updateAddButtonStatus", "m_169305_"}, at = @At("TAIL"), remap = false, require = 0)
    private void safra$overrideValidation(CallbackInfo ci) {
        this.safra$updateValidation();
    }

    @Inject(method = {"onAdd", "m_96045_"}, at = @At("HEAD"), remap = false, require = 0)
    private void safra$storeP2pAddress(CallbackInfo ci) {
        EditBox ipEdit = this.safra$getField(EditBox.class, "f_96010_");
        ServerData serverData = this.safra$getField(ServerData.class, "f_96009_");
        if (ipEdit == null || serverData == null) {
            return;
        }

        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = this.safra$getEditValue(ipEdit);
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        this.safra$setServerAddress(serverData, address);
    }

    @Unique
    private Component safra$getToggleText() {
        return ForgeComponentCompat.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        EditBox ipEdit = this.safra$getField(EditBox.class, "f_96010_");
        if (ipEdit != null) {
            this.safra$setEditHint(
                ipEdit,
                this.safra$p2pEnabled ? ForgeComponentCompat.translatable("safra.p2p.placeholder") : ForgeComponentCompat.empty()
            );
        }
    }

    @Unique
    private void safra$updateValidation() {
        EditBox ipEdit = this.safra$getField(EditBox.class, "f_96010_");
        EditBox nameEdit = this.safra$getField(EditBox.class, "f_96011_");
        Button addButton = this.safra$getField(Button.class, "f_96007_");
        if (ipEdit == null || nameEdit == null || addButton == null) {
            return;
        }

        String address = this.safra$getEditValue(ipEdit);
        this.safra$setButtonActive(addButton, this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : this.safra$isValidDirectAddress(address) && !this.safra$getEditValue(nameEdit).isEmpty());
    }

    @Unique
    private boolean safra$isValidDirectAddress(String address) {
        if (address == null) {
            return false;
        }

        String trimmed = address.trim();
        if (trimmed.isEmpty() || trimmed.contains(" ")) {
            return false;
        }

        if (trimmed.startsWith(":") || trimmed.endsWith(":")) {
            return false;
        }

        int colonIndex = trimmed.lastIndexOf(':');
        if (colonIndex >= 0 && trimmed.indexOf(']') < colonIndex) {
            String portPart = trimmed.substring(colonIndex + 1);
            if (portPart.isEmpty()) {
                return false;
            }
            for (int i = 0; i < portPart.length(); i++) {
                if (!Character.isDigit(portPart.charAt(i))) {
                    return false;
                }
            }
        }

        return true;
    }

    @Unique
    private String safra$getEditValue(EditBox editBox) {
        Object value = this.safra$callFirst(editBox, new String[]{"getValue", "m_94155_"}, new Class<?>[0]);
        return value instanceof String ? (String) value : "";
    }

    @Unique
    private void safra$setEditValue(EditBox editBox, String value) {
        this.safra$callFirst(editBox, new String[]{"setValue", "m_94173_"}, new Class<?>[]{String.class}, value);
    }

    @Unique
    private void safra$setEditHint(EditBox editBox, Component hint) {
        this.safra$callFirst(editBox, new String[]{"setHint", "m_257544_"}, new Class<?>[]{Component.class}, hint);
    }

    @Unique
    private void safra$setEditWidth(EditBox editBox, int width) {
        this.safra$callFirst(editBox, new String[]{"setWidth", "m_93674_"}, new Class<?>[]{int.class}, width);
        this.safra$setNamedIntField(editBox, width, "width", "f_93618_");
    }

    @Unique
    private void safra$setEditX(EditBox editBox, int x) {
        this.safra$callFirst(editBox, new String[]{"setX", "m_252865_"}, new Class<?>[]{int.class}, x);
        this.safra$setNamedIntField(editBox, x, "x", "f_93620_");
    }

    @Unique
    private void safra$setEditMaxLength(EditBox editBox, int maxLength) {
        this.safra$callFirst(editBox, new String[]{"setMaxLength", "m_94112_"}, new Class<?>[]{int.class}, maxLength);
    }

    @Unique
    private void safra$setServerAddress(ServerData serverData, String address) {
        Class<?> type = serverData.getClass();
        while (type != null) {
            for (String name : new String[]{"ip", "f_105363_"}) {
                try {
                    Field field = type.getDeclaredField(name);
                    if (field.getType() != String.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    field.set(serverData, address);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }

        throw new IllegalStateException("Failed to resolve compatible ServerData address field");
    }

    @Unique
    private void safra$setButtonActive(Button button, boolean active) {
        Class<?> type = button.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != boolean.class) {
                    continue;
                }
                if (!field.getName().equals("active") && !field.getName().equals("f_93623_")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.setBoolean(button, active);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    @Unique
    private void safra$setButtonMessage(Button button, Component message) {
        Object result = this.safra$callFirst(button, new String[]{"setMessage", "m_93666_"}, new Class<?>[]{Component.class}, message);
        if (result != null) {
            return;
        }

        Class<?> type = button.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!Component.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(button, message);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    @Unique
    private int safra$getScreenWidth() {
        Object widthValue = this.safra$getNamedFieldValue(this, int.class, "width", "f_96543_");
        if (widthValue instanceof Integer width) {
            return width;
        }
        return 0;
    }

    @Unique
    private <T extends GuiEventListener & Renderable & NarratableEntry> T safra$addRenderableWidgetCompat(T widget) {
        Object added = this.safra$callFirst(
            this,
            new String[]{"addRenderableWidget", "m_142416_"},
            new Class<?>[]{GuiEventListener.class},
            widget
        );
        if (added instanceof GuiEventListener) {
            return widget;
        }

        added = this.safra$callFirst(
            this,
            new String[]{"addRenderableOnly", "m_169394_"},
            new Class<?>[]{Renderable.class},
            widget
        );
        if (added != null) {
            this.safra$callFirst(
                this,
                new String[]{"addWidget", "m_7787_"},
                new Class<?>[]{GuiEventListener.class},
                widget
            );
            return widget;
        }

        throw new IllegalStateException("Could not add compatible widget to screen");
    }

    @Unique
    private Button safra$createButton(int x, int y, int width, int height, Component message, Consumer<Button> onPress) {
        Button.OnPress buttonOnPress = this.safra$createOnPress(onPress);
        try {
            Object builder = this.safra$callStaticFirst(
                Button.class,
                new String[]{"builder", "m_253074_"},
                new Class<?>[]{Component.class, Button.OnPress.class},
                message,
                buttonOnPress
            );
            if (builder != null) {
                this.safra$callFirst(builder, new String[]{"bounds", "m_252987_"}, new Class<?>[]{int.class, int.class, int.class, int.class}, x, y, width, height);
                Object built = this.safra$callFirst(builder, new String[]{"build", "m_253136_"}, new Class<?>[0]);
                if (built instanceof Button button) {
                    return button;
                }
            }
        } catch (RuntimeException ignored) {
        }

        try {
            Class<?> narrationType = Class.forName("net.minecraft.client.gui.components.Button$CreateNarration");
            Constructor<Button> constructor = Button.class.getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                int.class,
                Component.class,
                Button.OnPress.class,
                narrationType
            );
            constructor.setAccessible(true);
            Object narration = this.safra$getStaticField(Button.class, narrationType, "DEFAULT_NARRATION", "f_252438_");
            if (narration == null) {
                narration = this.safra$findStaticFieldValue(Button.class, narrationType);
            }
            if (narration != null) {
                return constructor.newInstance(x, y, width, height, message, buttonOnPress, narration);
            }
        } catch (ReflectiveOperationException ignored) {
        }

        throw new IllegalStateException("Could not create a compatible Button instance");
    }

    @Unique
    private Button.OnPress safra$createOnPress(Consumer<Button> consumer) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1 && args[0] instanceof Button button) {
                consumer.accept(button);
                return null;
            }

            if ("toString".equals(method.getName())) {
                return "SafraOnPressProxy";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            return null;
        };
        return (Button.OnPress) Proxy.newProxyInstance(
            Button.OnPress.class.getClassLoader(),
            new Class<?>[]{Button.OnPress.class},
            handler
        );
    }

    @Unique
    private Object safra$callFirst(Object target, String[] methodNames, Class<?>[] parameterTypes, Object... args) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String methodName : methodNames) {
                try {
                    Method method = type.getDeclaredMethod(methodName, parameterTypes);
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    @Unique
    private Object safra$callStaticFirst(Class<?> targetType, String[] methodNames, Class<?>[] parameterTypes, Object... args) {
        for (String methodName : methodNames) {
            try {
                Method method = targetType.getMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method.invoke(null, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @Unique
    private Object safra$getStaticField(Class<?> owner, Class<?> expectedType, String... names) {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                if (!Modifier.isStatic(field.getModifiers()) || !expectedType.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return field.get(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @Unique
    private Object safra$findStaticFieldValue(Class<?> owner, Class<?> expectedType) {
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !expectedType.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @Unique
    private Object safra$getNamedFieldValue(Object target, Class<?> expectedType, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    if (field.getType() != expectedType) {
                        continue;
                    }
                    field.setAccessible(true);
                    return field.get(target);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    @Unique
    private void safra$setNamedIntField(Object target, int value, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    if (field.getType() != int.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    field.setInt(target, value);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }


    @Unique
    private <T> T safra$getField(Class<T> expectedType, String preferredFieldName) {
        Class<?> type = this.getClass();
        Field fallback = null;
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!expectedType.isAssignableFrom(field.getType())) {
                    continue;
                }
                if (fallback == null) {
                    fallback = field;
                }
                if (!field.getName().equals(preferredFieldName)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(this);
                    if (value != null) {
                        return expectedType.cast(value);
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }

        if (fallback == null) {
            return null;
        }

        try {
            fallback.setAccessible(true);
            Object value = fallback.get(this);
            return expectedType.cast(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
