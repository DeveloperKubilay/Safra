package org.developerkubilay.safra.client.p2p;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
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
    private Button fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(Component.translatable("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int top = this.height / 4 - 20;
        this.allowCommandsButton = this.addRenderableWidget(new Button(this.width / 2 - 100, top + 24, 200, 20, this.getAllowCommandsText(), button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            }));

        this.fixedCodeButton = this.addRenderableWidget(new Button(this.width / 2 - 100, top + 48, 200, 20, this.getFixedCodeText(), button -> {
                ForgeLanSessionState.setFixedCodeEnabled(!ForgeLanSessionState.isFixedCodeEnabled());
                button.setMessage(this.getFixedCodeText());
            }));

        this.addRenderableWidget(new Button(this.width / 2 - 100, top + 72, 200, 20, Component.translatable("safra.p2p.fixed_code.refresh"), button -> {
                ForgeLanSessionState.regenerateFixedCode();
                this.clearWidgetFocus();
            }));

        this.addRenderableWidget(new Button(this.width / 2 - 100, top + 96, 200, 20, Component.translatable("safra.p2p.game_rules"), button -> {
                Minecraft minecraft = this.minecraft;
                if (minecraft == null || minecraft.level == null) {
                    return;
                }
                GameRules editableRules = ForgeLanGameRules.createEditableGameRules(minecraft, ForgeLanSessionState.getGameRuleSnapshot());
                minecraft.setScreen(new EditGameRulesScreen(editableRules, this::handleGameRulesClose));
            }));

        this.addRenderableWidget(new Button(this.width / 2 - 100, top + 120, 200, 20, Component.translatable("safra.p2p.game_rules.reset"), button -> {
                ForgeLanSessionState.resetGameRules();
                this.clearWidgetFocus();
            }));

        this.addRenderableWidget(new Button(this.width / 2 - 100, top + 168, 98, 20, CommonComponents.GUI_DONE, button -> this.onClose()));
        this.addRenderableWidget(new Button(this.width / 2 + 2, top + 168, 98, 20, CommonComponents.GUI_BACK, button -> this.onClose()));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        fill(poseStack, 0, 0, this.width, this.height, 0xC0101010);
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFF);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private Component getAllowCommandsText() {
        return Component.translatable(
            ForgeLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        );
    }

    private Component getFixedCodeText() {
        return Component.translatable(
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
        if (this.minecraft != null) {
            this.minecraft.setScreen(this);
        }
    }
}
