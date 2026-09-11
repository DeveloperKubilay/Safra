package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenMixin {
    @ModifyVariable(
        method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private static DisconnectionDetails safra$replaceP2pDisconnectionDetails(DisconnectionDetails details) {
        Component reason = P2pErrorComponents.disconnectDetails(details.reason());
        return reason == details.reason()
            ? details
            : new DisconnectionDetails(reason, details.report(), details.bugReportLink());
    }
}
