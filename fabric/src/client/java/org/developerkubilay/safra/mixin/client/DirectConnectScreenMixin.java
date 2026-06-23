package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
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

@Mixin(DirectJoinServerScreen.class)
abstract class DirectConnectScreenMixin extends Screen {
    @Shadow
    private Button selectButton;

    @Shadow
    private EditBox ipEdit;

    @Shadow
    @Final
    private ServerData serverData;

    @Unique
    private CycleButton<Boolean> safra$p2pToggle;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected DirectConnectScreenMixin(Component title) {
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

        Button cancelButton = this.safra$findSecondaryButton(this.selectButton);
        if (cancelButton != null) {
            this.selectButton.setRectangle(98, 20, this.width / 2 - 100, this.height / 4 + 108);
            cancelButton.setRectangle(98, 20, this.width / 2 + 2, this.height / 4 + 108);
        }

        this.safra$p2pToggle = this.addRenderableWidget(
            CycleButton.onOffBuilder(this.safra$p2pEnabled)
                .create(this.width / 2 - 100, this.height / 4 + 84, 200, 20,
                    Component.translatable("safra.p2p.toggle"),
                    (button, value) -> {
                        this.safra$p2pEnabled = value;
                        SafraClientConfig.get().setDirectConnectP2pEnabled(value);

                        if (value && this.ipEdit != null) {
                            String currentAddress = this.ipEdit.getValue();
                            if (currentAddress != null && !currentAddress.isEmpty()
                                && !P2pManager.isValidP2pAddress(currentAddress)) {
                                this.ipEdit.setValue("");
                            }
                        }

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
            if (element instanceof Button buttonWidget && buttonWidget != primaryButton) {
                candidate = buttonWidget;
            }
        }
        return candidate;
    }
}
