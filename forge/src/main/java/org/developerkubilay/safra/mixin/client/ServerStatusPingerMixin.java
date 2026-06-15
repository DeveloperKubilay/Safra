package org.developerkubilay.safra.mixin.client;

import java.util.List;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.developerkubilay.safra.client.ForgeClientCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void safra$skipP2pServerListPing(ServerData data, Runnable onPersistentDataChange, CallbackInfo ci) {
        if (!P2pManager.isP2pStoredAddress(data.ip)) {
            return;
        }

        data.ping = 0L;
        data.playerList = List.of();
        data.motd = ForgeClientCompat.translatable("safra.p2p.server_list_motd");
        data.status = ForgeClientCompat.translatable("safra.p2p.server_list_status");
        onPersistentDataChange.run();
        ci.cancel();
    }
}
