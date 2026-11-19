package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.developerkubilay.safra.client.FabricScreenCompat;
import org.developerkubilay.safra.client.p2p.FabricClientCompat;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.developerkubilay.safra.client.p2p.P2pManager;
import net.minecraft.text.Text;
import org.developerkubilay.safra.p2p.P2pClientProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Shadow
    private Text status;
    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void safra$rewriteP2pConnection(Screen parent, MinecraftClient client, ServerAddress serverAddress,
                                                   ServerInfo serverInfo, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isLikelyP2pAddress(serverInfo.address)) {
            return;
        }

        FabricScreenCompat.open(client, new P2pConnectingScreen(parent, P2pManager.getInstance()::cancelPendingRewrite));
        P2pManager.getInstance().createRewriteAsync(serverInfo).whenComplete((rewriteResult, throwable) ->
            client.execute(() -> {
                if (throwable != null) {
                    P2pClientProxy.clearConnectAttempt();
                    Throwable cause = throwable instanceof CompletionException completionException
                        && completionException.getCause() != null
                        ? completionException.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    FabricScreenCompat.open(client, new DisconnectedScreen(
                        parent,
                        FabricClientCompat.translatable("connect.failed"),
                        P2pErrorComponents.preparationFailure(cause)
                    ));
                    return;
                }

                ConnectScreen.connect(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo());
            })
        );
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void safra$updateP2pConnectingStatus(CallbackInfo ci) {
        String stageKey = P2pClientProxy.currentConnectingStageTranslationKey();
        if (stageKey != null) {
            this.status = FabricClientCompat.translatable(stageKey);
        }
    }
}
