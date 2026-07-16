package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(net.minecraft.network.chat.Component title) {
        super(title);
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null) {
            return;
        }
        String address = ForgeVersionCompat.getServerAddress(serverData);
        if (!P2pManager.isLikelyP2pAddress(address)) {
            return;
        }
        P2pConnectingScreen progressScreen = new P2pConnectingScreen(
            (Screen) (Object) this,
            () -> P2pManager.getInstance().cancelPendingRewrite()
        );
        Minecraft minecraft = ForgeVersionCompat.getMinecraftInstance();
        ForgeVersionCompat.setScreen(minecraft, progressScreen);
        P2pManager.getInstance()
            .createRewriteAsync(serverData)
            .orTimeout(P2pConstants.RENDEZVOUS_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
            .whenComplete((rewriteResult, throwable) ->
            ForgeVersionCompat.execute(minecraft, () -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    ForgeVersionCompat.setScreen(minecraft, new DisconnectedScreen(
                        (Screen) (Object) this,
                        ForgeComponentCompat.translatable("connect.failed"),
                        ForgeComponentCompat.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }
                ForgeVersionCompat.startConnect((Screen) (Object) this, minecraft, rewriteResult.serverAddress(), rewriteResult.serverInfo(), false);
            })
        );
        ci.cancel();
    }

}
