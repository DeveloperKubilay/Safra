package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.ForgeButtonCompat;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeScreenCompat;
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
    private boolean safra$p2pEnabled = true;

    @Unique
    private boolean safra$p2pInitialized;

    @Unique
    private boolean safra$syncingAddress;

    protected DirectJoinServerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
    private void safra$initP2pUi(CallbackInfo ci) {
        EditBox ipEdit = this.safra$ipEdit();
        Button selectButton = this.safra$selectButton();
        if (ipEdit == null || selectButton == null) {
            return;
        }

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
            safra$setEditValue(ipEdit, P2pManager.toDisplayAddress(currentAddress));
        }

        this.safra$p2pButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            this.safra$getToggleText(),
            button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
                ForgeButtonCompat.setMessage(button, this.safra$getToggleText());
                this.safra$refreshAddressField();
                this.safra$updateValidation();
            },
            this.safra$screenWidth() / 2 - 100,
            this.safra$screenHeight() / 4 + 84,
            200,
            20
        ));

        this.safra$p2pInitialized = true;
        this.safra$refreshAddressField();
        this.safra$updateValidation();
    }

    @Inject(method = "m_95986_", at = @At("TAIL"), remap = false)
    private void safra$overrideValidation(CallbackInfo ci) {
        this.safra$updateValidation();
    }

    @Inject(method = "m_95987_", at = @At("HEAD"), remap = false)
    private void safra$storeP2pAddress(CallbackInfo ci) {
        this.safra$persistStoredAddress();
    }

    @Inject(method = "m_7379_", at = @At("HEAD"), remap = false)
    private void safra$storeLastP2pAddress(CallbackInfo ci) {
        this.safra$persistStoredAddress();
    }

    @Unique
    private void safra$persistStoredAddress() {
        if (this.safra$syncingAddress || !this.safra$p2pInitialized) {
            return;
        }

        EditBox ipEdit = this.safra$ipEdit();
        ServerData serverData = this.safra$serverData();
        if (ipEdit == null || serverData == null) {
            return;
        }

        SafraClientConfig.get().setDirectConnectP2pEnabled(this.safra$p2pEnabled);
        String currentValue = safra$getEditValue(ipEdit);
        String address = currentValue;
        if (this.safra$p2pEnabled && P2pManager.isValidP2pAddress(address)) {
            address = P2pManager.toStoredAddress(address);
        }

        if (!address.equals(currentValue)) {
            this.safra$syncingAddress = true;
            try {
                safra$setEditValue(ipEdit, address);
            } finally {
                this.safra$syncingAddress = false;
            }
        }
        safra$setServerAddress(serverData, address);
    }

    @Unique
    private Component safra$getToggleText() {
        return ForgeComponentCompat.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private void safra$refreshAddressField() {
        EditBox ipEdit = this.safra$ipEdit();
        if (ipEdit == null) {
            return;
        }
        safra$call(ipEdit, new Class<?>[]{Component.class}, new Object[]{
            this.safra$p2pEnabled ? ForgeComponentCompat.translatable("safra.p2p.placeholder") : ForgeComponentCompat.empty()
        }, "setHint", "m_257771_");
    }

    @Unique
    private void safra$updateValidation() {
        Button selectButton = this.safra$selectButton();
        EditBox ipEdit = this.safra$ipEdit();
        if (selectButton == null || ipEdit == null) {
            return;
        }

        String address = safra$getEditValue(ipEdit);
        safra$setButtonActive(selectButton, this.safra$p2pEnabled
            ? P2pManager.isValidP2pAddress(address)
            : safra$isValidServerAddress(address));
    }

    @Unique
    private Button safra$selectButton() {
        return (Button) this.safra$getField("selectButton", "f_95953_");
    }

    @Unique
    private EditBox safra$ipEdit() {
        return (EditBox) this.safra$getField("ipEdit", "f_95955_");
    }

    @Unique
    private ServerData safra$serverData() {
        return (ServerData) this.safra$getField("serverData", "f_95954_");
    }

    @Unique
    private int safra$screenWidth() {
        Object width = this.safra$getField("width", "f_96543_");
        return width instanceof Integer value ? value : 0;
    }

    @Unique
    private int safra$screenHeight() {
        Object height = this.safra$getField("height", "f_96544_");
        return height instanceof Integer value ? value : 0;
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
    private static void safra$setServerAddress(ServerData serverData, String address) {
        Class<?> type = serverData.getClass();
        while (type != null) {
            for (String name : new String[]{"ip", "f_105363_"}) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(serverData, address);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    @Unique
    private Object safra$getField(String... names) {
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
    private static String safra$getEditValue(EditBox editBox) {
        Object value = safra$call(editBox, new Class<?>[0], new Object[0], "getValue", "m_94155_");
        return value instanceof String text ? text : "";
    }

    @Unique
    private static void safra$setEditValue(EditBox editBox, String value) {
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
