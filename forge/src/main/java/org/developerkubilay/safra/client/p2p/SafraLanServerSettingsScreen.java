package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.EditGamerulesScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameRules;

import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private Button allowCommandsButton;
    private Button fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(new TranslationTextComponent("safra.p2p.server_settings"));
        this.parent = parent;
    }

    protected void init() {
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
            button -> {
                ForgeLanSessionState.regenerateFixedCode();
            }
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
            button -> {
                ForgeLanSessionState.resetGameRules();
            }
        ));

        ForgeScreenCompat.addButton(this, new Button(width / 2 - 100, top + 168, 98, 20, new TranslationTextComponent("gui.done"), button -> this.onClose()));
        ForgeScreenCompat.addButton(this, new Button(width / 2 + 2, top + 168, 98, 20, new TranslationTextComponent("gui.cancel"), button -> this.onClose()));
    }

    public void onClose() {
        Minecraft minecraft = ForgeScreenCompat.getMinecraft(this);
        if (minecraft != null) {
            minecraft.displayGuiScreen(this.parent);
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
            minecraft.displayGuiScreen(this);
        }
    }
}
