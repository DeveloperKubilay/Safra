package org.developerkubilay.safra.client.p2p;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.EditGamerulesScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameRules;

import java.lang.reflect.Field;
import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private Button allowCommandsButton;
    private Button fixedCodeButton;
    private boolean safra$closing;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(new TranslationTextComponent("safra.p2p.server_settings"));
        this.parent = parent;
    }

    protected void func_231160_c_() {
        ForgeScreenCompat.initScreenSuper(this);
        ForgeScreenCompat.getButtons(this).clear();
        ForgeScreenCompat.getChildren(this).clear();
        this.safra$closing = false;
        int width = ForgeScreenCompat.getWidth(this);
        int height = ForgeScreenCompat.getHeight(this);
        int top = height / 4 - 20;
        this.allowCommandsButton = ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            top + 24,
            200,
            20,
            this.getAllowCommandsText(),
            button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                ForgeScreenCompat.setButtonMessage(button, this.getAllowCommandsText());
            }
        ));

        this.fixedCodeButton = ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            top + 48,
            200,
            20,
            this.getFixedCodeText(),
            button -> {
                ForgeLanSessionState.setFixedCodeEnabled(!ForgeLanSessionState.isFixedCodeEnabled());
                ForgeScreenCompat.setButtonMessage(button, this.getFixedCodeText());
            }
        ));

        ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            top + 72,
            200,
            20,
            new TranslationTextComponent("safra.p2p.fixed_code.refresh"),
            button -> ForgeLanSessionState.regenerateFixedCode()
        ));

        ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            top + 96,
            200,
            20,
            new TranslationTextComponent("safra.p2p.game_rules"),
            button -> {
                Minecraft minecraft = ForgeScreenCompat.getMinecraft(this);
                if (minecraft == null || minecraft.world == null) {
                    return;
                }
                GameRules editableRules = ForgeLanGameRules.createEditableGameRules(minecraft, ForgeLanSessionState.getGameRuleSnapshot());
                minecraft.displayGuiScreen(new EditGamerulesScreen(editableRules, this::handleGameRulesClose));
            }
        ));

        ForgeScreenCompat.addButton(this, new Button(
            width / 2 - 100,
            top + 120,
            200,
            20,
            new TranslationTextComponent("safra.p2p.game_rules.reset"),
            button -> ForgeLanSessionState.resetGameRules()
        ));

        ForgeScreenCompat.addButton(this, new Button(width / 2 - 100, top + 168, 98, 20, new TranslationTextComponent("gui.done"), button -> this.onClose()));
        ForgeScreenCompat.addButton(this, new Button(width / 2 + 2, top + 168, 98, 20, new TranslationTextComponent("gui.cancel"), button -> this.onClose()));
    }

    public void func_230430_a_(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        this.safra$renderScreen(matrixStack, mouseX, mouseY, partialTicks);
    }

    private void safra$renderScreen(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        ForgeScreenCompat.renderBackground(this, matrixStack);
        ForgeScreenCompat.drawCenteredText(
            this,
            matrixStack,
            new TranslationTextComponent("safra.p2p.server_settings"),
            ForgeScreenCompat.getWidth(this) / 2,
            ForgeScreenCompat.getHeight(this) / 4 - 20,
            0xFFFFFF
        );
        ForgeScreenCompat.renderWidgets(this, matrixStack, mouseX, mouseY, partialTicks);
    }

    public void onClose() {
        if (this.safra$closing) {
            return;
        }
        Minecraft minecraft = ForgeScreenCompat.getMinecraft(this);
        if (minecraft != null) {
            this.safra$closing = true;
            Screen returnScreen = this.safra$createReturnScreen();
            ForgeScreenCompat.resizeScreen(returnScreen, minecraft, ForgeScreenCompat.getWidth(this), ForgeScreenCompat.getHeight(this));
            ForgeScreenCompat.displayScreen(minecraft, returnScreen);
        }
    }

    private ITextComponent getAllowCommandsText() {
        return new TranslationTextComponent(
            ForgeLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private ITextComponent getFixedCodeText() {
        return new TranslationTextComponent(
            ForgeLanSessionState.isFixedCodeEnabled()
                ? "safra.p2p.fixed_code.on"
                : "safra.p2p.fixed_code.off"
        );
    }

    private void handleGameRulesClose(Optional<GameRules> rules) {
        rules.ifPresent(gameRules -> ForgeLanSessionState.setGameRuleSnapshot(ForgeLanGameRules.serialize(gameRules)));
        Minecraft minecraft = ForgeScreenCompat.getMinecraft(this);
        if (minecraft != null) {
            ForgeScreenCompat.resizeScreen(this, minecraft, ForgeScreenCompat.getWidth(this), ForgeScreenCompat.getHeight(this));
            ForgeScreenCompat.displayScreen(minecraft, this);
        }
    }

    private Screen safra$createReturnScreen() {
        if (!(this.parent instanceof ShareToLanScreen)) {
            return this.parent;
        }

        Screen lastScreen = this.safra$getScreenField(this.parent, "lastScreen", "field_146598_a");
        if (lastScreen == null) {
            return this.parent;
        }

        ShareToLanScreen recreated = new ShareToLanScreen(lastScreen);
        this.safra$copyShareToLanState(this.parent, recreated);
        return recreated;
    }

    private void safra$copyShareToLanState(Screen source, ShareToLanScreen target) {
        this.safra$copyObjectField(source, target, new String[]{"gameMode", "field_146599_h"});
        this.safra$copyBooleanField(source, target, new String[]{"allowCheats", "field_146600_i"});
    }

    private Screen safra$getScreenField(Screen screen, String... names) {
        Object value = this.safra$getFieldValue(screen, names);
        return value instanceof Screen ? (Screen) value : null;
    }

    private void safra$copyObjectField(Object source, Object target, String[] names) {
        Object value = this.safra$getFieldValue(source, names);
        if (value == null) {
            return;
        }
        this.safra$setFieldValue(target, value, names);
    }

    private void safra$copyBooleanField(Object source, Object target, String[] names) {
        Object value = this.safra$getFieldValue(source, names);
        if (!(value instanceof Boolean)) {
            return;
        }
        this.safra$setFieldValue(target, value, names);
    }

    private Object safra$getFieldValue(Object target, String[] names) {
        for (String name : names) {
            Field field = this.safra$findField(target.getClass(), name);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private void safra$setFieldValue(Object target, Object value, String[] names) {
        for (String name : names) {
            Field field = this.safra$findField(target.getClass(), name);
            if (field == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private Field safra$findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
