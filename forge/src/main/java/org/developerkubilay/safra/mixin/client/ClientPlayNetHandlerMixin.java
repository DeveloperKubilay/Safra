package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.util.text.ITextComponent;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ClientPlayNetHandler.class, remap = false)
abstract class ClientPlayNetHandlerMixin {
    @ModifyVariable(method = {"onDisconnect", "func_147231_a"}, at = @At("HEAD"), argsOnly = true, remap = false)
    private ITextComponent safra$classifyBeforeWorldExit(ITextComponent details) {
        return P2pErrorComponents.disconnectFailure(details);
    }
}
