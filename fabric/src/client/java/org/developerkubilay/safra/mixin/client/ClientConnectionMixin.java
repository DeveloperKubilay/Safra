package org.developerkubilay.safra.mixin.client;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import org.developerkubilay.safra.client.p2p.P2pLoginTimeout;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
abstract class ClientConnectionMixin {
    @Unique
    private final P2pLoginTimeout safra$loginTimeout = new P2pLoginTimeout();

    @Inject(method = "channelActive", at = @At("TAIL"), remap = false)
    private void safra$extendProxyLoginTimeout(ChannelHandlerContext context, CallbackInfo ci) {
        ClientConnection connection = (ClientConnection) (Object) this;
        if (connection.getSide() == NetworkSide.CLIENTBOUND
            && P2pManager.getInstance().isActiveProxyEndpoint(context.channel().remoteAddress())) {
            safra$loginTimeout.extend(context.channel());
        }
    }

    @Inject(method = "setState", at = @At("HEAD"))
    private void safra$restorePlayTimeout(NetworkState state, CallbackInfo ci) {
        if (state == NetworkState.PLAY) {
            safra$loginTimeout.finish();
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"), remap = false)
    private void safra$finishLoginTimeout(ChannelHandlerContext context, CallbackInfo ci) {
        safra$loginTimeout.finish();
    }
}
