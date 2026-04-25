package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerData serverData, CallbackInfo ci) {
        if (serverData == null || !P2pManager.isP2pStoredAddress(serverData.ip)) {
            return;
        }

        try {
            P2pManager.RewriteResult rewriteResult = P2pManager.getInstance().createRewrite(serverData);
            serverData.copyFrom(rewriteResult.serverInfo());
            serverData.ip = rewriteResult.serverInfo().ip;
        } catch (IOException exception) {
            Minecraft.getInstance().setScreen(new DisconnectedScreen(
                (Screen) (Object) this,
                Component.translatable("connect.failed"),
                Component.translatable("safra.p2p.prepare_failed", exception.getMessage())
            ));
            ci.cancel();
        }
    }
}
