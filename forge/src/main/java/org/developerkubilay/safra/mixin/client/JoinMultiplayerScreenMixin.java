package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.WorkingScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.client.p2p.P2pConnectingScreen;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
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
        Screen currentScreen = (Screen) (Object) this;
        client.displayGuiScreen(new P2pConnectingScreen(currentScreen, () -> P2pManager.getInstance().cancelPendingRewrite()));
        P2pManager.getInstance().createRewriteAsync(serverData).whenComplete((rewriteResult, throwable) ->
            client.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    client.displayGuiScreen(safra$createDisconnectedScreen(currentScreen, P2pErrorComponents.preparationFailure(cause)));
                    return;
                }

                client.displayGuiScreen(new ConnectingScreen(currentScreen, client, rewriteResult.serverInfo()));
            })
        );
        ci.cancel();
    }

    private static Screen safra$createDisconnectedScreen(Screen parent, ITextComponent reason) {
        TranslationTextComponent title = new TranslationTextComponent("connect.failed");
        try {
            return DisconnectedScreen.class
                .getConstructor(Screen.class, ITextComponent.class, ITextComponent.class)
                .newInstance(parent, title, reason);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            return DisconnectedScreen.class
                .getConstructor(Screen.class, String.class, ITextComponent.class)
                .newInstance(parent, title.getString(), reason);
        } catch (ReflectiveOperationException ignored) {
        }
        return parent;
    }
}
