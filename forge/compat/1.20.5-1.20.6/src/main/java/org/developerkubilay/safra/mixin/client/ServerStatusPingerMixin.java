package org.developerkubilay.safra.mixin.client;

import java.util.List;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(
        method = "pingServer(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 1
    )
    private void safra$skipP2pServerListPing(ServerData data, Runnable onPersistentDataChange, Runnable onPongResponse, CallbackInfo ci) {
        if (data == null || !P2pManager.isLikelyP2pAddress(data.ip)) {
            return;
        }

        data.ping = 0L;
        data.players = null;
        data.playerList = List.of();
        data.motd = ForgeComponentCompat.translatable("safra.p2p.server_list_motd");
        data.status = ForgeComponentCompat.translatable("safra.p2p.server_list_status");
        try {
            data.setState(ServerData.State.SUCCESSFUL);
        } catch (NoSuchMethodError ignored) {
        }
        onPongResponse.run();
        ci.cancel();
    }
}
