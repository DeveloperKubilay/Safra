package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void safra$skipP2pServerListPing(ServerData data, Runnable onPersistentDataChange,
                                             Runnable onPongResponse, EventLoopGroupHolder eventLoopGroupHolder,
                                             CallbackInfo ci) {
        if (!P2pManager.isP2pStoredAddress(data.ip)) {
            return;
        }

        data.setState(ServerData.State.SUCCESSFUL);
        data.ping = 0L;
        data.players = null;
        data.playerList = List.of();
        data.motd = Component.translatable("safra.p2p.server_list_motd");
        data.status = Component.translatable("safra.p2p.server_list_status");
        onPongResponse.run();
        ci.cancel();
    }
}
