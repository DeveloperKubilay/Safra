package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.EditGameRulesScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.world.GameRules;

import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private ButtonWidget allowCommandsButton;
    private ButtonWidget fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(FabricClientCompat.translatable("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int top = this.height / 4 - 20;
        this.allowCommandsButton = this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, top + 24, 200, 20, this.getAllowCommandsText(), button -> {
                FabricLanSessionState.setAllowCommandsEnabled(!FabricLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            }));

        this.fixedCodeButton = this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, top + 48, 200, 20, this.getFixedCodeText(), button -> {
                FabricLanSessionState.setFixedCodeEnabled(!FabricLanSessionState.isFixedCodeEnabled());
                button.setMessage(this.getFixedCodeText());
            }));

        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, top + 72, 200, 20, FabricClientCompat.translatable("safra.p2p.fixed_code.refresh"), button -> {
                FabricLanSessionState.regenerateFixedCode();
                this.clearWidgetFocus();
            }));

        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, top + 96, 200, 20, FabricClientCompat.translatable("safra.p2p.game_rules"), button -> {
                if (this.client == null || this.client.world == null) {
                    return;
                }
                GameRules editableRules = FabricLanGameRules.createEditableGameRules(this.client, FabricLanSessionState.getGameRuleSnapshot());
                this.client.setScreen(new EditGameRulesScreen(editableRules, this::handleGameRulesClose));
            }));

        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, top + 120, 200, 20, FabricClientCompat.translatable("safra.p2p.game_rules.reset"), button -> {
                FabricLanSessionState.resetGameRules();
                this.clearWidgetFocus();
            }));

        this.addDrawableChild(new ButtonWidget(this.width / 2 - 100, top + 168, 98, 20, FabricClientCompat.screenDone(), button -> this.close()));
        this.addDrawableChild(new ButtonWidget(this.width / 2 + 2, top + 168, 98, 20, FabricClientCompat.screenBack(), button -> this.close()));
    }

    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        fill(matrices, 0, 0, this.width, this.height, 0xC0101010);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
    }

    private Text getAllowCommandsText() {
        return FabricClientCompat.translatable(
            FabricLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private Text getFixedCodeText() {
        return FabricClientCompat.translatable(
            FabricLanSessionState.isFixedCodeEnabled()
                ? "safra.p2p.fixed_code.on"
                : "safra.p2p.fixed_code.off"
        );
    }

    private void clearWidgetFocus() {
        this.setFocused(null);
    }

    private void handleGameRulesClose(Optional<GameRules> rules) {
        rules.ifPresent(gameRules -> FabricLanSessionState.setGameRuleSnapshot(FabricLanGameRules.serialize(gameRules)));
        if (this.client != null) {
            this.client.setScreen(this);
        }
    }
}
