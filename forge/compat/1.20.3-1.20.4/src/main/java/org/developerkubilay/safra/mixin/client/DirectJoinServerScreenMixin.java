package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.ForgeButtonCompat;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeScreenCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
@Mixin(DirectJoinServerScreen.class)
abstract class DirectJoinServerScreenMixin extends Screen {
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
        Object ipEdit = safra$resolveIpEdit();
        Object selectButton = safra$resolveSelectButton();
        if (ipEdit == null || !(selectButton instanceof Button selectButtonWidget)) {
            return;
        }
        int screenWidth = this.safra$getScreenWidth();
        int screenHeight = this.safra$getScreenHeight();

        safra$call(ipEdit, new Class<?>[]{int.class}, new Object[]{200}, "setMaxLength", "m_94199_");
        String currentAddress = safra$getEditValue(ipEdit);
        boolean storedAddress = P2pManager.isP2pStoredAddress(currentAddress);
        boolean likelyP2pAddress = P2pManager.isLikelyP2pAddress(currentAddress);
        if (!this.safra$p2pInitialized) {
            this.safra$p2pEnabled = likelyP2pAddress || SafraClientConfig.get().isDirectConnectP2pEnabled();
        } else if (storedAddress) {
            this.safra$p2pEnabled = true;
        }
        if (storedAddress) {
            try {
                safra$setEditValue(ipEdit, P2pManager.toDisplayAddress(currentAddress));
            } catch (IllegalArgumentException ignored) {
                this.safra$p2pEnabled = false;
                safra$setEditValue(ipEdit, "");
            }
        }

