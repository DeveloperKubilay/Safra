package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.EditGameRulesScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.world.GameRules;

import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private ButtonWidget allowCommandsButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(new TranslatableText("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.allowCommandsButton = this.addButton(new ButtonWidget(this.width / 2 - 100, this.height / 4 + 24, 200, 20, this.getAllowCommandsText(), button -> {
                FabricLanSessionState.setAllowCommandsEnabled(!FabricLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            }));

        this.addButton(new ButtonWidget(this.width / 2 - 100, this.height / 4 + 48, 200, 20, new TranslatableText("safra.p2p.server_settings.reset"), button -> {
                FabricLanSessionState.resetServerSettings();
                if (this.allowCommandsButton != null) {
                    this.allowCommandsButton.setMessage(this.getAllowCommandsText());
                }
            }));

        this.addButton(new ButtonWidget(this.width / 2 - 100, this.height / 4 + 72, 200, 20, new TranslatableText("safra.p2p.game_rules"), button -> {
                if (this.client == null || this.client.world == null) {
                    return;
                }
                GameRules editableRules = FabricLanGameRules.createEditableGameRules(this.client, FabricLanSessionState.getGameRuleSnapshot());
                this.client.openScreen(new EditGameRulesScreen(editableRules, this::handleGameRulesClose));
            }));

        this.addButton(new ButtonWidget(this.width / 2 - 100, this.height / 4 + 120, 98, 20, new TranslatableText("gui.done"), button -> this.onClose()));
        this.addButton(new ButtonWidget(this.width / 2 + 2, this.height / 4 + 120, 98, 20, new LiteralText("Back"), button -> this.onClose()));
    }

    @Override
    public void onClose() {
        if (this.client != null) {
            this.client.openScreen(this.parent);
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        fill(matrices, 0, 0, this.width, this.height, 0xC0101010);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, this.height / 4, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
    }

    private TranslatableText getAllowCommandsText() {
        return new TranslatableText(
            FabricLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private void handleGameRulesClose(Optional<GameRules> rules) {
        rules.ifPresent(gameRules -> FabricLanSessionState.setGameRuleSnapshot(FabricLanGameRules.serialize(gameRules)));
        if (this.client != null) {
            this.client.openScreen(this);
        }
    }
}
