package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.FabricVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Pseudo
@Mixin(targets = "net.minecraft.client.gui.screen.multiplayer.ConnectScreen")
abstract class ConnectScreenMixin {
    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void safra$rewriteP2pConnection(Screen parent, MinecraftClient client, ServerAddress serverAddress,
                                                   ServerInfo serverInfo, boolean quickPlay, CookieStorage cookieStorage, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isLikelyP2pAddress(serverInfo.address)) {
            return;
        }

        P2pConnectingScreen progressScreen = new P2pConnectingScreen(
            parent,
            () -> P2pManager.getInstance().cancelPendingRewrite()
        );
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
                    client.setScreen(new DisconnectedScreen(
                        parent,
                        Text.translatable("connect.failed"),
                        P2pErrorComponents.preparationFailure(cause)
                    ));
                    return;
                }

                FabricVersionCompat.startConnect(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo(), quickPlay, cookieStorage);
            })
        );
        ci.cancel();
    }
}
