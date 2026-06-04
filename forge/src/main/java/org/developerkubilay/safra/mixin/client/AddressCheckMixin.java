package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.multiplayer.resolver.AddressCheck$1")
abstract class AddressCheckMixin {
    @Inject(method = "isAllowed(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Z", at = @At("HEAD"), cancellable = true)
    private void safra$allowLocalProxyServerAddress(ServerAddress serverAddress, CallbackInfoReturnable<Boolean> cir) {
        if (serverAddress != null && safra$isLocalProxyHost(serverAddress.getHost())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isAllowed(Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;)Z", at = @At("HEAD"), cancellable = true)
    private void safra$allowLocalProxyResolvedAddress(ResolvedServerAddress resolvedServerAddress, CallbackInfoReturnable<Boolean> cir) {
        if (resolvedServerAddress != null && (
            safra$isLocalProxyHost(resolvedServerAddress.getHostName()) ||
            safra$isLocalProxyHost(resolvedServerAddress.getHostIp())
        )) {
            cir.setReturnValue(true);
        }
    }

    private static boolean safra$isLocalProxyHost(String host) {
        return P2pConstants.LOCAL_PROXY_HOST.equals(host) || "localhost".equalsIgnoreCase(host);
    }
}
