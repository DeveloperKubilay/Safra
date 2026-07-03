package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.AddServerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.ForgeScreenCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = AddServerScreen.class, remap = false)
abstract class DirectJoinServerScreenMixin extends Screen {
    @Unique
    private Button safra$p2pButton;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected DirectJoinServerScreenMixin(ITextComponent title) {
        super(title);
    }

    @Inject(method = {"init", "func_231160_c_"}, at = @At("TAIL"), remap = false)
    private void safra$initP2pUi(CallbackInfo ci) {
        TextFieldWidget addressField = this.safra$getAddressField();
        if (addressField == null) {
            return;
        }

        addressField.setMaxStringLength(200);
        String currentAddress = addressField.getText();
        boolean storedAddress = P2pManager.isP2pStoredAddress(currentAddress);
        boolean likelyP2pAddress = P2pManager.isLikelyP2pAddress(currentAddress);
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = likelyP2pAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            addressField.setText(P2pManager.toDisplayAddress(addressField.getText()));
        }

        Button primaryButton = this.safra$getPrimaryAddButton();
        Button cancelButton = this.safra$findSecondaryButton(primaryButton);
        int width = ForgeScreenCompat.getWidth(this);
        int height = ForgeScreenCompat.getHeight(this);
        if (primaryButton != null && cancelButton != null) {
            ForgeScreenCompat.setButtonWidth(primaryButton, 98);
            ForgeScreenCompat.setButtonX(primaryButton, width / 2 - 100);
            ForgeScreenCompat.setButtonY(primaryButton, height / 4 + 108);
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

                TextFieldWidget refreshedAddressField = this.safra$getAddressField();
                if (this.safra$p2pEnabled && refreshedAddressField != null) {
                    String typedAddress = refreshedAddressField.getText();
                    if (typedAddress != null && !typedAddress.isEmpty()
                        && !P2pManager.isValidP2pAddress(typedAddress)) {
                        refreshedAddressField.setText("");
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

    @Inject(method = {"onButtonServerAddPressed", "func_195172_h"}, at = @At("HEAD"), remap = false)
    private void safra$storeP2pAddress(CallbackInfo ci) {
        this.safra$persistStoredAddress();
    }

    @Unique
    private void safra$persistStoredAddress() {
        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        TextFieldWidget addressField = this.safra$getAddressField();
        if (addressField == null) {
            return;
        }
        String address = addressField.getText();
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        addressField.setText(address);
        ServerData serverData = this.safra$getServerData();
        if (serverData != null) {
            serverData.serverIP = address;
        }
    }

    @Unique
    private ITextComponent safra$getToggleText() {
        return new TranslationTextComponent(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        TextFieldWidget addressField = this.safra$getAddressField();
        if (addressField == null) {
            return;
        }
        addressField.setSuggestion(null);
    }

    @Unique
    private void safra$updateValidation() {
        Button primaryButton = this.safra$getPrimaryAddButton();
        TextFieldWidget addressField = this.safra$getAddressField();
        if (primaryButton == null || addressField == null) {
            return;
        }

        String address = addressField.getText();
        ForgeScreenCompat.setButtonActive(
            primaryButton,
            this.safra$p2pEnabled
                ? P2pManager.isValidP2pAddress(address)
                : this.safra$isVanillaAddressValid(address)
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

    @Unique
    private Button safra$getPrimaryAddButton() {
        Button fallback = null;
        String cancelText = new TranslationTextComponent("gui.cancel").getString();
        for (IGuiEventListener element : ForgeScreenCompat.getChildren(this)) {
            if (!(element instanceof Button)) {
                continue;
            }
            Button button = (Button) element;
            if (button == this.safra$p2pButton) {
                continue;
            }
            String label = button.getMessage() == null ? "" : button.getMessage().getString();
            if (cancelText.equals(label)) {
                continue;
            }
            if (fallback == null) {
                fallback = button;
            }
            if (label != null && !label.trim().isEmpty()) {
                return button;
            }
        }
        return fallback;
    }

    @Unique
    private TextFieldWidget safra$getAddressField() {
        Object fieldValue = this.safra$getFieldValue("textFieldServerAddress", "field_146302_g");
        if (fieldValue instanceof TextFieldWidget) {
            return (TextFieldWidget) fieldValue;
        }

        for (IGuiEventListener element : ForgeScreenCompat.getChildren(this)) {
            if (element instanceof TextFieldWidget) {
                return (TextFieldWidget) element;
            }
        }
        return null;
    }

    @Unique
    private ServerData safra$getServerData() {
        Object fieldValue = this.safra$getFieldValue("serverData", "field_146374_i");
        return fieldValue instanceof ServerData ? (ServerData) fieldValue : null;
    }

    @Unique
    private Object safra$getFieldValue(String... names) {
        for (String name : names) {
            Field field = this.safra$findField(name);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(this);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @Unique
    private Field safra$findField(String name) {
        Class<?> current = this.getClass();
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @Unique
    private boolean safra$isVanillaAddressValid(String address) {
        return address != null && !address.trim().isEmpty() && address.indexOf(' ') < 0;
    }
}
