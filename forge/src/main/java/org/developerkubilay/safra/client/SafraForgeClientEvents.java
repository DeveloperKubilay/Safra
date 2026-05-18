package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.developerkubilay.safra.SafraForge;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;

import java.lang.reflect.Method;

@Mod.EventBusSubscriber(modid = SafraForge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SafraForgeClientEvents {
    static {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
    }

    private SafraForgeClientEvents() {
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        P2pManager.getInstance().tick(safra$getMinecraft());
    }

    @SubscribeEvent
    public static void clientStopping(GameShuttingDownEvent event) {
        P2pManager.getInstance().shutdown();
    }

    private static Minecraft safra$getMinecraft() {
        try {
            Method method = Minecraft.class.getDeclaredMethod("m_91087_");
            return (Minecraft) method.invoke(null);
        } catch (ReflectiveOperationException ignored) {
            try {
                Method method = Minecraft.class.getDeclaredMethod("getInstance");
                return (Minecraft) method.invoke(null);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Minecraft instance accessor is not available", exception);
            }
        }
    }
}
