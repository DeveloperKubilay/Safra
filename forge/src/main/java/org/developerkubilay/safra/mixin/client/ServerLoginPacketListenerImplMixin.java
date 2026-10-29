package org.developerkubilay.safra.mixin.client;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.developerkubilay.safra.client.p2p.P2pHostLoginConnection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginPacketListenerImplMixin {
    @Shadow @Final
    public Connection connection;

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 600), require = 1, allow = 1)
    private int safra$extendHostLoginDeadline(int original) {
        return P2pHostLoginConnection.loginTicks(connection, original);
    }
}
