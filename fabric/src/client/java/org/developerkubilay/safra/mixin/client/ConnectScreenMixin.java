package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
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

import java.io.IOException;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void safra$rewriteP2pConnection(Screen parent, MinecraftClient client, ServerAddress serverAddress,
                                                   ServerInfo serverInfo, boolean quickPlay,
                                                   CookieStorage cookieStorage, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isP2pStoredAddress(serverInfo.address)) {
            return;
        }

        try {
            P2pManager.RewriteResult rewriteResult = P2pManager.getInstance().createRewrite(serverInfo);
            ConnectScreen.connect(parent, client, rewriteResult.serverAddress(), rewriteResult.serverInfo(), quickPlay, cookieStorage);
        } catch (IOException exception) {
            client.setScreen(new DisconnectedScreen(
                parent,
                Text.translatable("connect.failed"),
                Text.translatable("safra.p2p.prepare_failed", exception.getMessage())
            ));
        }
        ci.cancel();
    }
}
