package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.WorkingScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(value = MultiplayerScreen.class, remap = false)
abstract class JoinMultiplayerScreenMixin {
    @Inject(method = {"connectToServer", "func_146791_a"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null || !P2pManager.isP2pStoredAddress(serverData.serverIP)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        WorkingScreen progressScreen = new WorkingScreen();
        progressScreen.displaySavingString(new TranslationTextComponent("connect.connecting"));
        progressScreen.displayLoadingString(new TranslationTextComponent("safra.p2p.prepare_message"));
        client.displayGuiScreen(progressScreen);
        Screen currentScreen = (Screen) (Object) this;
        P2pManager.getInstance().createRewriteAsync(serverData).whenComplete((rewriteResult, throwable) ->
            client.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    client.displayGuiScreen(new DisconnectedScreen(
                        currentScreen,
                        new TranslationTextComponent("connect.failed"),
                        new TranslationTextComponent("safra.p2p.prepare_failed", message)
                    ));
                    return;
                }

                client.displayGuiScreen(new ConnectingScreen(currentScreen, client, rewriteResult.serverInfo()));
            })
        );
        ci.cancel();
    }
}
