package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameRules;

import java.lang.reflect.Field;
import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private Button allowCommandsButton;
    private Button fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(ForgeComponentCompat.translatable("safra.p2p.server_settings"));
        this.parent = parent;
    }

    private int safra$getWidth() {
        Object v = safra$getField(this, "width", "f_96543_");
        return v instanceof Integer i ? i : 0;
    }

    private int safra$getHeight() {
        Object v = safra$getField(this, "height", "f_96544_");
        return v instanceof Integer i ? i : 0;
    }

    private Minecraft safra$getMinecraft() {
        Object v = safra$getField(this, "minecraft", "f_96541_");
        return v instanceof Minecraft mc ? mc : null;
    }

    private static Object safra$getField(Object target, String... names) {
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

    @Override
    protected void init() {
        super.init();
        int w = safra$getWidth();
        int h = safra$getHeight();
        int top = h / 4 - 20;
        this.allowCommandsButton = ForgeScreenCompat.addRenderableWidget(this, ForgeButtonCompat.create(this.getAllowCommandsText(), button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                ForgeButtonCompat.setMessage(button, this.getAllowCommandsText());
            }, w / 2 - 100, top + 24, 200, 20));

        this.fixedCodeButton = ForgeScreenCompat.addRenderableWidget(this, ForgeButtonCompat.create(this.getFixedCodeText(), button -> {
                ForgeLanSessionState.setFixedCodeEnabled(!ForgeLanSessionState.isFixedCodeEnabled());
                ForgeButtonCompat.setMessage(button, this.getFixedCodeText());
            }, w / 2 - 100, top + 48, 200, 20));

        ForgeScreenCompat.addRenderableWidget(this, ForgeButtonCompat.create(ForgeComponentCompat.translatable("safra.p2p.fixed_code.refresh"), button -> {
                ForgeLanSessionState.regenerateFixedCode();
                this.clearWidgetFocus();
            }, w / 2 - 100, top + 72, 200, 20));

        ForgeScreenCompat.addRenderableWidget(this, ForgeButtonCompat.create(ForgeComponentCompat.translatable("safra.p2p.game_rules"), button -> {
                Minecraft minecraft = safra$getMinecraft();
                if (minecraft == null) {
                    return;
                }
                Object level = safra$getField(minecraft, "level", "f_91073_");
                if (level == null) {
                    return;
                }
                GameRules editableRules = ForgeLanGameRules.createEditableGameRules(minecraft, ForgeLanSessionState.getGameRuleSnapshot());
                ForgeVersionCompat.setScreen(minecraft, new EditGameRulesScreen(editableRules, this::handleGameRulesClose));
            }, w / 2 - 100, top + 96, 200, 20));

        ForgeScreenCompat.addRenderableWidget(this, ForgeButtonCompat.create(ForgeComponentCompat.translatable("safra.p2p.game_rules.reset"), button -> {
                ForgeLanSessionState.resetGameRules();
                this.clearWidgetFocus();
            }, w / 2 - 100, top + 120, 200, 20));

        ForgeScreenCompat.addRenderableWidget(this, ForgeButtonCompat.create(CommonComponents.GUI_DONE, button -> this.onClose(), w / 2 - 100, top + 168, 98, 20));
        ForgeScreenCompat.addRenderableWidget(this, ForgeButtonCompat.create(CommonComponents.GUI_BACK, button -> this.onClose(), w / 2 + 2, top + 168, 98, 20));
    }

    @Override
    public void onClose() {
        Minecraft mc = safra$getMinecraft();
        if (mc != null) {
            ForgeVersionCompat.setScreen(mc, this.parent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        int w = safra$getWidth();
        int h = safra$getHeight();
        Object font = safra$getField(this, "font", "f_96547_");
        if (font instanceof net.minecraft.client.gui.Font f) {
            guiGraphics.drawCenteredString(f, this.title, w / 2, h / 4 - 20, 0xFFFFFF);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private Component getAllowCommandsText() {
        return ForgeComponentCompat.translatable(
            ForgeLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private Component getFixedCodeText() {
        return ForgeComponentCompat.translatable(
            ForgeLanSessionState.isFixedCodeEnabled()
                ? "safra.p2p.fixed_code.on"
                : "safra.p2p.fixed_code.off"
        );
    }

    private void clearWidgetFocus() {
        this.setFocused(null);
    }

    private void handleGameRulesClose(Optional<GameRules> rules) {
        rules.ifPresent(gameRules -> ForgeLanSessionState.setGameRuleSnapshot(ForgeLanGameRules.serialize(gameRules)));
        Minecraft mc = safra$getMinecraft();
        if (mc != null) {
            int w = safra$getWidth();
            int h = safra$getHeight();
            ForgeVersionCompat.initScreen(this, mc, w, h);
            ForgeVersionCompat.setScreen(mc, this);
        }
    }
}
