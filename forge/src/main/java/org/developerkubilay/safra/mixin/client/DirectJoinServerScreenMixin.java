package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ServerListScreen;
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

@Mixin(ServerListScreen.class)
abstract class DirectJoinServerScreenMixin extends Screen {
    @Shadow
    private Button selectButton;

    @Shadow
    private TextFieldWidget ipEdit;

    @Shadow
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
            this.safra$moveButton(this.selectButton, this.width / 2 - 100, this.height / 4 + 108, 98);
            this.safra$moveButton(cancelButton, this.width / 2 + 2, this.height / 4 + 108, 98);
        }

        this.safra$p2pButton = this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 84,
            200,
            20,
            this.safra$getToggleText(),
            button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
                button.setMessage(this.safra$getToggleText());
                this.safra$refreshAddressField();
                this.safra$updateValidation();
            }
        ));

        this.safra$p2pInitialized = true;
        this.safra$refreshAddressField();
        this.safra$updateValidation();
    }

    @Inject(method = "tick", at = @At("TAIL"))
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
        this.ipEdit.setSuggestion(this.safra$p2pEnabled
            ? new TranslationTextComponent("safra.p2p.placeholder").getString()
            : null);
    }

    @Unique
    private void safra$updateValidation() {
        if (this.selectButton == null || this.ipEdit == null) {
            return;
        }

        String address = this.ipEdit.getValue();
        this.selectButton.active = this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : this.safra$isValidVanillaAddress(address);
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

    @Unique
    private void safra$moveButton(Button button, int x, int y, int width) {
        button.setWidth(width);
        button.x = x;
        button.y = y;
    }

    @Unique
    private String safra$getToggleText() {
        return new TranslationTextComponent(
            this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off"
        ).getString();
    }

    @Unique
    private boolean safra$isValidVanillaAddress(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        try {
            ServerAddress parsed = ServerAddress.parseString(address);
            return parsed.getHost() != null && !parsed.getHost().isEmpty() && parsed.getPort() > 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
