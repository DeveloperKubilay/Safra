package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.authlib.GameProfile;

import java.lang.ref.WeakReference;

public final class SafraHostPermissions {
    private static volatile WeakReference<IntegratedServer> grantedServer = new WeakReference<>(null);

    private SafraHostPermissions() {
    }

    public static void activate(IntegratedServer server, long generation, boolean grantHostOperator) {
        P2pManager manager = P2pManager.getInstance();
        synchronized (manager) {
            if (!grantHostOperator || !manager.isHosting() || manager.getHostStartGeneration() != generation) {
                return;
            }
            grantedServer = new WeakReference<>(server);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (isGranted(server, player.getGameProfile())) {
                    server.getPlayerList().sendPlayerPermissionLevel(player);
                }
            }
        }
    }

    public static boolean isGranted(MinecraftServer server, GameProfile profile) {
        IntegratedServer granted = grantedServer.get();
        return granted != null && granted == server && granted.getSingleplayerProfile() != null
            && granted.getSingleplayerProfile().getId().equals(profile.getId());
    }

    public static void reset() {
        grantedServer = new WeakReference<>(null);
    }
}
