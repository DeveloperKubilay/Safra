package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
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
        if (serverInfo == null || !P2pManager.isLikelyP2pAddress(serverInfo.address)) {
            return;
        }

        P2pConnectingScreen progressScreen = new P2pConnectingScreen(
            this.parent,
            () -> P2pManager.getInstance().cancelPendingRewrite()
        );
        MinecraftClient.getInstance().openScreen(progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverInfo).whenComplete((rewriteResult, throwable) ->
            MinecraftClient.getInstance().execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable;
                    if (throwable instanceof CompletionException && ((CompletionException) throwable).getCause() != null) {
                        cause = ((CompletionException) throwable).getCause();
                    }
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    MinecraftClient.getInstance().openScreen(
                        this.safra$createDisconnectedScreen(P2pErrorComponents.preparationFailure(cause))
                    );
                    return;
                }

                MinecraftClient.getInstance().openScreen(new ConnectScreen(this.parent, MinecraftClient.getInstance(), rewriteResult.serverInfo()));
            })
        );
        ci.cancel();
    }

    private Screen safra$createDisconnectedScreen(Text reason) {
        try {
            return DisconnectedScreen.class
                .getConstructor(Screen.class, String.class, Text.class)
                .newInstance(this.parent, "connect.failed", reason);
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            return DisconnectedScreen.class
                .getConstructor(Screen.class, Text.class, Text.class)
                .newInstance(this.parent, new TranslatableText("connect.failed"), reason);
        } catch (ReflectiveOperationException ignored) {
        }

        return this.parent == null ? new net.minecraft.client.gui.screen.TitleScreen() : this.parent;
    }
}
