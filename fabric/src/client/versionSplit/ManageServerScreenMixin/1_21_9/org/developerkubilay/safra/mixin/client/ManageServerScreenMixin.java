package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ManageServerScreen.class)
abstract class ManageServerScreenMixin extends Screen {
    @Shadow
    private Button addButton;

    @Shadow
    private EditBox ipEdit;

    @Shadow
    @Final
    private ServerData serverData;

    @Unique
    private Button safra$p2pButton;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected ManageServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        this.ipEdit.setMaxLength(200);
        boolean storedAddress = P2pManager.isP2pStoredAddress(this.ipEdit.getValue());
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = storedAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            this.ipEdit.setValue(P2pManager.toDisplayAddress(this.ipEdit.getValue()));
        }

        this.ipEdit.setWidth(122);
        this.safra$p2pButton = this.addRenderableWidget(
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
        this.safra$persistStoredAddress();
    }

    @Unique
    private void safra$persistStoredAddress() {
        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = this.ipEdit.getValue();
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        this.ipEdit.setValue(address);
        this.serverData.ip = address;
    }

    @Unique
    private Component safra$getToggleText() {
        return Component.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        if (this.ipEdit == null) {
            return;
        }
        this.ipEdit.setHint(this.safra$p2pEnabled
            ? Component.translatable("safra.p2p.placeholder")
            : Component.empty());
    }

    @Unique
    private void safra$updateValidation() {
        if (this.addButton == null || this.ipEdit == null) {
            return;
        }

        String address = this.ipEdit.getValue();
        this.addButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.isValidAddress(address);
    }
}
