package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class P2pConnectingScreen extends ProgressScreen {
    private final Screen parent;
    private final Runnable cancelAction;
    private boolean canceled;

    public P2pConnectingScreen(Screen parent, Runnable cancelAction) {
        super(false);
        this.parent = parent;
        this.cancelAction = cancelAction;
        setTitle(Text.translatable("connect.connecting"));
        setTask(Text.translatable("safra.p2p.prepare_message"));
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> cancel())
            .dimensions(width / 2 - 100, height / 2 + 35, 200, 20)
            .build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        cancel();
    }

    private void cancel() {
        if (canceled) {
            return;
        }
        canceled = true;
        cancelAction.run();
        if (client != null) {
            client.setScreen(parent);
        }
    }
}
