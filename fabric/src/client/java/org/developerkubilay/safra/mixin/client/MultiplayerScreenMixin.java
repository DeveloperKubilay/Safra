package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.TranslatableText;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(MultiplayerScreen.class)
abstract class MultiplayerScreenMixin {
    @Shadow
    @Final
    private Screen parent;

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(CallbackInfo ci) {
        ServerInfo serverInfo = null;
        if (serverInfo == null || !P2pManager.isP2pStoredAddress(serverInfo.address)) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        client.openScreen(new ProgressScreen());
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
                    client.openScreen(new DisconnectedScreen(
                        this.parent,
                        new TranslatableText("connect.failed").getString(),
                        new TranslatableText("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                client.openScreen(new ConnectScreen(this.parent, client, rewriteResult.serverInfo()));
            })
        );
        ci.cancel();
    }
}
