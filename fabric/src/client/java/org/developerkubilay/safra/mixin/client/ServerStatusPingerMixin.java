package org.developerkubilay.safra.mixin.client;

import java.net.UnknownHostException;
import java.util.List;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerServerListPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void safra$skipP2pServerListPing(ServerInfo serverInfo, Runnable runnable, CallbackInfo ci) throws UnknownHostException {
        if (!P2pManager.isLikelyP2pAddress(serverInfo.address)) {
            return;
        }

        serverInfo.ping = 0L;
        serverInfo.players = null;
        serverInfo.playerListSummary = List.of();
        serverInfo.label = Text.translatable("safra.p2p.server_list_motd");
        serverInfo.playerCountLabel = Text.translatable("safra.p2p.server_list_status");
        runnable.run();
        ci.cancel();
    }
}
