package org.developerkubilay.safra.mixin.client;

import java.util.List;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(
        method = "pingServer(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void safra$skipP2pServerListPing(ServerData data, Runnable onPongResponse, CallbackInfo ci) {
        this.safra$skipP2pServerListPingInternal(data, onPongResponse, ci);
    }

    private void safra$skipP2pServerListPingInternal(ServerData data, Runnable onPongResponse, CallbackInfo ci) {
        if (!P2pManager.isLikelyP2pAddress(data.ip)) {
            return;
        }

        ForgeVersionCompat.setServerPing(data, 0L);
        ForgeVersionCompat.setServerPlayers(data, null);
        ForgeVersionCompat.setServerPlayerList(data, List.of());
        ForgeVersionCompat.setServerMotd(data, ForgeComponentCompat.translatable("safra.p2p.server_list_motd"));
        ForgeVersionCompat.setServerStatus(data, ForgeComponentCompat.translatable("safra.p2p.server_list_status"));
        onPongResponse.run();
        ci.cancel();
    }
}
