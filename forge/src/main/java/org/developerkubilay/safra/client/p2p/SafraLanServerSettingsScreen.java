package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.TranslationTextComponent;

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
            new TranslationTextComponent("safra.p2p.server_settings.reset").getString(),
            button -> {
                ForgeLanSessionState.resetServerSettings();
                if (this.allowCommandsButton != null) {
                    this.allowCommandsButton.setMessage(this.getAllowCommandsText());
                }
            }
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 96,
            98,
            20,
            new TranslationTextComponent("gui.done").getString(),
            button -> this.onClose()
        ));
        this.addButton(new Button(
            this.width / 2 + 2,
            this.height / 4 + 96,
            98,
            20,
            new TranslationTextComponent("gui.back").getString(),
            button -> this.onClose()
        ));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTick) {
        this.renderBackground();
        drawCenteredString(this.font, this.title.getString(), this.width / 2, this.height / 4, 0xFFFFFF);
        super.render(mouseX, mouseY, partialTick);
    }

    private String getAllowCommandsText() {
        return new TranslationTextComponent(
            ForgeLanSessionState.isAllowCommandsEnabled()
                ? "safra.p2p.allow_commands.on"
                : "safra.p2p.allow_commands.off"
        ).getString();
    }
}
