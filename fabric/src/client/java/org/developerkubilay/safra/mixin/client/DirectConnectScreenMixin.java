package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.DirectConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
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

    @Shadow
    @Final
    private ServerInfo serverEntry;

    @Unique
    private ButtonWidget safra$p2pToggle;

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
        String currentAddress = this.addressField.getText();
        boolean storedAddress = P2pManager.isP2pStoredAddress(currentAddress);
        boolean likelyP2pAddress = P2pManager.isLikelyP2pAddress(currentAddress);
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = likelyP2pAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            this.addressField.setText(P2pManager.toDisplayAddress(this.addressField.getText()));
        }

        ButtonWidget cancelButton = this.safra$findSecondaryButton(this.selectServerButton);
        if (cancelButton != null) {
            this.selectServerButton.setWidth(98);
            this.selectServerButton.x = this.width / 2 - 100;
            this.selectServerButton.y = this.height / 4 + 108;
            cancelButton.setWidth(98);
            cancelButton.x = this.width / 2 + 2;
            cancelButton.y = this.height / 4 + 108;
        }

        this.safra$p2pToggle = this.addButton(
            new ButtonWidget(
                this.width / 2 - 100,
                this.height / 4 + 84,
                200,
                20,
                this.safra$getToggleText(),
                button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);

                if (this.safra$p2pEnabled && this.addressField != null) {
                        String typedAddress = this.addressField.getText();
                        if (typedAddress != null && !typedAddress.isEmpty()
                            && !P2pManager.isValidP2pAddress(typedAddress)) {
                            this.addressField.setText("");
                        }
                    }

                    button.setMessage(this.safra$getToggleText());
                    this.safra$refreshAddressField();
                    this.safra$updateValidation();
                }
            )
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
        this.safra$persistStoredAddress();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void safra$storeLastP2pAddress(CallbackInfo ci) {
        this.safra$persistStoredAddress();
    }

    @Unique
    private void safra$persistStoredAddress() {
        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = this.addressField.getText();
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        this.addressField.setText(address);
        this.serverEntry.address = address;
    }

    @Unique
    private void safra$refreshAddressField() {
        if (this.addressField == null) {
            return;
        }
        this.addressField.setSuggestion(null);
    }

    @Unique
    private void safra$updateValidation() {
        if (this.selectServerButton == null || this.addressField == null) {
            return;
        }

        String address = this.addressField.getText();
        this.selectServerButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : safra$isValidDirectAddress(address);
    }

    @Unique
    private boolean safra$isValidDirectAddress(String address) {
        if (address == null) {
            return false;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int separator = trimmed.lastIndexOf(':');
        if (separator < 0) {
            return true;
        }
        if (separator == trimmed.length() - 1) {
            return false;
        }
        try {
            int port = Integer.parseInt(trimmed.substring(separator + 1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    @Unique
    private ButtonWidget safra$findSecondaryButton(ButtonWidget primaryButton) {
        ButtonWidget candidate = null;
        for (Element element : this.children()) {
            if (element instanceof ButtonWidget) {
                ButtonWidget buttonWidget = (ButtonWidget) element;
                if (buttonWidget == primaryButton) {
                    continue;
                }
                candidate = buttonWidget;
            }
        }
        return candidate;
    }

    @Unique
    private TranslatableText safra$getToggleText() {
        return new TranslatableText(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }
}
