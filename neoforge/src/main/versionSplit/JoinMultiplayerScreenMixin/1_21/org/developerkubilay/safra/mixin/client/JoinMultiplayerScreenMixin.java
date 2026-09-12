package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null || !P2pManager.isP2pConnectionAddress(serverData.ip)) {
            return;
        }

        P2pConnectingScreen progressScreen = new P2pConnectingScreen(
            (Screen) (Object) this,
            () -> P2pManager.getInstance().cancelPendingRewrite()
        );
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
                    Minecraft.getInstance().setScreen(new DisconnectedScreen(
                        (Screen) (Object) this,
                        Component.translatable("connect.failed"),
                        P2pErrorComponents.preparationFailure(cause)
                    ));
                    return;
                }

                ConnectScreen.startConnecting(
                    (JoinMultiplayerScreen) (Object) this,
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
