package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShareToLanScreen.class)
abstract class ShareToLanScreenMixin extends Screen {
    @Shadow
    private boolean commands;

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
        this.commands = ForgeLanSessionState.isAllowCommandsEnabled();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            ForgeLanSessionState.loadFromConfig();
        }
        if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
            ForgeLanSessionState.initializeGameRules(this.minecraft);
        }

        this.safra$p2pButton = this.addRenderableWidget(
            new Button(this.width / 2 - 100, 148, 98, 20, this.safra$getToggleText(), button -> {
                ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                button.setMessage(this.safra$getToggleText());
            })
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            new Button(this.width / 2 + 2, 148, 98, 20, this.safra$getOnlineModeText(), button -> {
                ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                button.setMessage(this.safra$getOnlineModeText());
            })
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            new Button(this.width / 2 - 49, 172, 98, 20, Component.translatable("safra.p2p.server_settings.short"), button ->
                this.minecraft.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this)))
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
}
