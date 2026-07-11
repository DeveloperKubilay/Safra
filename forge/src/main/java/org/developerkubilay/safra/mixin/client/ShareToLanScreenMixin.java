package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShareToLanScreen.class, remap = false)
abstract class ShareToLanScreenMixin extends Screen {
    @Shadow(remap = false)
    private boolean allowCheats;

    @Unique
    private Button safra$p2pButton;

    @Unique
    private Button safra$onlineModeButton;

    @Unique
    private Button safra$serverSettingsButton;

    protected ShareToLanScreenMixin(ITextComponent title) {
        super(title);
    }

    @Inject(method = {"init", "func_231160_c_"}, at = @At("HEAD"), remap = false)
    private void safra$loadLanSettings(CallbackInfo ci) {
        ForgeLanSessionState.loadFromConfig();
        this.allowCheats = ForgeLanSessionState.isAllowCommandsEnabled();
    }

    @Inject(method = {"init", "func_231160_c_"}, at = @At("TAIL"), remap = false)
    private void safra$initP2pUi(CallbackInfo ci) {
        if (this.minecraft != null && this.minecraft.getIntegratedServer() != null) {
            ForgeLanSessionState.initializeGameRules(this.minecraft);
        }

        this.safra$p2pButton = this.addButton(new Button(
            this.width / 2 - 100,
            156,
            98,
            20,
            this.safra$getToggleText().getFormattedText(),
            button -> {
                ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                button.setMessage(this.safra$getToggleText().getFormattedText());
            }
        ));
        this.safra$onlineModeButton = this.addButton(new Button(
            this.width / 2 + 2,
            156,
            98,
            20,
            this.safra$getOnlineModeText().getFormattedText(),
            button -> {
                ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                button.setMessage(this.safra$getOnlineModeText().getFormattedText());
            }
        ));
        this.safra$serverSettingsButton = this.addButton(new Button(
            this.width / 2 - 100,
            180,
            200,
            20,
            new TranslationTextComponent("safra.p2p.server_settings.short").getFormattedText(),
            button -> this.minecraft.displayGuiScreen(new SafraLanServerSettingsScreen((Screen) (Object) this))
        ));
    }

    @Unique
    private ITextComponent safra$getToggleText() {
        return new TranslationTextComponent(ForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private ITextComponent safra$getOnlineModeText() {
        return new TranslationTextComponent(
            ForgeLanSessionState.isOnlineModeEnabled()
                ? "safra.p2p.online_mode.short.on"
                : "safra.p2p.online_mode.short.off"
        );
    }
}
