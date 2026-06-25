package org.developerkubilay.safra.client.p2p;

import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.InWorldGameRulesScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRules;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private Button allowCommandsButton;
    private Button fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(Component.translatable("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int top = this.height / 4 - 20;
        this.allowCommandsButton = this.addRenderableWidget(Button.builder(this.getAllowCommandsText(), button -> {
                FabricLanSessionState.setAllowCommandsEnabled(!FabricLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            })
            .bounds(this.width / 2 - 100, top + 24, 200, 20)
            .build());

        this.fixedCodeButton = this.addRenderableWidget(Button.builder(this.getFixedCodeText(), button -> {
                FabricLanSessionState.setFixedCodeEnabled(!FabricLanSessionState.isFixedCodeEnabled());
                button.setMessage(this.getFixedCodeText());
            })
            .bounds(this.width / 2 - 100, top + 48, 200, 20)
            .build());

        this.addRenderableWidget(Button.builder(Component.translatable("safra.p2p.fixed_code.refresh"), button -> {
                FabricLanSessionState.regenerateFixedCode();
                this.clearWidgetFocus();
            })
            .bounds(this.width / 2 - 100, top + 72, 200, 20)
            .build());

        this.addRenderableWidget(Button.builder(Component.translatable("safra.p2p.game_rules"), button -> {
                if (this.minecraft == null || this.minecraft.level == null) {
                    return;
                }
                var connection = this.minecraft.getConnection();
                if (connection == null) {
                    return;
                }
                this.minecraft.setScreenAndShow(new InWorldGameRulesScreen(connection, this::handleGameRulesClose, this));
            })
            .bounds(this.width / 2 - 100, top + 96, 200, 20)
            .build());

        this.addRenderableWidget(Button.builder(Component.translatable("safra.p2p.game_rules.reset"), button -> {
                FabricLanSessionState.resetGameRules();
                this.clearWidgetFocus();
            })
            .bounds(this.width / 2 - 100, top + 120, 200, 20)
            .build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds(this.width / 2 - 100, top + 168, 98, 20)
            .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
            .bounds(this.width / 2 + 2, top + 168, 98, 20)
            .build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xC0101010);
        context.centeredText(this.font, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFF);
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private Component getAllowCommandsText() {
        return Component.translatable(
            FabricLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private Component getFixedCodeText() {
        return Component.translatable(
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
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this);
        }
    }
}
