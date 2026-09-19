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

        ResolvedServerAddress resolved = safra$createResolvedAddress(
            new InetSocketAddress(host, safra$getPort(serverAddress))
        );
        if (resolved != null) {
            cir.setReturnValue(Optional.of(resolved));
        }
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

    private static ResolvedServerAddress safra$createResolvedAddress(InetSocketAddress address) {
        Object value = safra$callStatic(ResolvedServerAddress.class,
            new Class<?>[]{InetSocketAddress.class}, new Object[]{address}, "from", "m_171845_");
        if (value instanceof ResolvedServerAddress resolved) {
            return resolved;
        }

        try {
            return ResolvedServerAddress.from(address);
        } catch (Throwable ignored) {
        }

        try {
            return (ResolvedServerAddress) java.lang.reflect.Proxy.newProxyInstance(
                ResolvedServerAddress.class.getClassLoader(),
                new Class<?>[]{ResolvedServerAddress.class},
                (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == InetSocketAddress.class) {
                        return address;
                    }
                    if (returnType == int.class || returnType == Integer.class) {
                        return address.getPort();
                    }
                    if (returnType == String.class) {
                        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("hashCode".equals(method.getName())) {
                        return address.hashCode();
                    }
                    if ("toString".equals(method.getName())) {
                        return address.toString();
                    }
                    return null;
                }
            );
        } catch (Throwable ignored) {
        }

        return new ResolvedServerAddress() {
            @Override
            public String getHostName() {
                return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
            }

            @Override
            public String getHostIp() {
                return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
            }

            @Override
            public int getPort() {
                return address.getPort();
            }

            @Override
            public InetSocketAddress asInetSocketAddress() {
                return address;
            }
        };
    }

    private static Object safra$callStatic(Class<?> targetClass, Class<?>[] parameterTypes, Object[] args, String... names) {
        for (String name : names) {
            try {
                Method method = targetClass.getDeclaredMethod(name, parameterTypes);
                if (Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    return method.invoke(null, args);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
