package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.ForgeScreenCompat;
import org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = ShareToLanScreen.class, remap = false)
abstract class ShareToLanScreenMixin extends Screen {
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
        this.safra$setAllowCheats(ForgeLanSessionState.isAllowCommandsEnabled());
    }

    @Inject(method = {"init", "func_231160_c_"}, at = @At("TAIL"), remap = false)
    private void safra$initP2pUi(CallbackInfo ci) {
        int width = ForgeScreenCompat.getWidth(this);
        Minecraft minecraft = ForgeScreenCompat.getMinecraft(this);
        if (minecraft != null && minecraft.getIntegratedServer() != null) {
            ForgeLanSessionState.initializeGameRules(minecraft);
        }

        this.safra$p2pButton = ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            156,
            98,
            20,
            this.safra$getToggleText(),
            button -> {
                ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                ForgeScreenCompat.setButtonMessage(button, this.safra$getToggleText());
            }
        ));
        this.safra$onlineModeButton = ForgeScreenCompat.addButton(this, new Button(
            width / 2 + 2,
            156,
            98,
            20,
            this.safra$getOnlineModeText(),
            button -> {
                ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                ForgeScreenCompat.setButtonMessage(button, this.safra$getOnlineModeText());
            }
        ));
        this.safra$serverSettingsButton = ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            180,
            200,
            20,
            new TranslationTextComponent("safra.p2p.server_settings.short"),
            button -> {
                Minecraft currentMinecraft = ForgeScreenCompat.getMinecraft(this);
                if (currentMinecraft != null) {
                    currentMinecraft.displayGuiScreen(new SafraLanServerSettingsScreen((Screen) (Object) this));
                }
            }
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

    @Unique
    private void safra$setAllowCheats(boolean allowCheats) {
        Field field = this.safra$findField("allowCheats", "field_146600_i");
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.setBoolean(this, allowCheats);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Unique
    private Field safra$findField(String... names) {
        Class<?> current = this.getClass();
        while (current != null) {
            for (String name : names) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
