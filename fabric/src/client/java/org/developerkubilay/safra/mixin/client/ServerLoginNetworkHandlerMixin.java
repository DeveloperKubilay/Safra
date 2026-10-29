package org.developerkubilay.safra.mixin.client;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.developerkubilay.safra.client.p2p.P2pHostLoginConnection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ServerLoginNetworkHandler.class)
abstract class ServerLoginNetworkHandlerMixin {
    @Shadow @Final
    public ClientConnection connection;

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600), require = 1, allow = 1)
    private int safra$extendHostLoginDeadline(int original) {
        return P2pHostLoginConnection.loginTicks(connection, original);
    }
}
