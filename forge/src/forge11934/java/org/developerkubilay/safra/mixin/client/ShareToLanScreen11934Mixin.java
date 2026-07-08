package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.ForgeClientCompat;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShareToLanScreen.class)
abstract class ShareToLanScreen11934Mixin extends Screen {
    @Shadow
    @Final
    @Mutable
    private static Component PORT_INFO_TEXT;

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

    @Unique
    private boolean safra$hidePortUi;

    protected ShareToLanScreen11934Mixin(Component title) {
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
        this.safra$hidePortUi = this.safra$hidePortInput();
        if (this.safra$hidePortUi) {
            this.safra$clearPortLabel();
        }
        int controlsY = 148;

        this.safra$p2pButton = this.addRenderableWidget(
            ForgeClientCompat.createButton(this.width / 2 - 100, controlsY, 98, 20, this.safra$getToggleText(), button -> {
                ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                button.setMessage(this.safra$getToggleText());
            })
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            ForgeClientCompat.createButton(this.width / 2 + 2, controlsY, 98, 20, this.safra$getOnlineModeText(), button -> {
                ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                button.setMessage(this.safra$getOnlineModeText());
            })
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            ForgeClientCompat.createButton(this.width / 2 - 49, controlsY + 24, 98, 20, ForgeClientCompat.translatable("safra.p2p.server_settings.short"), button ->
                this.minecraft.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this)))
        );
        this.safra$p2pInitialized = true;
    }

    @Unique
    private boolean safra$hidePortInput() {
        boolean found = false;
        for (GuiEventListener child : this.children()) {
            if (!(child instanceof EditBox editBox)) {
                continue;
            }
            found = true;
            editBox.setVisible(false);
            editBox.setEditable(false);
            editBox.setWidth(0);
            ((AbstractWidgetAccessor) editBox).safra$setX(-1000);
            ((AbstractWidgetAccessor) editBox).safra$setY(-1000);
        }
        return found;
    }

    @Unique
    private void safra$clearPortLabel() {
        PORT_INFO_TEXT = ForgeClientCompat.literal("");
    }

    @Unique
    private Component safra$getToggleText() {
        return ForgeClientCompat.translatable(ForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return ForgeClientCompat.translatable(ForgeLanSessionState.isOnlineModeEnabled()
            ? "safra.p2p.online_mode.short.on"
            : "safra.p2p.online_mode.short.off");
    }
}
