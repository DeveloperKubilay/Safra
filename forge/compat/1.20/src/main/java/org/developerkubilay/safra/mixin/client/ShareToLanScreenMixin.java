package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.ForgeButtonCompat;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.ForgeScreenCompat;
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

    @Inject(method = "init", at = @At("HEAD"))
    private void safra$loadLanSettings(CallbackInfo ci) {
        ForgeLanSessionState.loadFromConfig();
        this.safra$setField(ForgeLanSessionState.isAllowCommandsEnabled(), "commands", "f_96647_");
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            ForgeLanSessionState.loadFromConfig();
        }

        int screenWidth = this.safra$screenWidth();
        if (screenWidth <= 0) {
            return;
        }

        Minecraft minecraft = this.safra$minecraft();
        if (minecraft != null && ForgeLanGameRules.getSingleplayerServer(minecraft) != null) {
            ForgeLanSessionState.initializeGameRules(minecraft);
        }

        EditBox portEdit = this.safra$portEdit();
        if (portEdit != null) {
            safra$call(portEdit, new Class<?>[]{int.class, int.class}, new Object[]{screenWidth / 2 - 80, 156}, "setPosition", "m_305310_", "m_264495_");
            safra$call(portEdit, new Class<?>[]{int.class}, new Object[]{70}, "setWidth", "m_93674_");
        }

        this.safra$p2pButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            this.safra$getToggleText(),
            button -> {
                ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                button.setMessage(this.safra$getToggleText());
            },
            screenWidth / 2 + 2,
            156,
            78,
            20
        ));
        this.safra$onlineModeButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            this.safra$getOnlineModeText(),
            button -> {
                ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                button.setMessage(this.safra$getOnlineModeText());
            },
            screenWidth / 2 - 100,
            184,
            98,
            20
        ));
        this.safra$serverSettingsButton = this.addRenderableWidget(
            Button.builder(ForgeComponentCompat.translatable("safra.p2p.server_settings.short"), button -> this.safra$openSettingsScreen())
                .bounds(screenWidth / 2 + 2, 184, 98, 20)
                .build()
        );
        this.safra$p2pInitialized = true;
    }

    @Unique
    private Component safra$getToggleText() {
        return ForgeComponentCompat.translatable(ForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return ForgeComponentCompat.translatable(ForgeLanSessionState.isOnlineModeEnabled()
            ? "safra.p2p.online_mode.short.on"
            : "safra.p2p.online_mode.short.off");
    }

    @Unique
    private EditBox safra$portEdit() {
        return (EditBox) this.safra$getField("portEdit", "f_256803_");
    }

    @Unique
    private Minecraft safra$minecraft() {
        Object minecraft = this.safra$getField("minecraft", "f_96541_");
        return minecraft instanceof Minecraft client ? client : null;
    }

    @Unique
    private int safra$screenWidth() {
        Object width = this.safra$getField("width", "f_96543_");
        return width instanceof Integer value ? value : 0;
    }

    @Unique
    private void safra$openSettingsScreen() {
        Minecraft minecraft = this.safra$minecraft();
        if (minecraft != null) {
            minecraft.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this));
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
