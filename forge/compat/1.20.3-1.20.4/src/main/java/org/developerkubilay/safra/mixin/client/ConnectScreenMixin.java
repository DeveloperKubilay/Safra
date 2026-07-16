package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pConstants;
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
                                                   ServerData serverInfo, boolean quickPlay, CallbackInfo ci) {
        if (serverInfo == null) {
            return;
        }
        String address = ForgeVersionCompat.getServerAddress(serverInfo);
        if (!P2pManager.isLikelyP2pAddress(address)) {
            return;
        }
        P2pConnectingScreen progressScreen = new P2pConnectingScreen(
            parent,
            () -> P2pManager.getInstance().cancelPendingRewrite()
        );
        ForgeVersionCompat.setScreen(client, progressScreen);
        P2pManager.getInstance()
            .createRewriteAsync(serverInfo)
            .orTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
            .whenComplete((rewriteResult, throwable) ->
            ForgeVersionCompat.execute(client, () -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    ForgeVersionCompat.setScreen(client, new DisconnectedScreen(
                        parent,
                        ForgeComponentCompat.translatable("connect.failed"),
                        ForgeComponentCompat.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }
                CompletableFuture.delayedExecutor(75L, TimeUnit.MILLISECONDS).execute(() ->
                    ForgeVersionCompat.execute(client, () ->
                        ForgeVersionCompat.startConnect(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo(), quickPlay)
                    )
                );
            })
        );
        ci.cancel();
    }

}
