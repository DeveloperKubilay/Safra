package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static Text safra$replaceP2pDisconnectReason(Text details) {
        return P2pErrorComponents.disconnectDetails(details);
    }
}
