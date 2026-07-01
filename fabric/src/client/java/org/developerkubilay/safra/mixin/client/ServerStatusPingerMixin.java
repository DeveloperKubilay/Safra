package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;

@Mixin(MultiplayerServerListPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void safra$skipP2pServerListPing(ServerInfo serverInfo, Runnable runnable, CallbackInfo ci) {
        if (!P2pManager.isLikelyP2pAddress(serverInfo.address)) {
            return;
        }

        serverInfo.ping = 0L;
        serverInfo.label = new TranslatableText("safra.p2p.server_list_motd");
        serverInfo.playerCountLabel = new TranslatableText("safra.p2p.server_list_status");
        serverInfo.playerListSummary = Collections.<Text>emptyList();
        serverInfo.online = true;
        if (runnable != null) {
            runnable.run();
        }
        ci.cancel();
    }
}
