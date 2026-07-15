package org.developerkubilay.safra.client.p2p;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.TranslationTextComponent;

public final class P2pConnectingScreen extends Screen {
    private final Screen parent;
    private final Runnable cancelAction;
    private boolean canceled;

    public P2pConnectingScreen(Screen parent, Runnable cancelAction) {
        super(new TranslationTextComponent("connect.connecting"));
        this.parent = parent;
        this.cancelAction = cancelAction;
    }

    @Override
    protected void init() {
        addButton(new Button(width / 2 - 100, height / 2 + 25, 200, 20,
            new TranslationTextComponent("gui.cancel"), button -> cancel()));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredString(matrices, font, new TranslationTextComponent("connect.connecting"), width / 2, height / 2 - 25, 0xFFFFFF);
        drawCenteredString(matrices, font, new TranslationTextComponent("safra.p2p.prepare_message"), width / 2, height / 2 - 5, 0xFFFFFF);
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
        Minecraft.getInstance().displayGuiScreen(parent);
    }
}
