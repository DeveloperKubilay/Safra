package org.developerkubilay.safra.client.p2p;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.level.GameRules;

import java.util.Optional;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private Button allowCommandsButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(new TranslatableComponent("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.allowCommandsButton = this.addRenderableWidget(new Button(this.width / 2 - 100, this.height / 4 + 24, 200, 20, this.getAllowCommandsText(), button -> {
                ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
                button.setMessage(this.getAllowCommandsText());
            }));

        this.addRenderableWidget(new Button(this.width / 2 - 100, this.height / 4 + 48, 200, 20, new TranslatableComponent("safra.p2p.server_settings.reset"), button -> {
                ForgeLanSessionState.resetServerSettings();
                if (this.allowCommandsButton != null) {
                    this.allowCommandsButton.setMessage(this.getAllowCommandsText());
                }
            }));

        this.addRenderableWidget(new Button(this.width / 2 - 100, this.height / 4 + 72, 200, 20, new TranslatableComponent("safra.p2p.game_rules"), button -> {
                Minecraft minecraft = this.minecraft;
                if (minecraft == null || minecraft.level == null) {
                    return;
                }
                GameRules editableRules = ForgeLanGameRules.createEditableGameRules(minecraft, ForgeLanSessionState.getGameRuleSnapshot());
                minecraft.setScreen(new EditGameRulesScreen(editableRules, this::handleGameRulesClose));
            }));

        this.addRenderableWidget(new Button(this.width / 2 - 100, this.height / 4 + 120, 98, 20, CommonComponents.GUI_DONE, button -> this.onClose()));
        this.addRenderableWidget(new Button(this.width / 2 + 2, this.height / 4 + 120, 98, 20, CommonComponents.GUI_BACK, button -> this.onClose()));
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
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, this.height / 4, 0xFFFFFF);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private Component getAllowCommandsText() {
        return new TranslatableComponent(
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
