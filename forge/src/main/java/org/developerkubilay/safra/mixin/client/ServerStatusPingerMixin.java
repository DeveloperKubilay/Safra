package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.ServerPinger;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerPinger.class, remap = false)
abstract class ServerStatusPingerMixin {
    @Inject(method = "ping", at = @At("HEAD"), cancellable = true, remap = false)
    private void safra$skipP2pServerListPing(ServerData serverData, CallbackInfo ci) {
        if (!P2pManager.isP2pStoredAddress(serverData.serverIP)) {
            return;
        }

        serverData.pingToServer = 0L;
        serverData.serverMOTD = "Safra P2P server";
        serverData.populationInfo = "P2P";
        serverData.playerList = "";
        serverData.pinged = true;
        ci.cancel();
    }
}
