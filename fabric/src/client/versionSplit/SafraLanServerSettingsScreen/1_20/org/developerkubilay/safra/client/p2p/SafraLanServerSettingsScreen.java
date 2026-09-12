package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.EditGameRulesScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.world.GameRules;

import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private ButtonWidget allowCommandsButton;
    private ButtonWidget fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(Text.translatable("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int top = this.height / 4 - 20;
        this.allowCommandsButton = this.addDrawableChild(ButtonWidget.builder(this.getAllowCommandsText(), button -> {
                FabricLanSessionState.setAllowCommandsEnabled(!FabricLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            })
            .dimensions(this.width / 2 - 100, top + 24, 200, 20)
            .build());

        this.fixedCodeButton = this.addDrawableChild(ButtonWidget.builder(this.getFixedCodeText(), button -> {
                FabricLanSessionState.setFixedCodeEnabled(!FabricLanSessionState.isFixedCodeEnabled());
                button.setMessage(this.getFixedCodeText());
            })
            .dimensions(this.width / 2 - 100, top + 48, 200, 20)
            .build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("safra.p2p.fixed_code.refresh"), button -> {
                FabricLanSessionState.regenerateFixedCode();
                this.clearWidgetFocus();
            })
            .dimensions(this.width / 2 - 100, top + 72, 200, 20)
            .build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("safra.p2p.game_rules"), button -> {
                if (this.client == null || this.client.world == null) {
                    return;
                }
                GameRules editableRules = FabricLanGameRules.createEditableGameRules(this.client, FabricLanSessionState.getGameRuleSnapshot());
                this.client.setScreen(new EditGameRulesScreen(editableRules, this::handleGameRulesClose));
            })
            .dimensions(this.width / 2 - 100, top + 96, 200, 20)
            .build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("safra.p2p.game_rules.reset"), button -> {
                FabricLanSessionState.resetGameRules();
                this.clearWidgetFocus();
            })
            .dimensions(this.width / 2 - 100, top + 120, 200, 20)
            .build());

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> this.close())
            .dimensions(this.width / 2 - 100, top + 168, 98, 20)
            .build());
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> this.close())
            .dimensions(this.width / 2 + 2, top + 168, 98, 20)
            .build());
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xC0101010);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    private Text getAllowCommandsText() {
        return Text.translatable(
            FabricLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private Text getFixedCodeText() {
        return Text.translatable(
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
