package org.developerkubilay.safra.mixin.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditServerScreen.class)
abstract class EditServerScreenMixin extends Screen {
    @Unique private boolean safra$p2pEnabled;
    @Unique private boolean safra$p2pInitialized;

    protected EditServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = {"init", "m_7856_"}, at = @At("TAIL"), remap = false, require = 0)
    private void safra$initP2pUi(CallbackInfo ci) {
        EditBox ipEdit = this.safra$getField(EditBox.class, "ipEdit");
        if (ipEdit == null) {
            return;
        }

        this.safra$setEditMaxLength(ipEdit, 200);
        boolean storedAddress = P2pManager.isP2pStoredAddress(this.safra$getEditValue(ipEdit));
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = storedAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            this.safra$setEditValue(ipEdit, P2pManager.toDisplayAddress(this.safra$getEditValue(ipEdit)));
        }

        this.safra$setEditWidth(ipEdit, 122);
        this.addRenderableWidget(
            Button.builder(this.safra$getToggleText(), button -> {
                    this.safra$p2pEnabled = !this.safra$p2pEnabled;
                    SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
                    button.setMessage(this.safra$getToggleText());
                    this.safra$refreshAddressField();
                    this.safra$updateValidation();
                })
                .bounds(this.width / 2 + 30, 106, 70, 20)
                .build()
        );

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
        EditBox ipEdit = this.safra$getField(EditBox.class, "ipEdit");
        ServerData serverData = this.safra$getField(ServerData.class, "serverData");
        if (ipEdit == null || serverData == null) {
            return;
        }

        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = this.safra$getEditValue(ipEdit);
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        this.safra$setEditValue(ipEdit, address);
        serverData.ip = address;
    }

    @Unique
    private Component safra$getToggleText() {
        return Component.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        EditBox ipEdit = this.safra$getField(EditBox.class, "ipEdit");
        if (ipEdit != null) {
            this.safra$setEditHint(ipEdit, this.safra$p2pEnabled ? Component.translatable("safra.p2p.placeholder") : Component.empty());
        }
    }

    @Unique
    private void safra$updateValidation() {
        EditBox ipEdit = this.safra$getField(EditBox.class, "ipEdit");
        EditBox nameEdit = this.safra$getField(EditBox.class, "nameEdit");
        Button addButton = this.safra$getField(Button.class, "addButton");
        if (ipEdit == null || nameEdit == null || addButton == null) {
            return;
        }

        String address = this.safra$getEditValue(ipEdit);
        addButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.isValidAddress(address) && !this.safra$getEditValue(nameEdit).isEmpty();
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
        this.safra$callFirst(editBox, new String[]{"setWidth", "m_93671_"}, new Class<?>[]{int.class}, width);
    }

    @Unique
    private void safra$setEditMaxLength(EditBox editBox, int maxLength) {
        this.safra$callFirst(editBox, new String[]{"setMaxLength", "m_94112_"}, new Class<?>[]{int.class}, maxLength);
    }

    @Unique
    private Object safra$callFirst(Object target, String[] methodNames, Class<?>[] parameterTypes, Object... args) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
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
