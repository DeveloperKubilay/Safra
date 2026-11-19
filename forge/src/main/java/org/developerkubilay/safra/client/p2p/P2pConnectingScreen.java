package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.ForgeClientCompat;
import org.developerkubilay.safra.p2p.P2pClientProxy;

public final class P2pConnectingScreen extends ProgressScreen {
    private final Screen parent;
    private final Runnable cancelAction;
    private boolean canceled;

    public P2pConnectingScreen(Screen parent, Runnable cancelAction) {
        super(false);
        this.parent = parent;
        this.cancelAction = cancelAction;
        P2pClientProxy.beginConnectAttempt();
        progressStart(ForgeClientCompat.translatable("connect.connecting"));
        progressStage(ForgeClientCompat.translatable("safra.p2p.prepare_message"));
    }

    @Override
    public void tick() {
        super.tick();
        String stageKey = P2pClientProxy.currentConnectingStageTranslationKey();
        if (stageKey != null) {
            progressStage(ForgeClientCompat.translatable(stageKey));
        }
    }

    @Override
    protected void init() {
        addRenderableWidget(ForgeClientCompat.createButton(
            width / 2 - 100,
            height / 2 + 35,
            200,
            20,
            ForgeClientCompat.translatable("gui.cancel"),
            button -> cancel()
        ));
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
        P2pClientProxy.clearConnectAttempt();
        cancelAction.run();
        Minecraft.getInstance().setScreen(parent);
    }
}
