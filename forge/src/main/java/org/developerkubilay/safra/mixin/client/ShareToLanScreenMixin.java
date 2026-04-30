package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
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
    private CycleButton<Boolean> safra$p2pButton;

    @Unique
    private CycleButton<Boolean> safra$onlineModeButton;

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
            CycleButton.onOffBuilder(ForgeLanSessionState.isP2pEnabled())
                .create(this.width / 2 - 100, 180, 98, 20, new TranslatableComponent("safra.p2p.toggle"), (button, value) ->
                    ForgeLanSessionState.setP2pEnabled(value))
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            CycleButton.onOffBuilder(ForgeLanSessionState.isOnlineModeEnabled())
                .create(this.width / 2 + 2, 180, 98, 20, safra$getOnlineModeText(), (button, value) -> {
                    ForgeLanSessionState.setOnlineModeEnabled(value);
                    button.setMessage(safra$getOnlineModeText());
                })
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            new Button(this.width / 2 - 100, 204, 200, 20, new TranslatableComponent("safra.p2p.server_settings.short"), button ->
                this.minecraft.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this)))
        );
        this.safra$p2pInitialized = true;
        this.safra$onlineModeButton.setMessage(safra$getOnlineModeText());
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return new TranslatableComponent(
            ForgeLanSessionState.isOnlineModeEnabled() ? "safra.p2p.online_mode.short.on" : "safra.p2p.online_mode.short.off"
        );
    }
}
