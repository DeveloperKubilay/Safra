package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(ShareToLanScreen.class)
abstract class ShareToLanScreenMixin extends Screen {
    @Unique
    private Button safra$p2pButton;

    @Unique
    private Button safra$onlineModeButton;

    @Unique
    private Button safra$serverSettingsButton;

    @Unique
    private boolean safra$p2pInitialized;

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "m_7856_", at = @At("HEAD"), remap = false)
    private void safra$loadLanSettings(CallbackInfo ci) {
        ForgeLanSessionState.loadFromConfig();
        this.safra$setField(ForgeLanSessionState.isAllowCommandsEnabled(), "commands", "f_96647_");
    }

    @Inject(method = "m_7856_", at = @At("TAIL"), remap = false)
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            ForgeLanSessionState.loadFromConfig();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            ForgeLanSessionState.initializeGameRules(minecraft);
        }

        EditBox portEdit = this.safra$portEdit();
        if (portEdit != null) {
            safra$call(portEdit, new Class<?>[]{int.class, int.class}, new Object[]{this.width / 2 - 80, 156}, "setPosition", "m_305310_");
            safra$call(portEdit, new Class<?>[]{int.class}, new Object[]{70}, "setWidth", "m_93674_");
        }
        this.safra$p2pButton = this.addRenderableWidget(
            Button.builder(this.safra$getToggleText(), button -> {
                    ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                    button.setMessage(this.safra$getToggleText());
                })
                .bounds(this.width / 2 - 5, 156, 85, 20)
                .build()
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            Button.builder(this.safra$getOnlineModeText(), button -> {
                    ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                    button.setMessage(this.safra$getOnlineModeText());
                })
                .bounds(this.width / 2 - 100, 180, 98, 20)
                .build()
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            Button.builder(Component.translatable("safra.p2p.server_settings.short"), button ->
                    Minecraft.getInstance().setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this)))
                .bounds(this.width / 2 + 2, 180, 98, 20)
                .build()
        );
        this.safra$p2pInitialized = true;
    }

    @Unique
    private Component safra$getToggleText() {
        return Component.translatable(ForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return Component.translatable(ForgeLanSessionState.isOnlineModeEnabled()
            ? "safra.p2p.online_mode.short.on"
            : "safra.p2p.online_mode.short.off");
    }

    @Unique
    private EditBox safra$portEdit() {
        return (EditBox) this.safra$getField("portEdit", "f_256803_");
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
    private void safra$setField(Object value, String... names) {
        Class<?> type = this.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(this, value);
                    return;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    @Unique
    private static Object safra$call(Object target, Class<?>[] parameterTypes, Object[] args, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
