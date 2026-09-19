package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

@Mixin(targets = "net.minecraft.client.multiplayer.resolver.AddressCheck$1")
abstract class AddressCheckMixin {
    @Inject(method = "isAllowed(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Z", at = @At("HEAD"), cancellable = true)
    private void safra$allowLocalProxyServerAddress(ServerAddress serverAddress, CallbackInfoReturnable<Boolean> cir) {
        if (serverAddress == null) {
            return;
        }
        String host = null;
        try {
            host = serverAddress.getHost();
        } catch (Throwable ignored) {
        }
        if (host == null || host.isEmpty()) {
            host = safra$getStringValue(serverAddress, "getHost", "m_171863_", "m_171889_");
        }
        if (safra$isLocalProxyHost(host)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isAllowed(Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;)Z", at = @At("HEAD"), cancellable = true)
    private void safra$allowLocalProxyResolvedAddress(ResolvedServerAddress resolvedServerAddress, CallbackInfoReturnable<Boolean> cir) {
        if (resolvedServerAddress == null) {
            return;
        }
        String hostName = null;
        String hostIp = null;
        try {
            hostName = resolvedServerAddress.getHostName();
            hostIp = resolvedServerAddress.getHostIp();
        } catch (Throwable ignored) {
        }
        if (hostName == null || hostName.isEmpty()) {
            hostName = safra$getStringValue(resolvedServerAddress, "getHostName", "m_142727_", "m_171889_");
        }
        if (hostIp == null || hostIp.isEmpty()) {
            hostIp = safra$getStringValue(resolvedServerAddress, "getHostIp", "m_142728_", "m_171888_");
        }
        if (safra$isLocalProxyHost(hostName) || safra$isLocalProxyHost(hostIp)) {
            cir.setReturnValue(true);
        }
    }

    private static boolean safra$isLocalProxyHost(String host) {
        return P2pConstants.LOCAL_PROXY_HOST.equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static String safra$getStringValue(Object target, String... methodNames) {
        Object value = safra$invokeNoArg(target, String.class, methodNames);
        if (value instanceof String stringValue) {
            return stringValue;
        }

        Class<?> type = target.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(target);
                    if (fieldValue instanceof String stringValue) {
                        return stringValue;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return "";
    }

    private static Object safra$invokeNoArg(Object target, Class<?> expectedType, String... names) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    if (method.getParameterCount() != 0 || !expectedType.isAssignableFrom(method.getReturnType())) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
