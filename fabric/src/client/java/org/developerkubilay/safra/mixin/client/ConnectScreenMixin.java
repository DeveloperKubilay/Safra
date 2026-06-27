package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.developerkubilay.safra.client.FabricScreenCompat;
import org.developerkubilay.safra.client.p2p.FabricClientCompat;
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
                                                   ServerInfo serverInfo, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isLikelyP2pAddress(serverInfo.address)) {
            return;
        }

        ProgressScreen progressScreen = new ProgressScreen(false);
        progressScreen.setTitle(FabricClientCompat.translatable("connect.connecting"));
        progressScreen.setTask(FabricClientCompat.translatable("safra.p2p.prepare_message"));
        FabricScreenCompat.open(client, progressScreen);
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
                    FabricScreenCompat.open(client, new DisconnectedScreen(
                        parent,
                        FabricClientCompat.translatable("connect.failed"),
                        FabricClientCompat.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                ConnectScreen.connect(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo());
            })
        );
        ci.cancel();
    }
}