        if (this.safra$p2pButton == null) {
            this.safra$p2pButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
                this.safra$getToggleText(),
                button -> {
                    this.safra$p2pEnabled = !this.safra$p2pEnabled;
                    SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);

                    if (this.safra$p2pEnabled) {
                        Object currentIpEdit = this.safra$resolveIpEdit();
                        if (currentIpEdit != null) {
                            String candidateAddress = safra$getEditValue(currentIpEdit);
                            if (candidateAddress != null && !candidateAddress.isEmpty()
                                && !P2pManager.isValidP2pAddress(candidateAddress)) {
                                safra$setEditValue(currentIpEdit, "");
                            }
                        }
                    }

                    ForgeButtonCompat.setMessage(button, this.safra$getToggleText());
                    Object currentIpEdit = this.safra$resolveIpEdit();
                    Object currentSelectButton = this.safra$resolveSelectButton();
                    this.safra$refreshAddressField(currentIpEdit);
                    if (currentSelectButton instanceof Button currentSelectButtonWidget) {
                        this.safra$updateValidation(currentIpEdit, currentSelectButtonWidget);
                    }
                },
                screenWidth / 2 - 100,
                screenHeight / 4 + 84,
                200,
                20
            ));
        } else {
            ForgeButtonCompat.setMessage(this.safra$p2pButton, this.safra$getToggleText());
            this.safra$moveButton(this.safra$p2pButton, screenWidth / 2 - 100, screenHeight / 4 + 84, 200);
        }

        this.safra$p2pInitialized = true;
        this.safra$refreshAddressField(ipEdit);
        this.safra$updateValidation(ipEdit, selectButtonWidget);
    }

    @Inject(method = "updateSelectButtonStatus", at = @At("TAIL"))
    private void safra$overrideValidation(CallbackInfo ci) {
        Object ipEdit = safra$resolveIpEdit();
        Object selectButton = safra$resolveSelectButton();
        if (ipEdit != null && selectButton instanceof Button selectButtonWidget) {
            this.safra$updateValidation(ipEdit, selectButtonWidget);
        }
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
        Object ipEdit = safra$resolveIpEdit();
        Object serverData = safra$resolveServerData();
        if (ipEdit == null || serverData == null) {
            return;
        }

        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String address = safra$getEditValue(ipEdit);
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }
        ForgeVersionCompat.setServerAddress((net.minecraft.client.multiplayer.ServerData) serverData, address);
    }

    @Unique
    private Component safra$getToggleText() {
        return ForgeComponentCompat.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField(Object ipEdit) {
        if (ipEdit == null) {
            return;
        }
        safra$call(ipEdit, new Class<?>[]{Component.class}, new Object[]{
            this.safra$p2pEnabled ? ForgeComponentCompat.translatable("safra.p2p.placeholder") : ForgeComponentCompat.empty()
        }, "setHint", "m_257771_");
    }

    @Unique
    private void safra$updateValidation(Object ipEdit, Button selectButton) {
        if (selectButton == null || ipEdit == null) {
            return;
        }

        String address = safra$getEditValue(ipEdit);
        safra$setButtonActive(selectButton, this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : safra$isValidServerAddress(address));
    }

    @Unique
    private void safra$moveButton(Button button, int x, int y, int width) {
        safra$call(button, new Class<?>[]{int.class}, new Object[]{width}, "setWidth", "m_93674_");
        safra$call(button, new Class<?>[]{int.class, int.class}, new Object[]{x, y}, "setPosition", "m_305310_");
    }

    @Unique
    private int safra$getScreenWidth() {
        Object value = safra$getScreenDimension("width", "f_96543_");
        return value instanceof Integer width ? width : 0;
    }

    @Unique
    private int safra$getScreenHeight() {
        Object value = safra$getScreenDimension("height", "f_96544_");
        return value instanceof Integer height ? height : 0;
    }

    @Unique
    private Object safra$getScreenDimension(String... names) {
        Class<?> type = this.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(this);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    @Unique
    private static boolean safra$isValidServerAddress(String address) {
        return address != null && !address.trim().isEmpty() && !address.contains(" ");
    }

    @Unique
    private static void safra$setButtonActive(Button button, boolean active) {
        Class<?> type = button.getClass();
        while (type != null) {
            for (String name : new String[]{"active", "f_93623_"}) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setBoolean(button, active);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    @Unique
    private Object safra$resolveIpEdit() {
        return safra$findNamedFieldValue(
            "net.minecraft.client.gui.components.EditBox",
            "ipEdit", "f_95698_", "f_95539_", "f_169369_", "f_242994_"
        );
    }

    @Unique
    private Object safra$resolveSelectButton() {
        Object namedButton = safra$findNamedFieldValue(
            "net.minecraft.client.gui.components.Button",
            "selectButton", "addButton", "joinButton", "f_95990_", "f_95991_"
        );
        if (namedButton != null) {
            return namedButton;
        }
        return safra$findLargestButtonFieldValue();
    }

    @Unique
    private Object safra$resolveServerData() {
        return safra$findNamedFieldValue(
            "net.minecraft.client.multiplayer.ServerData",
            "serverData", "f_95992_", "f_242995_"
        );
    }

    @Unique
    private Object safra$findNamedFieldValue(String expectedTypeName, String... preferredNames) {
        for (String name : preferredNames) {
            try {
                Field field = this.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(this);
                if (value != null && value.getClass().getName().equals(expectedTypeName)) {
                    return value;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (Field field : this.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(this);
                if (value != null && value.getClass().getName().equals(expectedTypeName)) {
                    return value;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    @Unique
    private Object safra$findLargestButtonFieldValue() {
        Field buttonCandidate = null;
        int candidateWidth = Integer.MIN_VALUE;
        for (Field field : this.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(this);
                if (!(value instanceof Button button) || button == this.safra$p2pButton) {
                    continue;
                }
                if (buttonCandidate == null || button.getWidth() > candidateWidth) {
                    buttonCandidate = field;
                    candidateWidth = button.getWidth();
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        if (buttonCandidate != null) {
            try {
                buttonCandidate.setAccessible(true);
                return buttonCandidate.get(this);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        return null;
    }

    @Unique
    private static String safra$getEditValue(Object editBox) {
        Object value = safra$call(editBox, new Class<?>[0], new Object[0], "getValue", "m_94155_");
        return value instanceof String text ? text : "";
    }

    @Unique
    private static void safra$setEditValue(Object editBox, String value) {
        safra$call(editBox, new Class<?>[]{String.class}, new Object[]{value}, "setValue", "m_94144_");
    }

    @Unique
    private static Object safra$call(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
        for (String name : names) {
            try {
                Method method = type.getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(target instanceof Class<?> ? null : target, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
