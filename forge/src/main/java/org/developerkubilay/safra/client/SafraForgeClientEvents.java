package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.developerkubilay.safra.SafraForge;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;

@Mod.EventBusSubscriber(modid = SafraForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SafraForgeClientEvents {
    private static final String[] CLIENT_SHUTDOWN_EVENT_CLASS_NAMES = {
        "net.minecraftforge.event.GameShuttingDownEvent"
    };

    private static final String[] CLIENT_STOPPING_EVENT_CLASS_NAMES = {
        "net.minecraftforge.client.event.ClientPlayerNetworkEvent$LoggedOutEvent",
        "net.minecraftforge.client.event.ClientPlayerNetworkEvent$LoggingOut"
    };

    static {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
        if (!registerClientShutdownListener()) {
            registerClientStoppingListener();
        }
    }

    private SafraForgeClientEvents() {
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        P2pManager.getInstance().tick(Minecraft.getInstance());
    }

    public static void clientStopping(Object event) {
        P2pManager.getInstance().shutdown();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean registerClientShutdownListener() {
        for (String className : CLIENT_SHUTDOWN_EVENT_CLASS_NAMES) {
            try {
                Class<?> eventClass = Class.forName(className);
                MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) eventClass, SafraForgeClientEvents::clientStopping);
                return true;
            } catch (ClassNotFoundException ignored) {
                // Fall back to older disconnect events when game shutdown event is unavailable.
            }
        }

        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerClientStoppingListener() {
        for (String className : CLIENT_STOPPING_EVENT_CLASS_NAMES) {
            try {
                Class<?> eventClass = Class.forName(className);
                MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) eventClass, SafraForgeClientEvents::clientStopping);
                return;
            } catch (ClassNotFoundException ignored) {
                // Try the Forge event name used by the other supported version line.
            }
        }

        throw new IllegalStateException("Could not find a compatible Forge client disconnect event class");
    }
}
