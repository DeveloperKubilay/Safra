package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameRules;

import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private Button allowCommandsButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(Component.translatable("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.allowCommandsButton = this.addRenderableWidget(Button.builder(this.getAllowCommandsText(), button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            })
            .bounds(this.width / 2 - 100, this.height / 4 + 24, 200, 20)
            .build());

        this.addRenderableWidget(Button.builder(Component.translatable("safra.p2p.server_settings.reset"), button -> {
                ForgeLanSessionState.resetServerSettings();
                if (this.allowCommandsButton != null) {
                    this.allowCommandsButton.setMessage(this.getAllowCommandsText());
                }
            })
            .bounds(this.width / 2 - 100, this.height / 4 + 48, 200, 20)
            .build());

        this.addRenderableWidget(Button.builder(Component.translatable("safra.p2p.game_rules"), button -> {
                Minecraft minecraft = this.minecraft;
                if (minecraft == null || minecraft.level == null) {
                    return;
                }
                GameRules editableRules = ForgeLanGameRules.createEditableGameRules(minecraft, ForgeLanSessionState.getGameRuleSnapshot());
                minecraft.setScreen(new EditGameRulesScreen(editableRules, this::handleGameRulesClose));
            })
            .bounds(this.width / 2 - 100, this.height / 4 + 72, 200, 20)
            .build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds(this.width / 2 - 100, this.height / 4 + 120, 98, 20)
            .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
            .bounds(this.width / 2 + 2, this.height / 4 + 120, 98, 20)
            .build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xC0101010);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private Component getAllowCommandsText() {
        return Component.translatable(
            ForgeLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private void handleGameRulesClose(Optional<GameRules> rules) {
        rules.ifPresent(gameRules -> ForgeLanSessionState.setGameRuleSnapshot(ForgeLanGameRules.serialize(gameRules)));
        if (this.minecraft != null) {
            this.minecraft.setScreen(this);
        }
    }
}
