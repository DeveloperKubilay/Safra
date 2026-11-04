package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenMixin {
    @Shadow @Final @Mutable
    private Text reason;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void safra$replaceP2pReason(CallbackInfo ci) {
        this.reason = P2pErrorComponents.disconnectFailure(this.reason);
    }
}
