package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.util.text.ITextComponent;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = DisconnectedScreen.class, remap = false)
abstract class DisconnectedScreenMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private static ITextComponent safra$replaceP2pReason(ITextComponent reason) {
        return P2pErrorComponents.disconnectFailure(reason);
    }
}
