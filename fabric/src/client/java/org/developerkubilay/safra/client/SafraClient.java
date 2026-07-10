package org.developerkubilay.safra.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class SafraClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
        this.registerTickListener();
        this.registerShutdownListener();
    }

    private void registerTickListener() {
        if (this.registerModernTickListener()) {
            return;
        }
        this.registerLegacyTickListener();
    }

    private boolean registerModernTickListener() {
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents");
            Class<?> listenerClass = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick");
            Field eventField = eventsClass.getField("END_CLIENT_TICK");
            return this.registerEventListener(eventField.get(null), listenerClass, "onEndTick", args -> {
                P2pManager.getInstance().tick((MinecraftClient) args[0]);
                return null;
            });
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private void registerLegacyTickListener() {
        try {
            Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.event.client.ClientTickCallback");
            Field eventField = callbackClass.getField("EVENT");
            if (!this.registerEventListener(eventField.get(null), callbackClass, "tick", args -> {
                P2pManager.getInstance().tick((MinecraftClient) args[0]);
                return null;
            })) {
                throw new IllegalStateException("Legacy Fabric client tick callback registration failed");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Fabric client tick callback could not be registered", exception);
        }
    }

    private void registerShutdownListener() {
        if (this.registerModernShutdownListener()) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> P2pManager.getInstance().shutdown(), "safra-fabric-shutdown"));
    }

    private boolean registerModernShutdownListener() {
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents");
            Class<?> listenerClass = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStopping");
            Field eventField = eventsClass.getField("CLIENT_STOPPING");
            return this.registerEventListener(eventField.get(null), listenerClass, "onClientStopping", args -> {
                P2pManager.getInstance().shutdown();
                return null;
            });
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private boolean registerEventListener(Object event, Class<?> listenerInterface, String methodName, ListenerInvocation invocation)
        throws ReflectiveOperationException {
        Method registerMethod = event.getClass().getMethod("register", Object.class);
        registerMethod.setAccessible(true);
        Object listener = Proxy.newProxyInstance(
            listenerInterface.getClassLoader(),
            new Class<?>[]{listenerInterface},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) {
                        return "SafraListenerProxy[" + listenerInterface.getSimpleName() + "]";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                }
                if (!methodName.equals(method.getName())) {
                    return null;
                }
                return invocation.invoke(args == null ? new Object[0] : args);
            }
        );
        registerMethod.invoke(event, listener);
        return true;
    }

    @FunctionalInterface
    private interface ListenerInvocation {
        Object invoke(Object[] args);
    }
}
