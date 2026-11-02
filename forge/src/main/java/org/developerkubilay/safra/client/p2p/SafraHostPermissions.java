package org.developerkubilay.safra.client.p2p;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.ref.WeakReference;
import java.util.UUID;

public final class SafraHostPermissions {
    private static volatile Grant grant;

    private SafraHostPermissions() {
    }

    public static void activate(IntegratedServer server, UUID hostId, long generation, boolean grantHostOperator) {
        P2pManager manager = P2pManager.getInstance();
        synchronized (manager) {
            if (!grantHostOperator || hostId == null || !manager.isHosting() || manager.getHostStartGeneration() != generation) {
                return;
            }
            grant = new Grant(new WeakReference<>(server), hostId);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (isGranted(server, player.getGameProfile())) {
                    server.getPlayerList().sendPlayerPermissionLevel(player);
                }
            }
        }
    }

    public static boolean isGranted(MinecraftServer server, GameProfile profile) {
        Grant current = grant;
        return current != null && current.server().get() == server && current.hostId().equals(profile.getId());
    }

    public static void reset() {
        grant = null;
    }

    private record Grant(WeakReference<IntegratedServer> server, UUID hostId) {
    }
}
