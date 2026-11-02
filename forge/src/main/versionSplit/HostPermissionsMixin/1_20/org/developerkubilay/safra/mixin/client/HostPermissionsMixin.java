package org.developerkubilay.safra.mixin.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import org.developerkubilay.safra.client.p2p.SafraHostPermissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
abstract class HostPermissionsMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void safra$grantHostPermissions(GameProfile profile, CallbackInfoReturnable<Integer> cir) {
        if (SafraHostPermissions.isGranted((MinecraftServer) (Object) this, profile)) {
            cir.setReturnValue(4);
        }
    }
}
