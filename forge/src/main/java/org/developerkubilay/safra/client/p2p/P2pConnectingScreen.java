package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class P2pConnectingScreen extends ProgressScreen {
    private final Screen parent;
    private final Runnable cancelAction;
    private boolean canceled;

    public P2pConnectingScreen(Screen parent, Runnable cancelAction) {
        super(false);
        this.parent = parent;
        this.cancelAction = cancelAction;
        progressStart(Component.translatable("connect.connecting", new Object[0]));
        progressStage(Component.translatable("safra.p2p.prepare_message", new Object[0]));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel", new Object[0]), button -> cancel())
            .bounds(width / 2 - 100, height / 2 + 35, 200, 20)
            .build());
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
        Minecraft.getInstance().setScreen(parent);
    }
}
