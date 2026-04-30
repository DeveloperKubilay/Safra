package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.WorkingScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.text.TranslationTextComponent;
import org.spongepowered.asm.mixin.Final;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(MultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin {
    @Shadow
    @Final
    private Screen lastScreen;

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null || !P2pManager.isP2pStoredAddress(serverData.ip)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        WorkingScreen progressScreen = new WorkingScreen();
        progressScreen.progressStart(new TranslationTextComponent("connect.connecting"));
        progressScreen.progressStage(new TranslationTextComponent("safra.p2p.prepare_message"));
        client.setScreen(progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverData).whenComplete((rewriteResult, throwable) ->
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
                        this.lastScreen,
                        new TranslationTextComponent("connect.failed").getString(),
                        new TranslationTextComponent("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                client.setScreen(new ConnectingScreen(this.lastScreen, client, rewriteResult.serverInfo()));
            })
        );
        ci.cancel();
    }
}
