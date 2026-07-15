package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class MinecraftLifecycleMixin {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"))
    private void safra$closeP2pOnWorldExit(Screen screen, CallbackInfo ci) {
        MinecraftClient minecraft = (MinecraftClient) (Object) this;
        if (minecraft.world != null || minecraft.getServer() != null) {
            P2pManager.getInstance().shutdown();
        }
    }
}
