package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Mixin(MultiplayerScreen.class)
abstract class MultiplayerScreenMixin {
    @Shadow
    @Final
    private Screen parent;

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private void safra$rewriteP2pBeforeVanillaParse(ServerInfo serverInfo, CallbackInfo ci) {
        if (serverInfo == null || !P2pManager.isP2pStoredAddress(serverInfo.address)) {
            return;
        }

        try {
            P2pManager.RewriteResult rewriteResult = P2pManager.getInstance().createRewrite(serverInfo);
            serverInfo.copyWithSettingsFrom(rewriteResult.serverInfo());
            serverInfo.address = rewriteResult.serverInfo().address;
        } catch (IOException exception) {
            MinecraftClient.getInstance().setScreen(new DisconnectedScreen(
                this.parent,
                Text.translatable("connect.failed"),
                Text.translatable("safra.p2p.prepare_failed", exception.getMessage())
            ));
            ci.cancel();
        }
    }
}
