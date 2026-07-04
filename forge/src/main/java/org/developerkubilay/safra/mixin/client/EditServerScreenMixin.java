package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.ForgeClientCompat;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditServerScreen.class)
abstract class EditServerScreenMixin extends Screen {
    @Shadow private Button addButton;
    @Shadow private EditBox ipEdit;
    @Shadow private EditBox nameEdit;
    @Shadow @Final private ServerData serverData;

    @Unique private boolean safra$p2pEnabled;
    @Unique private boolean safra$p2pInitialized;

    protected EditServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        this.ipEdit.setMaxLength(200);
        String currentAddress = this.ipEdit.getValue();
        boolean storedAddress = P2pManager.isP2pStoredAddress(currentAddress);
        boolean likelyP2pAddress = P2pManager.isLikelyP2pAddress(currentAddress);
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = likelyP2pAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            this.ipEdit.setValue(P2pManager.toDisplayAddress(this.ipEdit.getValue()));
        }

        this.ipEdit.setWidth(122);
        this.addRenderableWidget(
            org.developerkubilay.safra.client.ForgeClientCompat.createButton(this.width / 2 + 30, 106, 70, 20, this.safra$getToggleText(), button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
                button.setMessage(this.safra$getToggleText());
                this.safra$refreshAddressField();
                this.safra$updateValidation();
            })
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
        return ForgeClientCompat.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        this.ipEdit.setSuggestion(null);
    }

    @Unique
    private void safra$updateValidation() {
        String address = this.ipEdit.getValue();
        this.addButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.isValidAddress(address) && !this.nameEdit.getValue().isEmpty();
    }

}

