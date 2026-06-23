package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AddServerScreen.class, remap = false)
abstract class DirectJoinServerScreenMixin extends Screen {
    @Shadow(remap = false)
    private Button buttonAddServer;

    @Shadow(remap = false)
    private TextFieldWidget textFieldServerAddress;

    @Shadow(remap = false)
    @Final
    private ServerData serverData;

    @Unique
    private Button safra$p2pButton;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected DirectJoinServerScreenMixin(ITextComponent title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void safra$initP2pUi(CallbackInfo ci) {
        this.textFieldServerAddress.setMaxStringLength(200);
        boolean storedAddress = P2pManager.isP2pStoredAddress(this.textFieldServerAddress.getText());
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = storedAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            this.textFieldServerAddress.setText(P2pManager.toDisplayAddress(this.textFieldServerAddress.getText()));
        }

        Button cancelButton = this.safra$findSecondaryButton(this.buttonAddServer);
        if (cancelButton != null) {
            this.buttonAddServer.setWidth(98);
            this.buttonAddServer.x = this.width / 2 - 100;
            this.buttonAddServer.y = this.height / 4 + 108;
            cancelButton.setWidth(98);
            cancelButton.x = this.width / 2 + 2;
            cancelButton.y = this.height / 4 + 108;
        }

        this.safra$p2pButton = this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 132,
            200,
            20,
            this.safra$getToggleText().getFormattedText(),
            button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);

                if (this.safra$p2pEnabled && this.textFieldServerAddress != null) {
                    String currentAddress = this.textFieldServerAddress.getText();
                    if (currentAddress != null && !currentAddress.isEmpty()
                        && !P2pManager.isValidP2pAddress(currentAddress)) {
                        this.textFieldServerAddress.setText("");
                    }
                }

                button.setMessage(this.safra$getToggleText().getFormattedText());
                this.safra$refreshAddressField();
                this.safra$updateValidation();
            }
        ));

        this.safra$p2pInitialized = true;
        this.safra$refreshAddressField();
        this.safra$updateValidation();
    }

    @Inject(method = "func_228180_b_", at = @At("TAIL"), remap = false)
    private void safra$overrideValidation(CallbackInfo ci) {
        this.safra$updateValidation();
    }

    @Inject(method = "onButtonServerAddPressed", at = @At("HEAD"), remap = false)
    private void safra$storeP2pAddress(CallbackInfo ci) {
        this.safra$persistStoredAddress();
    }

    @Unique
    private void safra$persistStoredAddress() {
        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = this.textFieldServerAddress.getText();
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        this.textFieldServerAddress.setText(address);
        this.serverData.serverIP = address;
    }

    @Unique
    private void safra$refreshAddressField() {
        if (this.textFieldServerAddress == null) {
            return;
        }
        this.textFieldServerAddress.setSuggestion(this.safra$p2pEnabled ? "Safra code" : null);
    }

    @Unique
    private void safra$updateValidation() {
        if (this.buttonAddServer == null || this.textFieldServerAddress == null) {
            return;
        }

        String address = this.textFieldServerAddress.getText();
        this.buttonAddServer.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : ServerAddress.fromString(address) != null;
    }

    @Unique
    private Button safra$findSecondaryButton(Button primaryButton) {
        Button candidate = null;
        for (IGuiEventListener element : this.children()) {
            if (element instanceof Button && element != primaryButton) {
                candidate = (Button) element;
            }
        }
        return candidate;
    }

    private ITextComponent safra$getToggleText() {
        return new TranslationTextComponent(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }
}
