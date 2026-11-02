package org.developerkubilay.safra.mixin.client;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import org.developerkubilay.safra.client.p2p.SafraWorldOpsManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
abstract class CommandsMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void safra$registerOperatorCommands(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        if (selection == Commands.CommandSelection.INTEGRATED) {
            SafraWorldOpsManager.registerCommands((Commands) (Object) this);
        }
    }
}
