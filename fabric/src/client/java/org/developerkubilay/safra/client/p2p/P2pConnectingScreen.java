package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import org.developerkubilay.safra.client.FabricScreenCompat;
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
        setTitle(FabricClientCompat.translatable("connect.connecting"));
        setTask(FabricClientCompat.translatable("safra.p2p.prepare_message"));
    }

    @Override
    public void tick() {
        super.tick();
        String stageKey = P2pClientProxy.currentConnectingStageTranslationKey();
        if (stageKey != null) {
            setTask(FabricClientCompat.translatable(stageKey));
        }
    }

    @Override
    protected void init() {
        addDrawableChild(FabricClientCompat.createButton(
            width / 2 - 100,
            height / 2 + 35,
            200,
            20,
            FabricClientCompat.translatable("gui.cancel"),
            button -> cancel()
        ));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            cancel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void cancel() {
        if (canceled) {
            return;
        }
        canceled = true;
        P2pClientProxy.clearConnectAttempt();
        cancelAction.run();
        FabricScreenCompat.open(client, parent);
    }
}
