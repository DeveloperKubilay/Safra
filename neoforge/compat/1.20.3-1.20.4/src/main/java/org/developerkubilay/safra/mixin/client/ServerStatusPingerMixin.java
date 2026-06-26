package org.developerkubilay.safra.mixin.client;

import java.util.List;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.NeoForgeVersionCompat;
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
        if (!P2pManager.isP2pStoredAddress(data.ip)) {
            return;
        }

        NeoForgeVersionCompat.setServerPinged(data, true);
        NeoForgeVersionCompat.setServerPing(data, 0L);
        NeoForgeVersionCompat.setServerPlayers(data, null);
        NeoForgeVersionCompat.setServerPlayerList(data, List.of());
        NeoForgeVersionCompat.setServerMotd(data, Component.translatable("safra.p2p.server_list_motd"));
        NeoForgeVersionCompat.setServerStatus(data, Component.translatable("safra.p2p.server_list_status"));
        onPongResponse.run();
        ci.cancel();
    }
}
