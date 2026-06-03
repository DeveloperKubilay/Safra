package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameRules;
import org.developerkubilay.safra.client.p2p.ForgeButtonCompat;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.ForgeGuiCompat;
import org.developerkubilay.safra.client.p2p.ForgeScreenCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

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

    @Unique
    private boolean safra$settingsExpanded;

    @Unique
    private Button safra$allowCommandsButton;

    @Unique
    private Button safra$fixedCodeButton;

    @Unique
    private Button safra$refreshFixedCodeButton;

    @Unique
    private Button safra$gameRulesButton;

    @Unique
    private Button safra$resetGameRulesButton;

    @Unique
    private Button safra$settingsBackButton;

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void safra$loadLanSettings(CallbackInfo ci) {
        ForgeLanSessionState.loadFromConfig();
        this.safra$setField(ForgeLanSessionState.isAllowCommandsEnabled(), "commands", "f_96647_");
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void safra$initInlineSettings(CallbackInfo ci) {
        if (!this.safra$settingsExpanded) {
            return;
        }

        this.safra$buildSettingsOnlyView();
        ci.cancel();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (this.safra$settingsExpanded) {
            return;
        }

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
                ForgeButtonCompat.setMessage(button, this.safra$getToggleText());
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
                ForgeButtonCompat.setMessage(button, this.safra$getOnlineModeText());
            },
            screenWidth / 2 - 100,
            180,
            98,
            20
        ));
        this.safra$serverSettingsButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            ForgeComponentCompat.translatable("safra.p2p.server_settings.short"),
            button -> this.safra$openInlineSettings(),
            screenWidth / 2 + 2,
            180,
            98,
            20
        ));
        this.safra$p2pInitialized = true;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void safra$renderSettingsOnlyView(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!this.safra$settingsExpanded) {
            return;
        }

        ForgeGuiCompat.fill(guiGraphics, 0, 0, this.safra$screenWidth(), this.safra$screenHeight(), 0xC0101010);
        ForgeScreenCompat.renderWidgets((Screen) (Object) this, guiGraphics, mouseX, mouseY, partialTick);
        ci.cancel();
    }

    @Unique
    private void safra$buildSettingsOnlyView() {
        ForgeScreenCompat.clearWidgets((Screen) (Object) this);
        ForgeScreenCompat.clearFocus((Screen) (Object) this);

        int screenWidth = this.safra$screenWidth();
        int screenHeight = this.safra$screenHeight();
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        Minecraft minecraft = this.safra$minecraft();
        if (minecraft != null && ForgeLanGameRules.getSingleplayerServer(minecraft) != null) {
            ForgeLanSessionState.initializeGameRules(minecraft);
        }

        int top = Math.max(40, screenHeight / 4 - 20);
        int left = screenWidth / 2 - 100;
        int width = 200;

        this.safra$allowCommandsButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            this.safra$getAllowCommandsText(),
            button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                ForgeButtonCompat.setMessage(button, this.safra$getAllowCommandsText());
            },
            left,
            top + 24,
            width,
            20
        ));

        this.safra$fixedCodeButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            this.safra$getFixedCodeText(),
            button -> {
                ForgeLanSessionState.setFixedCodeEnabled(!ForgeLanSessionState.isFixedCodeEnabled());
                ForgeButtonCompat.setMessage(button, this.safra$getFixedCodeText());
            },
            left,
            top + 48,
            width,
            20
        ));

        this.safra$refreshFixedCodeButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            ForgeComponentCompat.translatable("safra.p2p.fixed_code.refresh"),
            button -> {
                ForgeLanSessionState.regenerateFixedCode();
                ForgeScreenCompat.clearFocus((Screen) (Object) this);
            },
            left,
            top + 72,
            width,
            20
        ));

        this.safra$gameRulesButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            ForgeComponentCompat.translatable("safra.p2p.game_rules"),
            button -> {
                Minecraft currentMinecraft = this.safra$minecraft();
                if (currentMinecraft == null || this.safra$getFieldValue(currentMinecraft, "level", "f_91073_") == null) {
                    return;
                }
                GameRules editableRules = ForgeLanGameRules.createEditableGameRules(currentMinecraft, ForgeLanSessionState.getGameRuleSnapshot());
                ForgeVersionCompat.setScreen(currentMinecraft, new EditGameRulesScreen(editableRules, this.safra$handleGameRulesClose()));
            },
            left,
            top + 96,
            width,
            20
        ));

        this.safra$resetGameRulesButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            ForgeComponentCompat.translatable("safra.p2p.game_rules.reset"),
            button -> {
                ForgeLanSessionState.resetGameRules();
                ForgeScreenCompat.clearFocus((Screen) (Object) this);
            },
            left,
            top + 120,
            width,
            20
        ));

        this.safra$settingsBackButton = ForgeScreenCompat.addRenderableWidget((Screen) (Object) this, ForgeButtonCompat.create(
            ForgeComponentCompat.translatable("gui.back"),
            button -> this.safra$closeInlineSettings(),
            left,
            top + 168,
            width,
            20
        ));
    }

    @Unique
    private void safra$openInlineSettings() {
        Minecraft minecraft = this.safra$minecraft();
        if (minecraft == null) {
            return;
        }

        this.safra$settingsExpanded = true;
        ForgeVersionCompat.initScreen((Screen) (Object) this, minecraft, this.safra$screenWidth(), this.safra$screenHeight());
    }

    @Unique
    private void safra$closeInlineSettings() {
        Minecraft minecraft = this.safra$minecraft();
        if (minecraft == null) {
            return;
        }

        this.safra$settingsExpanded = false;
        ForgeVersionCompat.initScreen((Screen) (Object) this, minecraft, this.safra$screenWidth(), this.safra$screenHeight());
    }

    @Unique
    private java.util.function.Consumer<Optional<GameRules>> safra$handleGameRulesClose() {
        return rules -> {
            rules.ifPresent(gameRules -> ForgeLanSessionState.setGameRuleSnapshot(ForgeLanGameRules.serialize(gameRules)));
            Minecraft currentMinecraft = this.safra$minecraft();
            if (currentMinecraft != null) {
                ForgeVersionCompat.initScreen((Screen) (Object) this, currentMinecraft, 0, 0);
                ForgeVersionCompat.setScreen(currentMinecraft, (Screen) (Object) this);
            }
        };
    }

    @Unique
    private Component safra$getToggleText() {
        return ForgeComponentCompat.translatable(ForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return ForgeComponentCompat.translatable(
            ForgeLanSessionState.isOnlineModeEnabled()
                ? "safra.p2p.online_mode.short.on"
                : "safra.p2p.online_mode.short.off"
        );
    }

    @Unique
    private Component safra$getAllowCommandsText() {
        return ForgeComponentCompat.translatable(
            ForgeLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    @Unique
    private Component safra$getFixedCodeText() {
        return ForgeComponentCompat.translatable(
            ForgeLanSessionState.isFixedCodeEnabled()
                ? "safra.p2p.fixed_code.on"
                : "safra.p2p.fixed_code.off"
        );
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
    private int safra$screenHeight() {
        Object height = this.safra$getField("height", "f_96544_");
        return height instanceof Integer value ? value : 0;
    }

    @Unique
    private Font safra$font() {
        Object font = this.safra$getField("font", "f_96547_");
        return font instanceof Font resolved ? resolved : null;
    }

    @Unique
    private Object safra$getField(String... names) {
        return this.safra$getFieldValue(this, names);
    }

    @Unique
    private Object safra$getFieldValue(Object target, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
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
