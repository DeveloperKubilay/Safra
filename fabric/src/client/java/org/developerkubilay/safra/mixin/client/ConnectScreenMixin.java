package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void safra$rewriteP2pConnection(Screen parent, MinecraftClient client, ServerAddress serverAddress,
                                                   ServerInfo serverInfo, boolean quickPlay,
                                                   CookieStorage cookieStorage, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isP2pStoredAddress(serverInfo.address)) {
            return;
        }

        ProgressScreen progressScreen = new ProgressScreen(false);
        progressScreen.setTitle(Text.translatable("connect.connecting"));
        progressScreen.setTask(Text.translatable("safra.p2p.prepare_message"));
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
                        Text.translatable("connect.failed"),
                        Text.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                ConnectScreen.connect(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo(), quickPlay, cookieStorage);
            })
        );
        ci.cancel();
    }
}
