package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.server.LanServerDetection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LanServerDetection.LanServerDetector.class)
abstract class LanServerDetectorMixin {
    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void safra$disableBrokenForgeLanDiscovery(CallbackInfo ci) {
        ci.cancel();
    }
}
