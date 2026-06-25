package org.developerkubilay.safra.mixin.client;

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

import java.lang.reflect.Field;

@Mixin(EditServerScreen.class)
abstract class EditServerScreenMixin extends Screen {
    @Unique private boolean safra$p2pEnabled;
    @Unique private boolean safra$p2pInitialized;

    protected EditServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        EditBox ipEdit = this.safra$getField(EditBox.class, "ipEdit");
        if (ipEdit == null) {
            return;
        }

        ipEdit.setMaxLength(200);
        boolean storedAddress = P2pManager.isP2pStoredAddress(ipEdit.getValue());
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = storedAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            ipEdit.setValue(P2pManager.toDisplayAddress(ipEdit.getValue()));
        }

        ipEdit.setWidth(122);
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

    @Inject(method = "updateAddButtonStatus", at = @At("TAIL"))
    private void safra$overrideValidation(CallbackInfo ci) {
        this.safra$updateValidation();
    }

    @Inject(method = "onAdd", at = @At("HEAD"))
    private void safra$storeP2pAddress(CallbackInfo ci) {
        EditBox ipEdit = this.safra$getField(EditBox.class, "ipEdit");
        ServerData serverData = this.safra$getField(ServerData.class, "serverData");
        if (ipEdit == null || serverData == null) {
            return;
        }

        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = ipEdit.getValue();
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        ipEdit.setValue(address);
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
            ipEdit.setHint(this.safra$p2pEnabled ? Component.translatable("safra.p2p.placeholder") : Component.empty());
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

        String address = ipEdit.getValue();
        addButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.isValidAddress(address) && !nameEdit.getValue().isEmpty();
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
