package org.developerkubilay.safra;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SafraForge.MOD_ID)
public final class SafraForge {
    public static final String MOD_ID = "safra";

    public SafraForge() {
        this(FMLJavaModLoadingContext.get());
    }

    public SafraForge(FMLJavaModLoadingContext context) {
    }
}
