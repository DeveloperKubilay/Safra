package org.developerkubilay.safra.mixin.client;

import java.util.List;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void safra$skipP2pServerListPing(ServerData data, Runnable onPersistentDataChange,
                                             Runnable onPongResponse, CallbackInfo ci) {
        if (!P2pManager.isP2pStoredAddress(data.ip)) {
            return;
        }

        data.ping = 0L;
        data.players = null;
        data.playerList = List.of();
        data.motd = Component.translatable("safra.p2p.server_list_motd");
        data.status = Component.translatable("safra.p2p.server_list_status");
        onPongResponse.run();
        ci.cancel();
    }
}
