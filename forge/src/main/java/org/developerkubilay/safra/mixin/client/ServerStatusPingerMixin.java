package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.ServerPinger;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;

@Mixin(value = ServerPinger.class, remap = false)
abstract class ServerStatusPingerMixin {
    @Inject(method = "ping", at = @At("HEAD"), cancellable = true, remap = false)
    private void safra$skipP2pServerListPing(ServerData serverData, Runnable runnable, CallbackInfo ci) {
        if (!P2pManager.isP2pStoredAddress(serverData.serverIP)) {
            return;
        }

        serverData.pingToServer = 0L;
        serverData.serverMOTD = new TranslationTextComponent("safra.p2p.server_list_motd");
        serverData.populationInfo = new StringTextComponent(new TranslationTextComponent("safra.p2p.server_list_status").getString());
        serverData.playerList = Collections.emptyList();
        serverData.pinged = true;
        if (runnable != null) {
            runnable.run();
        }
        ci.cancel();
    }
}
