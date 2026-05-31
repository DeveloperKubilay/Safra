package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void safra$rewriteP2pConnection(Screen parent, Minecraft client, ServerAddress serverAddress,
                                                   ServerData serverInfo, boolean quickPlay,
                                                   TransferState transferState, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isP2pStoredAddress(serverInfo.ip)) {
            return;
        }

        ProgressScreen progressScreen = new ProgressScreen(false);
        progressScreen.progressStart(Component.translatable("connect.connecting"));
        progressScreen.progressStage(Component.translatable("safra.p2p.prepare_message"));
        client.setScreen(progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverInfo).whenComplete((rewriteResult, throwable) ->
            client.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    client.setScreen(new DisconnectedScreen(
                        parent,
                        Component.translatable("connect.failed"),
                        Component.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                CompletableFuture.delayedExecutor(75L, TimeUnit.MILLISECONDS).execute(() ->
                    client.execute(() ->
                        ConnectScreen.startConnecting(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo(), quickPlay, transferState)
                    )
                );
            })
        );
        ci.cancel();
    }
}
