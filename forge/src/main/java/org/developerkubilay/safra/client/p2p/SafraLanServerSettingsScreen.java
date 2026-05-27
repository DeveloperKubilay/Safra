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

    public SafraLanServerSettingsScreen(Screen parent) {
        super(new TranslationTextComponent("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.allowCommandsButton = this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 24,
            200,
            20,
            this.getAllowCommandsText(),
            button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            }
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 48,
            200,
            20,
            new TranslationTextComponent("safra.p2p.server_settings.reset"),
            button -> {
                ForgeLanSessionState.resetServerSettings();
                if (this.allowCommandsButton != null) {
                    this.allowCommandsButton.setMessage(this.getAllowCommandsText());
                }
            }
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 72,
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

        this.addButton(new Button(this.width / 2 - 100, this.height / 4 + 120, 98, 20, DialogTexts.GUI_DONE, button -> this.onClose()));
        this.addButton(new Button(this.width / 2 + 2, this.height / 4 + 120, 98, 20, DialogTexts.GUI_BACK, button -> this.onClose()));
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
        drawCenteredString(matrixStack, this.font, this.title, this.width / 2, this.height / 4, 0xFFFFFF);
        super.render(matrixStack, mouseX, mouseY, partialTick);
    }

    private ITextComponent getAllowCommandsText() {
        return new TranslationTextComponent(
            ForgeLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private void handleGameRulesClose(Optional<GameRules> rules) {
        rules.ifPresent(gameRules -> ForgeLanSessionState.setGameRuleSnapshot(ForgeLanGameRules.serialize(gameRules)));
        if (this.minecraft != null) {
            this.minecraft.displayGuiScreen(this);
        }
    }
}
