package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.TranslationTextComponent;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private Button grantHostOperatorButton;
    private Button fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(new TranslationTextComponent("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.grantHostOperatorButton = this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 24,
            200,
            20,
            this.getGrantHostOperatorText(),
            button -> {
                ForgeLanSessionState.setGrantHostOperatorEnabled(!ForgeLanSessionState.isGrantHostOperatorEnabled());
                button.setMessage(this.getGrantHostOperatorText());
            }
        ));

        this.fixedCodeButton = this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 48,
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
            this.height / 4 + 72,
            200,
            20,
            new TranslationTextComponent("safra.p2p.fixed_code.refresh").getString(),
            button -> ForgeLanSessionState.regenerateFixedCode()
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 96,
            200,
            20,
            new TranslationTextComponent("safra.p2p.server_settings.reset").getString(),
            button -> {
                ForgeLanSessionState.resetServerSettings();
                if (this.grantHostOperatorButton != null) {
                    this.grantHostOperatorButton.setMessage(this.getGrantHostOperatorText());
                }
                if (this.fixedCodeButton != null) {
                    this.fixedCodeButton.setMessage(this.getFixedCodeText());
                }
            }
        ));

        this.addButton(new Button(
            this.width / 2 - 100,
            this.height / 4 + 144,
            98,
            20,
            new TranslationTextComponent("gui.done").getString(),
            button -> this.onClose()
        ));
        this.addButton(new Button(
            this.width / 2 + 2,
            this.height / 4 + 144,
            98,
            20,
            new TranslationTextComponent("gui.back").getString(),
            button -> this.onClose()
        ));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.displayGuiScreen(this.parent);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTick) {
        this.renderBackground();
        drawCenteredString(this.font, this.title.getString(), this.width / 2, this.height / 4, 0xFFFFFF);
        super.render(mouseX, mouseY, partialTick);
    }

    private String getGrantHostOperatorText() {
        return new TranslationTextComponent(
            ForgeLanSessionState.isGrantHostOperatorEnabled()
                ? "safra.p2p.grant_host_operator.on"
                : "safra.p2p.grant_host_operator.off"
        ).getString();
    }

    private String getFixedCodeText() {
        return new TranslationTextComponent(
            ForgeLanSessionState.isFixedCodeEnabled()
                ? "safra.p2p.fixed_code.on"
                : "safra.p2p.fixed_code.off"
        ).getString();
    }
}
