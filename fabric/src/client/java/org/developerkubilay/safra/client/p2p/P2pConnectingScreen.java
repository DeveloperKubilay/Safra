package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

public final class P2pConnectingScreen extends Screen {
    private final Screen parent;
    private final Runnable cancelAction;
    private boolean canceled;

    public P2pConnectingScreen(Screen parent, Runnable cancelAction) {
        super(new TranslatableText("connect.connecting"));
        this.parent = parent;
        this.cancelAction = cancelAction;
    }

    @Override
    protected void init() {
        addButton(new ButtonWidget(width / 2 - 100, height / 2 + 25, 200, 20,
            new TranslatableText("gui.cancel"), button -> cancel()));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredText(matrices, textRenderer, new TranslatableText("connect.connecting"), width / 2, height / 2 - 25, 0xFFFFFF);
        drawCenteredText(matrices, textRenderer, new TranslatableText("safra.p2p.prepare_message"), width / 2, height / 2 - 5, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        cancel();
    }

    private void cancel() {
        if (canceled) {
            return;
        }
        canceled = true;
        cancelAction.run();
        MinecraftClient.getInstance().openScreen(parent);
    }
}
