package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.FabricClientCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AddServerScreen.class)
abstract class AddServerScreenMixin extends Screen {
    @Shadow private ButtonWidget addButton;
    @Shadow private TextFieldWidget addressField;
    @Shadow private TextFieldWidget serverNameField;
    @Shadow @Final private ServerInfo server;

    @Unique private boolean safra$p2pEnabled;
    @Unique private boolean safra$p2pInitialized;

    protected AddServerScreenMixin(Text title) {
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
            this.addressField.setText(P2pManager.toDisplayAddress(currentAddress));
        }

        this.addressField.setWidth(122);

        this.addDrawableChild(FabricClientCompat.createButton(this.width / 2 + 30, 106, 70, 20, this.safra$getToggleText(), button -> {
                    this.safra$p2pEnabled = !this.safra$p2pEnabled;
                    SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);

                    if (this.safra$p2pEnabled) {
                        String typedAddress = this.addressField.getText();
                        if (typedAddress != null && !typedAddress.isEmpty()
                            && !P2pManager.isValidP2pAddress(typedAddress)) {
                            this.addressField.setText("");
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

    @Inject(method = "updateAddButton", at = @At("TAIL"))
    private void safra$overrideValidation(CallbackInfo ci) {
        this.safra$updateValidation();
    }

    @Inject(method = "addAndClose", at = @At("HEAD"))
    private void safra$storeP2pAddress(CallbackInfo ci) {
        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = this.addressField.getText();
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        this.addressField.setText(address);
        this.server.address = address;
    }

    @Unique
    private Text safra$getToggleText() {
        return FabricClientCompat.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
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
        if (this.addButton == null || this.addressField == null || this.serverNameField == null) {
            return;
        }

        String address = this.addressField.getText();
        this.addButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.isValid(address) && !this.serverNameField.getText().isEmpty();
    }
}
