package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.NeoForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.NeoForgeLanSessionState;
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
    private EditBox portEdit;

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
        NeoForgeLanSessionState.loadFromConfig();
        this.commands = NeoForgeLanSessionState.isAllowCommandsEnabled();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            NeoForgeLanSessionState.loadFromConfig();
        }
        if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
            NeoForgeLanSessionState.initializeGameRules(NeoForgeLanGameRules.serialize(this.minecraft.getSingleplayerServer().overworld().getGameRules()));
        }

        this.portEdit.setPosition(this.width / 2 - 80, 156);
        this.portEdit.setWidth(70);
        this.safra$p2pButton = this.addRenderableWidget(
            Button.builder(this.safra$getToggleText(), button -> {
                    NeoForgeLanSessionState.setP2pEnabled(!NeoForgeLanSessionState.isP2pEnabled());
                    button.setMessage(this.safra$getToggleText());
                })
                .bounds(this.width / 2 - 5, 156, 85, 20)
                .build()
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            Button.builder(this.safra$getOnlineModeText(), button -> {
                    NeoForgeLanSessionState.setOnlineModeEnabled(!NeoForgeLanSessionState.isOnlineModeEnabled());
                    button.setMessage(this.safra$getOnlineModeText());
                })
                .bounds(this.width / 2 - 100, 180, 98, 20)
                .build()
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            Button.builder(Component.translatable("safra.p2p.server_settings.short"), button ->
                    this.minecraft.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this)))
                .bounds(this.width / 2 + 2, 180, 98, 20)
                .build()
        );
        this.safra$p2pInitialized = true;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void safra$renderHint(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        guiGraphics.drawCenteredString(
            this.font,
            Component.translatable("safra.p2p.open_hint"),
            this.width / 2,
            232,
            0xA0A0A0
        );
    }

    @Unique
    private Component safra$getToggleText() {
        return Component.translatable(NeoForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return Component.translatable(NeoForgeLanSessionState.isOnlineModeEnabled()
            ? "safra.p2p.online_mode.short.on"
            : "safra.p2p.online_mode.short.off");
    }
}
