package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftLifecycleMixin {
    @Inject(method = "disconnect", at = @At("HEAD"))
    private void safra$closeP2pOnWorldExit(Screen screen, CallbackInfo ci) {
        P2pManager.getInstance().shutdown();
    }
}
