package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientPlayNetworkHandler.class)
abstract class ClientPlayDisconnectMixin {
    @ModifyVariable(method = "onDisconnected", at = @At("HEAD"), argsOnly = true)
    private Text safra$classifyBeforeWorldCleanup(Text reason) {
        return P2pErrorComponents.disconnectFailure(reason);
    }
}
