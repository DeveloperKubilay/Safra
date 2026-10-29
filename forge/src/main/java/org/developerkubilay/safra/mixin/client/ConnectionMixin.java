package org.developerkubilay.safra.mixin.client;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import org.developerkubilay.safra.client.p2p.P2pHostLoginConnection;
import org.developerkubilay.safra.client.p2p.P2pLoginTimeout;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.developerkubilay.safra.p2p.P2pHostConnectionRegistry;
import org.developerkubilay.safra.p2p.SafraBuildInfo;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionMixin implements P2pHostLoginConnection {
    @Unique
    private volatile boolean safra$hostTunnel;

    @Override
    public boolean safra$isHostTunnel() {
        return safra$hostTunnel;
    }

    @Unique
    private final P2pLoginTimeout safra$loginTimeout = new P2pLoginTimeout();

    @Inject(method = "channelActive", at = @At("TAIL"), remap = false)
    private void safra$extendProxyLoginTimeout(ChannelHandlerContext context, CallbackInfo ci) {
        Connection connection = (Connection) (Object) this;
        safra$hostTunnel = connection.getReceiving() == PacketFlow.SERVERBOUND
            && P2pHostConnectionRegistry.getInstance().matches(
                context.channel().remoteAddress(), context.channel().localAddress());
        if (safra$hostTunnel && SafraBuildInfo.diagnostics()) {
            LoggerFactory.getLogger(P2pHostLoginConnection.class).info(
                "Safra host LOGIN deadline selected: {} ticks on {} -> {}",
                P2pConstants.HOST_LOGIN_TIMEOUT_TICKS, context.channel().remoteAddress(), context.channel().localAddress());
        }
        if (safra$hostTunnel || connection.getReceiving() == PacketFlow.CLIENTBOUND
            && P2pManager.getInstance().isActiveProxyEndpoint(context.channel().remoteAddress())) {
            safra$loginTimeout.extend(context.channel());
        }
    }

    @Inject(method = "setProtocol", at = @At("HEAD"))
    private void safra$restorePlayTimeout(ConnectionProtocol protocol, CallbackInfo ci) {
        if (protocol == ConnectionProtocol.PLAY) {
            safra$loginTimeout.finish();
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"), remap = false)
    private void safra$finishLoginTimeout(ChannelHandlerContext context, CallbackInfo ci) {
        safra$loginTimeout.finish();
    }
}
