package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.util.Optional;

@Mixin(ServerNameResolver.class)
abstract class ServerNameResolverMixin {
    @Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
    private void safra$resolveLocalProxyWithoutAddressCheck(ServerAddress serverAddress, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
        if (serverAddress == null || !safra$isLocalProxyHost(serverAddress.getHost())) {
            return;
        }

        cir.setReturnValue(Optional.of(ResolvedServerAddress.from(
            new InetSocketAddress(serverAddress.getHost(), serverAddress.getPort())
        )));
    }

    private static boolean safra$isLocalProxyHost(String host) {
        return P2pConstants.LOCAL_PROXY_HOST.equals(host) || "localhost".equalsIgnoreCase(host);
    }
}
