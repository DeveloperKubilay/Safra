package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

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
                if (isGranted(server, player.nameAndId())) {
                    server.getPlayerList().sendPlayerPermissionLevel(player);
                }
            }
        }
    }

    public static boolean isGranted(MinecraftServer server, NameAndId profile) {
        IntegratedServer granted = grantedServer.get();
        return granted != null && granted == server && granted.getSingleplayerProfile() != null
            && granted.getSingleplayerProfile().id().equals(profile.id());
    }

    public static void reset() {
        grantedServer = new WeakReference<>(null);
    }
}
