package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ServerListScreen;
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

@Mixin(value = ServerListScreen.class, remap = false)
abstract class DirectConnectServerScreenMixin extends Screen {
    @Unique
    private Button safra$selectButton;

    @Unique
    private Button safra$p2pButton;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected DirectConnectServerScreenMixin(ITextComponent title) {
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

        this.safra$selectButton = this.safra$getSelectButton();
        int width = ForgeScreenCompat.getWidth(this);
        int height = ForgeScreenCompat.getHeight(this);

        this.safra$p2pButton = ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            height / 4 + 84,
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

    @Inject(method = {"func_213024_a", "onAddressFieldChanged"}, at = @At("TAIL"), remap = false)
    private void safra$overrideValidation(String value, CallbackInfo ci) {
        this.safra$updateValidation();
    }

    @Inject(method = {"func_195167_h", "saveAndClose"}, at = @At("HEAD"), remap = false)
    private void safra$storeP2pAddress(CallbackInfo ci) {
        this.safra$persistStoredAddress();
    }

    @Inject(method = {"removed", "func_231175_as__"}, at = @At("HEAD"), remap = false)
    private void safra$storeLastP2pAddress(CallbackInfo ci) {
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
    private void safra$refreshAddressField() {
        TextFieldWidget addressField = this.safra$getAddressField();
        if (addressField != null) {
            ForgeScreenCompat.setTextFieldSuggestion(addressField, null);
        }
    }

    @Unique
    private void safra$updateValidation() {
        if (this.safra$selectButton == null) {
            return;
        }
        TextFieldWidget addressField = this.safra$getAddressField();
        if (addressField == null) {
            return;
        }

        String address = addressField.getText();
        ForgeScreenCompat.setButtonActive(
            this.safra$selectButton,
            this.safra$p2pEnabled
                ? P2pManager.isValidP2pAddress(address)
                : this.safra$isValidDirectAddress(address)
        );
    }

    @Unique
    private Button safra$getSelectButton() {
        Object fieldValue = this.safra$getFieldValue("selectServerButton", "field_195170_a");
        return fieldValue instanceof Button ? (Button) fieldValue : null;
    }

    @Unique
    private TextFieldWidget safra$getAddressField() {
        Object fieldValue = this.safra$getFieldValue("addressField", "field_146302_g");
        return fieldValue instanceof TextFieldWidget ? (TextFieldWidget) fieldValue : null;
    }

    @Unique
    private ServerData safra$getServerData() {
        Object fieldValue = this.safra$getFieldValue("serverEntry", "field_146301_f");
        return fieldValue instanceof ServerData ? (ServerData) fieldValue : null;
    }

    @Unique
    private ITextComponent safra$getToggleText() {
        return new TranslationTextComponent(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
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
}
