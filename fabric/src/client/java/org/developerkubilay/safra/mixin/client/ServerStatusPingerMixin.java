package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerServerListPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void safra$skipP2pServerListPing(ServerInfo serverInfo, CallbackInfo ci) {
        if (!P2pManager.isP2pStoredAddress(serverInfo.address)) {
            return;
        }

        serverInfo.ping = 0L;
        serverInfo.label = "Safra P2P server";
        serverInfo.playerCountLabel = "P2P";
        serverInfo.playerListSummary = "";
        serverInfo.online = true;
        ci.cancel();
    }
}
