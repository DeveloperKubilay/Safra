package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.config.SafraClientConfig;
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
    private Button commandsButton;

    @Shadow
    private boolean commands;

    @Shadow
    private void updateSelectionStrings() {
    }

    @Unique
    private Button safra$p2pButton;

    @Unique
    private Button safra$onlineModeButton;

    @Unique
    private Button safra$serverSettingsButton;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$onlineModeEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected ShareToLanScreenMixin(ITextComponent title) {
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
            SafraClientConfig config = SafraClientConfig.get();
            this.safra$p2pEnabled = config.isOpenToLanP2pEnabled();
            this.safra$onlineModeEnabled = config.isOpenToLanOnlineModeEnabled();
        }
        if (this.minecraft != null) {
            ForgeLanSessionState.initializeGameRules(this.minecraft);
        }

        this.safra$p2pButton = this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 72,
            98,
            20,
            this.safra$getToggleText(),
            button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                ForgeLanSessionState.setP2pEnabled(this.safra$p2pEnabled);
                button.setMessage(this.safra$getToggleText());
            }
        ));
        this.safra$onlineModeButton = this.addButton(new Button(
            this.width / 2 + 2,
            this.height / 4 + 72,
            98,
            20,
            this.safra$getOnlineModeText(),
            button -> {
                this.safra$onlineModeEnabled = !this.safra$onlineModeEnabled;
                ForgeLanSessionState.setOnlineModeEnabled(this.safra$onlineModeEnabled);
                button.setMessage(this.safra$getOnlineModeText());
            }
        ));
        this.safra$serverSettingsButton = this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 96,
            200,
            20,
            new TranslationTextComponent("safra.p2p.server_settings.short").getString(),
            button -> {
                if (this.minecraft != null) {
                    this.minecraft.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this));
                }
            }
        ));
        this.safra$p2pInitialized = true;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void safra$syncAllowCommands(int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        boolean allowCommands = ForgeLanSessionState.isAllowCommandsEnabled();
        if (this.commands != allowCommands) {
            this.commands = allowCommands;
            if (this.commandsButton != null) {
                this.updateSelectionStrings();
            }
        }
    }

    @Unique
    private String safra$getToggleText() {
        return new TranslationTextComponent(
            this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off"
        ).getString();
    }

    @Unique
    private String safra$getOnlineModeText() {
        return new TranslationTextComponent(
            this.safra$onlineModeEnabled ? "safra.p2p.online_mode.short.on" : "safra.p2p.online_mode.short.off"
        ).getString();
    }
}
