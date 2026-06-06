package org.developerkubilay.safra.client.p2p;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.DialogTexts;
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

    @Override
    protected void init() {
        int top = this.height / 4 - 20;
        this.allowCommandsButton = this.addButton(new Button(
            this.width / 2 - 100,
            top + 24,
            200,
            20,
            this.getAllowCommandsText(),
            button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            }
        ));

        this.fixedCodeButton = this.addButton(new Button(
            this.width / 2 - 100,
            top + 48,
            200,
            20,
            this.getFixedCodeText(),
            button -> {
                ForgeLanSessionState.setFixedCodeEnabled(!ForgeLanSessionState.isFixedCodeEnabled());
                button.setMessage(this.getFixedCodeText());
            }
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            top + 72,
            200,
            20,
            new TranslationTextComponent("safra.p2p.fixed_code.refresh"),
            button -> {
                ForgeLanSessionState.regenerateFixedCode();
            }
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            top + 96,
            200,
            20,
            new TranslationTextComponent("safra.p2p.game_rules"),
            button -> {
                Minecraft minecraft = this.minecraft;
                if (minecraft == null || minecraft.world == null) {
                    return;
                }
                GameRules editableRules = ForgeLanGameRules.createEditableGameRules(minecraft, ForgeLanSessionState.getGameRuleSnapshot());
                minecraft.displayGuiScreen(new EditGamerulesScreen(editableRules, this::handleGameRulesClose));
            }
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            top + 120,
            200,
            20,
            new TranslationTextComponent("safra.p2p.game_rules.reset"),
            button -> {
                ForgeLanSessionState.resetGameRules();
            }
        ));

        this.addButton(new Button(this.width / 2 - 100, top + 168, 98, 20, DialogTexts.GUI_DONE, button -> this.onClose()));
        this.addButton(new Button(this.width / 2 + 2, top + 168, 98, 20, DialogTexts.GUI_BACK, button -> this.onClose()));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.displayGuiScreen(this.parent);
        }
    }

    @Override
    public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(matrixStack);
        drawCenteredString(matrixStack, this.font, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFF);
        super.render(matrixStack, mouseX, mouseY, partialTick);
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
        if (this.minecraft != null) {
            this.minecraft.displayGuiScreen(this);
        }
    }
}
