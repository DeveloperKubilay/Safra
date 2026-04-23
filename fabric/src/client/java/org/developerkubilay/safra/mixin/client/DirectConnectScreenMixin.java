package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.DirectConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DirectConnectScreen.class)
abstract class DirectConnectScreenMixin extends Screen {
    @Shadow
    private ButtonWidget selectServerButton;

    @Shadow
    private TextFieldWidget addressField;

    @Unique
    private CyclingButtonWidget<Boolean> safra$p2pToggle;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected DirectConnectScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        this.addressField.setMaxLength(200);
        boolean storedAddress = P2pManager.isP2pStoredAddress(this.addressField.getText());
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = storedAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            this.addressField.setText(P2pManager.toDisplayAddress(this.addressField.getText()));
        }

        ButtonWidget cancelButton = this.safra$findSecondaryButton(this.selectServerButton);
        if (cancelButton != null) {
            this.selectServerButton.setDimensionsAndPosition(98, 20, this.width / 2 - 100, this.height / 4 + 108);
            cancelButton.setDimensionsAndPosition(98, 20, this.width / 2 + 2, this.height / 4 + 108);
        }

        this.safra$p2pToggle = this.addDrawableChild(
            CyclingButtonWidget.onOffBuilder(this.safra$p2pEnabled)
                .build(this.width / 2 - 100, this.height / 4 + 84, 200, 20,
                    Text.translatable("safra.p2p.toggle"),
                    (button, value) -> {
                        this.safra$p2pEnabled = value;
                        SafraClientConfig.get().setDirectConnectP2pEnabled(value);
                        this.safra$refreshAddressField();
                        this.safra$updateValidation();
                    })
        );

        this.safra$p2pInitialized = true;
        this.safra$refreshAddressField();
        this.safra$updateValidation();
    }

    @Inject(method = "onAddressFieldChanged", at = @At("TAIL"))
    private void safra$overrideValidation(CallbackInfo ci) {
        this.safra$updateValidation();
    }

    @Inject(method = "saveAndClose", at = @At("HEAD"))
    private void safra$storeP2pAddress(CallbackInfo ci) {
        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(this.addressField.getText())) {
            this.addressField.setText(P2pManager.toStoredAddress(this.addressField.getText()));
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void safra$storeLastP2pAddress(CallbackInfo ci) {
        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(this.addressField.getText())) {
            this.addressField.setText(P2pManager.toStoredAddress(this.addressField.getText()));
        }
    }

    @Unique
    private void safra$refreshAddressField() {
        if (this.addressField == null) {
            return;
        }
        this.addressField.setPlaceholder(this.safra$p2pEnabled
            ? Text.translatable("safra.p2p.placeholder")
            : Text.empty());
    }

    @Unique
    private void safra$updateValidation() {
        if (this.selectServerButton == null || this.addressField == null) {
            return;
        }

        String address = this.addressField.getText();
        this.selectServerButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.isValid(address);
    }

    @Unique
    private ButtonWidget safra$findSecondaryButton(ButtonWidget primaryButton) {
        ButtonWidget candidate = null;
        for (Element element : this.children()) {
            if (element instanceof ButtonWidget buttonWidget && buttonWidget != primaryButton) {
                candidate = buttonWidget;
            }
        }
        return candidate;
    }
}
