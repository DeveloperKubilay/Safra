package org.developerkubilay.safra.mixin.client;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import org.developerkubilay.safra.client.p2p.ForgeComponentCompat;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerSelectionList.OnlineServerEntry.class)
abstract class ServerSelectionListOnlineServerEntryMixin {
    @Inject(
        method = "render",
        at = @At("HEAD"),
        require = 0
    )
    private void safra$decorateP2pEntry(CallbackInfo ci) {
        ServerData serverData = this.safra$getServerData();
        if (serverData == null || !P2pManager.isLikelyP2pAddress(ForgeVersionCompat.getServerAddress(serverData))) {
            return;
        }

        ForgeVersionCompat.setServerPinged(serverData, true);
        ForgeVersionCompat.setServerPing(serverData, 0L);
        ForgeVersionCompat.setServerPlayers(serverData, null);
        ForgeVersionCompat.setServerPlayerList(serverData, List.of());
        ForgeVersionCompat.setServerMotd(serverData, ForgeComponentCompat.translatable("safra.p2p.server_list_motd"));
        ForgeVersionCompat.setServerStatus(serverData, ForgeComponentCompat.translatable("safra.p2p.server_list_status"));
    }

    private ServerData safra$getServerData() {
        try {
            Field field = this.getClass().getDeclaredField("f_99857_");
            field.setAccessible(true);
            Object value = field.get(this);
            return value instanceof ServerData serverData ? serverData : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
