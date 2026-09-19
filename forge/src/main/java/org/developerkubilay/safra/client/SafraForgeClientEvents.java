package org.developerkubilay.safra.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiShareToLan;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.client.p2p.SafraGuiMultiplayer;
import org.developerkubilay.safra.client.p2p.SafraGuiShareToLan;

public final class SafraForgeClientEvents {
    static {
        RemoteRendezvousConfigUpdater.initialize(SafraClientConfig.get());
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.getGui() instanceof GuiMainMenu) {
            P2pManager.getInstance().shutdown();
            return;
        }
        if (event.getGui() instanceof GuiMultiplayer && !(event.getGui() instanceof SafraGuiMultiplayer)) {
            event.setGui(new SafraGuiMultiplayer(Minecraft.getMinecraft().currentScreen));
            return;
        }
        if (event.getGui() instanceof GuiShareToLan && !(event.getGui() instanceof SafraGuiShareToLan)) {
            event.setGui(new SafraGuiShareToLan(Minecraft.getMinecraft().currentScreen));
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        P2pManager.getInstance().tick(Minecraft.getMinecraft());
    }

}
