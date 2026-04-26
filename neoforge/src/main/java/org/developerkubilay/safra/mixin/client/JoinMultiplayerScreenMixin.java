package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.Map;

@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null || !P2pManager.isP2pStoredAddress(serverData.ip)) {
            return;
        }

        ProgressScreen progressScreen = new ProgressScreen(false);
        progressScreen.progressStart(Component.translatable("connect.connecting"));
        progressScreen.progressStage(Component.translatable("safra.p2p.prepare_message"));
        Minecraft.getInstance().setScreen(progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverData).whenComplete((rewriteResult, throwable) ->
            Minecraft.getInstance().execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    Minecraft.getInstance().setScreen(new DisconnectedScreen(
                        (Screen) (Object) this,
                        Component.translatable("connect.failed"),
                        Component.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                ConnectScreen.startConnecting((Screen) (Object) this, Minecraft.getInstance(), rewriteResult.serverAddress(), rewriteResult.serverInfo(), false, new TransferState(Map.of()));
            })
        );
        ci.cancel();
    }
}
