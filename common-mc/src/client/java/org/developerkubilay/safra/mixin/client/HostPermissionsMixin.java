package org.developerkubilay.safra.mixin.client;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.developerkubilay.safra.client.p2p.SafraHostPermissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
abstract class HostPermissionsMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void safra$grantHostPermissions(NameAndId profile, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        if (SafraHostPermissions.isGranted((MinecraftServer) (Object) this, profile)) {
            cir.setReturnValue(LevelBasedPermissionSet.OWNER);
        }
    }
}
