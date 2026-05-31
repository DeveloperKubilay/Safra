package org.developerkubilay.safra.mixin.client;

import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

@Mixin(JoinMultiplayerScreen.class)
abstract class MultiplayerScreenMixin {
    @Shadow
    @Final
    private Screen lastScreen;

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverInfo, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isP2pStoredAddress(serverInfo.ip)) {
            return;
        }

        JoinMultiplayerScreen self = (JoinMultiplayerScreen) (Object) this;
        ProgressScreen progressScreen = new ProgressScreen(false);
        progressScreen.progressStartNoAbort(Component.translatable("connect.connecting"));
        progressScreen.progressStage(Component.translatable("safra.p2p.prepare_message"));
        Minecraft.getInstance().setScreen(progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverInfo).whenComplete((rewriteResult, throwable) ->
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
                        this.lastScreen,
                        Component.translatable("connect.failed"),
                        Component.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                ConnectScreen.startConnecting(
                    self,
                    Minecraft.getInstance(),
                    ServerAddress.parseString(rewriteResult.serverInfo().ip),
                    rewriteResult.serverInfo(),
                    false,
                    null
                );
            })
        );
        ci.cancel();
    }
}
