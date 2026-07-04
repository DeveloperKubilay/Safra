package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
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

@Mixin(DirectJoinServerScreen.class)
abstract class DirectJoinServerScreenMixin extends Screen {
    @Shadow
    private Button selectButton;

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

    protected DirectJoinServerScreenMixin(Component title) {
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

        Button cancelButton = this.safra$findSecondaryButton(this.selectButton);
        if (cancelButton != null) {
            this.safra$moveButton(this.selectButton, this.width / 2 - 100, this.height / 4 + 108, 98);
            this.safra$moveButton(cancelButton, this.width / 2 + 2, this.height / 4 + 108, 98);
        }

        this.safra$p2pButton = this.addRenderableWidget(
            org.developerkubilay.safra.client.ForgeClientCompat.createButton(this.width / 2 - 100, this.height / 4 + 132, 200, 20, this.safra$getToggleText(), button -> {
                    this.safra$p2pEnabled = !this.safra$p2pEnabled;
                    SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);

                    if (this.safra$p2pEnabled && this.ipEdit != null) {
                        String typedAddress = this.ipEdit.getValue();
                        if (typedAddress != null && !typedAddress.isEmpty()
                            && !P2pManager.isValidP2pAddress(typedAddress)) {
                            this.ipEdit.setValue("");
                        }
                    }

                    button.setMessage(this.safra$getToggleText());
                    this.safra$refreshAddressField();
                    this.safra$updateValidation();
                })
        );

        this.safra$p2pInitialized = true;
        this.safra$refreshAddressField();
        this.safra$updateValidation();
    }

    @Inject(method = "updateSelectButtonStatus", at = @At("TAIL"))
    private void safra$overrideValidation(CallbackInfo ci) {
        this.safra$refreshAddressField();
        this.safra$updateValidation();
    }

    @Inject(method = "onSelect", at = @At("HEAD"))
    private void safra$storeP2pAddress(CallbackInfo ci) {
        this.safra$persistStoredAddress();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void safra$storeLastP2pAddress(CallbackInfo ci) {
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
        return ForgeClientCompat.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        if (this.ipEdit == null) {
            return;
        }
        this.ipEdit.setSuggestion(null);
    }

    @Unique
    private void safra$updateValidation() {
        if (this.selectButton == null || this.ipEdit == null) {
            return;
        }

        String address = this.ipEdit.getValue();
        this.selectButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.isValidAddress(address);
    }

    @Unique
    private Button safra$findSecondaryButton(Button primaryButton) {
        Button candidate = null;
        for (GuiEventListener element : this.children()) {
            if (element instanceof Button button && button != primaryButton) {
                candidate = button;
            }
        }
        return candidate;
    }

    @Unique
    private void safra$moveButton(Button button, int x, int y, int width) {
        button.setWidth(width);
        org.developerkubilay.safra.client.ForgeClientCompat.setX(button, x);
        org.developerkubilay.safra.client.ForgeClientCompat.setY(button, y);
    }

}

