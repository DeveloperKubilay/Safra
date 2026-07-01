package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.ForgeScreenCompat;
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
        String currentAddress = this.textFieldServerAddress.getText();
        boolean storedAddress = P2pManager.isP2pStoredAddress(currentAddress);
        boolean likelyP2pAddress = P2pManager.isLikelyP2pAddress(currentAddress);
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = likelyP2pAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            this.textFieldServerAddress.setText(P2pManager.toDisplayAddress(this.textFieldServerAddress.getText()));
        }

        Button cancelButton = this.safra$findSecondaryButton(this.buttonAddServer);
        int width = ForgeScreenCompat.getWidth(this);
        int height = ForgeScreenCompat.getHeight(this);
        if (cancelButton != null) {
            ForgeScreenCompat.setButtonWidth(this.buttonAddServer, 98);
            ForgeScreenCompat.setButtonX(this.buttonAddServer, width / 2 - 100);
            ForgeScreenCompat.setButtonY(this.buttonAddServer, height / 4 + 108);
            ForgeScreenCompat.setButtonWidth(cancelButton, 98);
            ForgeScreenCompat.setButtonX(cancelButton, width / 2 + 2);
            ForgeScreenCompat.setButtonY(cancelButton, height / 4 + 108);
        }

        this.safra$p2pButton = ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            height / 4 + 132,
            200,
            20,
            this.safra$getToggleText(),
            button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);

                if (this.safra$p2pEnabled && this.textFieldServerAddress != null) {
                    String typedAddress = this.textFieldServerAddress.getText();
                    if (typedAddress != null && !typedAddress.isEmpty()
                        && !P2pManager.isValidP2pAddress(typedAddress)) {
                        this.textFieldServerAddress.setText("");
                    }
                }

                ForgeScreenCompat.setButtonMessage(button, this.safra$getToggleText());
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
    private ITextComponent safra$getToggleText() {
        return new TranslationTextComponent(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        if (this.textFieldServerAddress == null) {
            return;
        }
        this.textFieldServerAddress.setSuggestion(null);
    }

    @Unique
    private void safra$updateValidation() {
        if (this.buttonAddServer == null || this.textFieldServerAddress == null) {
            return;
        }

        String address = this.textFieldServerAddress.getText();
        ForgeScreenCompat.setButtonActive(
            this.buttonAddServer,
            this.safra$p2pEnabled
                ? P2pManager.isValidP2pAddress(address)
                : ServerAddress.fromString(address) != null
        );
    }

    @Unique
    private Button safra$findSecondaryButton(Button primaryButton) {
        Button candidate = null;
        for (IGuiEventListener element : ForgeScreenCompat.getChildren(this)) {
            if (element instanceof Button) {
                Button button = (Button) element;
                if (button != primaryButton) {
                    candidate = button;
                }
            }
        }
        return candidate;
    }
}
