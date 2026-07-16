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

    protected void func_231160_c_() {
        ForgeScreenCompat.initScreenSuper(this);
        int width = ForgeScreenCompat.getWidth(this);
        int height = ForgeScreenCompat.getHeight(this);
        ForgeScreenCompat.addButton(this, new Button(width / 2 - 100, height / 2 + 25, 200, 20,
            new TranslationTextComponent("gui.cancel"), button -> cancel()));
    }

    public void func_230430_a_(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        int width = ForgeScreenCompat.getWidth(this);
        int height = ForgeScreenCompat.getHeight(this);
        ForgeScreenCompat.renderBackground(this, matrices);
        ForgeScreenCompat.drawCenteredText(this, matrices, new TranslationTextComponent("connect.connecting"), width / 2, height / 2 - 25, 0xFFFFFF);
        ForgeScreenCompat.drawCenteredText(this, matrices, new TranslationTextComponent("safra.p2p.prepare_message"), width / 2, height / 2 - 5, 0xFFFFFF);
        ForgeScreenCompat.renderWidgets(this, matrices, mouseX, mouseY, delta);
    }

    public boolean func_231176_q_() {
        return true;
    }

    public void func_231175_as__() {
        cancel();
    }

    private void cancel() {
        if (canceled) {
            return;
        }
        canceled = true;
        cancelAction.run();
        ForgeScreenCompat.displayScreen(Minecraft.getInstance(), parent);
    }
}
