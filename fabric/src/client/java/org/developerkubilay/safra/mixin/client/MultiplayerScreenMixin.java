package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerInfo;
import org.developerkubilay.safra.client.FabricScreenCompat;
import org.developerkubilay.safra.client.p2p.FabricClientCompat;
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

    @Inject(method = "connect(Lnet/minecraft/client/network/ServerInfo;)V", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerInfo serverInfo, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isP2pStoredAddress(serverInfo.address)) {
            return;
        }

        ProgressScreen progressScreen = new ProgressScreen(false);
        progressScreen.setTitle(FabricClientCompat.translatable("connect.connecting"));
        progressScreen.setTask(FabricClientCompat.translatable("safra.p2p.prepare_message"));
        FabricScreenCompat.open(MinecraftClient.getInstance(), progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverInfo).whenComplete((rewriteResult, throwable) ->
            MinecraftClient.getInstance().execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    FabricScreenCompat.open(MinecraftClient.getInstance(), new DisconnectedScreen(
                        this.parent,
                        FabricClientCompat.translatable("connect.failed"),
                        FabricClientCompat.translatable("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                ConnectScreen.connect(this.parent, MinecraftClient.getInstance(), rewriteResult.serverAddress(), rewriteResult.serverInfo());
            })
        );
        ci.cancel();
    }
}
