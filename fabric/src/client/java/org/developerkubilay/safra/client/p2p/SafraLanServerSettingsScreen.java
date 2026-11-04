package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.TranslatableText;

public final class SafraLanServerSettingsScreen extends Screen {
    private final Screen parent;
    private ButtonWidget grantHostOperatorButton;
    private ButtonWidget fixedCodeButton;

    public SafraLanServerSettingsScreen(Screen parent) {
        super(new TranslatableText("safra.p2p.server_settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.grantHostOperatorButton = this.addButton(new ButtonWidget(
            this.width / 2 - 100,
            this.height / 4 + 24,
            200,
            20,
            this.getGrantHostOperatorText(),
            button -> {
                FabricLanSessionState.setGrantHostOperatorEnabled(!FabricLanSessionState.isGrantHostOperatorEnabled());
                button.setMessage(this.getGrantHostOperatorText());
            }
        ));

        this.fixedCodeButton = this.addButton(new ButtonWidget(
            this.width / 2 - 100,
            this.height / 4 + 48,
            200,
            20,
            this.getFixedCodeText(),
            button -> {
                FabricLanSessionState.setFixedCodeEnabled(!FabricLanSessionState.isFixedCodeEnabled());
                button.setMessage(this.getFixedCodeText());
            }
        ));

        this.addButton(new ButtonWidget(
            this.width / 2 - 100,
            this.height / 4 + 72,
            200,
            20,
            new TranslatableText("safra.p2p.fixed_code.refresh").getString(),
            button -> FabricLanSessionState.regenerateFixedCode()
        ));

        this.addButton(new ButtonWidget(
            this.width / 2 - 100,
            this.height / 4 + 96,
            200,
            20,
            new TranslatableText("safra.p2p.server_settings.reset").getString(),
            button -> {
                FabricLanSessionState.resetServerSettings();
                if (this.grantHostOperatorButton != null) {
                    this.grantHostOperatorButton.setMessage(this.getGrantHostOperatorText());
                }
                if (this.fixedCodeButton != null) {
                    this.fixedCodeButton.setMessage(this.getFixedCodeText());
                }
            }
        ));

        this.addButton(new ButtonWidget(
            this.width / 2 - 100,
            this.height / 4 + 144,
            98,
            20,
            new TranslatableText("gui.done").getString(),
            button -> this.onClose()
        ));

        this.addButton(new ButtonWidget(
            this.width / 2 + 2,
            this.height / 4 + 144,
            98,
            20,
            new TranslatableText("gui.back").getString(),
            button -> this.onClose()
        ));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.openScreen(this.parent);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        drawCenteredString(this.font, this.title.getString(), this.width / 2, this.height / 4, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }

    private String getGrantHostOperatorText() {
        return new TranslatableText(
            FabricLanSessionState.isGrantHostOperatorEnabled()
                ? "safra.p2p.grant_host_operator.on"
                : "safra.p2p.grant_host_operator.off"
        ).getString();
    }

    private String getFixedCodeText() {
        return new TranslatableText(
            FabricLanSessionState.isFixedCodeEnabled()
                ? "safra.p2p.fixed_code.on"
                : "safra.p2p.fixed_code.off"
        ).getString();
    }
}
