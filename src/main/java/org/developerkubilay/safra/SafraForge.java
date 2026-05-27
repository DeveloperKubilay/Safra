package org.developerkubilay.safra;

import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import org.developerkubilay.safra.client.SafraForgeClientEvents;
import org.developerkubilay.safra.server.DedicatedP2pServerManager;

@Mod(modid = SafraForge.MOD_ID, name = "Safra", version = SafraForge.VERSION)
public final class SafraForge {
    public static final String MOD_ID = "safra";
    public static final String VERSION = "1.0-SNAPSHOT";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (event.getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new SafraForgeClientEvents());
        }
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        DedicatedP2pServerManager.serverStarted(FMLCommonHandler.instance().getMinecraftServerInstance());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        DedicatedP2pServerManager.serverStopping(FMLCommonHandler.instance().getMinecraftServerInstance());
    }
}
