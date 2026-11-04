package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
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
        if (serverData == null || !P2pManager.isLikelyP2pAddress(serverData.serverIP)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Screen parent = (Screen) (Object) this;
        P2pConnectingScreen progressScreen = new P2pConnectingScreen(parent);
        client.displayGuiScreen(progressScreen);
        P2pManager.getInstance().createRewriteAsync(serverData).whenComplete((rewriteResult, throwable) ->
            client.execute(() -> {
                if (client.currentScreen != progressScreen) {
                    return;
                }
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (!(cause instanceof CancellationException)) {
                        client.displayGuiScreen(new DisconnectedScreen(parent, new TranslationTextComponent("connect.failed").getString(),
                            P2pErrorComponents.preparationFailure(cause)));
                    }
                    return;
                }
                client.displayGuiScreen(new ConnectingScreen(parent, client, rewriteResult.serverInfo()));
            }));
        ci.cancel();
    }
}
