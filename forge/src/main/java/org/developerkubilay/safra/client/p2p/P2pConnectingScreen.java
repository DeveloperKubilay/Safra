package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.TranslationTextComponent;

public final class P2pConnectingScreen extends Screen {
    private final Screen parent;
    private boolean canceled;

    public P2pConnectingScreen(Screen parent) {
        super(new TranslationTextComponent("connect.connecting"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addButton(new Button(this.width / 2 - 100, this.height / 2 + 25, 200, 20,
            new TranslationTextComponent("gui.cancel").getString(), button -> this.onClose()));
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTick) {
        this.renderBackground();
        drawCenteredString(this.font, this.title.getString(), this.width / 2, this.height / 2 - 25, 0xFFFFFF);
        drawCenteredString(this.font, new TranslationTextComponent("safra.p2p.prepare_message").getString(), this.width / 2, this.height / 2 - 5, 0xFFFFFF);
        super.render(mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.canceled) {
            return;
        }
        this.canceled = true;
        P2pManager.getInstance().cancelPendingRewrite();
        if (this.minecraft != null) {
            this.minecraft.displayGuiScreen(this.parent);
        }
    }
}
