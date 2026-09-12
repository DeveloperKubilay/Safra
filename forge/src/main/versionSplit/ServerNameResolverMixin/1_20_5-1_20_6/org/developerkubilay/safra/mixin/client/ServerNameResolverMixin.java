package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.developerkubilay.safra.p2p.P2pConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.util.Optional;

@Mixin(ServerNameResolver.class)
abstract class ServerNameResolverMixin {
    @Inject(method = {"resolveAddress", "m_171890_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void safra$resolveLocalProxyWithoutAddressCheck(ServerAddress serverAddress, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
        if (serverAddress == null) {
            return;
        }

        String host = safra$getHost(serverAddress);
        if (!safra$isLocalProxyHost(host)) {
            return;
        }

        cir.setReturnValue(Optional.of(ResolvedServerAddress.from(
            new InetSocketAddress(host, safra$getPort(serverAddress))
        )));
    }

    private static boolean safra$isLocalProxyHost(String host) {
        return P2pConstants.LOCAL_PROXY_HOST.equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static String safra$getHost(ServerAddress serverAddress) {
        Object value = safra$invokeNoArg(serverAddress, String.class, "getHost", "m_171889_");
        if (value instanceof String host) {
            return host;
        }

        Class<?> type = serverAddress.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(serverAddress);
                    if (fieldValue instanceof String host) {
                        return host;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return "";
    }

    private static int safra$getPort(ServerAddress serverAddress) {
        Object value = safra$invokeNoArg(serverAddress, int.class, "getPort", "m_171890_");
        if (value instanceof Integer port) {
            return port;
        }

        Class<?> type = serverAddress.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.getInt(serverAddress);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return 25565;
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
