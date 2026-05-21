package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;

public final class SafraNeoForgeClientEvents {
    private static final String[] CLIENT_TICK_EVENT_CLASS_NAMES = {
        "net.neoforged.neoforge.client.event.ClientTickEvent$Post",
        "net.neoforged.neoforge.event.TickEvent$ClientTickEvent"
    };

    static {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
    }

    private SafraNeoForgeClientEvents() {
    }

    public static void register() {
        registerClientTickListener();
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, SafraNeoForgeClientEvents::clientStopping);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerClientTickListener() {
        for (String className : CLIENT_TICK_EVENT_CLASS_NAMES) {
            try {
                Class<?> eventClass = Class.forName(className);
                NeoForge.EVENT_BUS.addListener((Class) eventClass, SafraNeoForgeClientEvents::clientTick);
                return;
            } catch (ClassNotFoundException ignored) {
                // Try the event class used by the other NeoForge line.
            }
        }

        throw new IllegalStateException("Could not find a compatible NeoForge client tick event class");
    }

    private static void clientTick(Object event) {
        if (!isEndPhase(event)) {
            return;
        }

        P2pManager.getInstance().tick(Minecraft.getInstance());
    }

    public static void clientStopping(GameShuttingDownEvent event) {
        P2pManager.getInstance().shutdown();
    }

    private static boolean isEndPhase(Object event) {
        try {
            Object phase = event.getClass().getField("phase").get(event);
            return phase != null && "END".equals(String.valueOf(phase));
        } catch (NoSuchFieldException ignored) {
            return true;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to inspect NeoForge client tick phase", exception);
        }
    }
}
