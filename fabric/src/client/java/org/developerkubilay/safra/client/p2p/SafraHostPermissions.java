package org.developerkubilay.safra.client.p2p;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.ref.WeakReference;

public final class SafraHostPermissions {
    private static volatile WeakReference<IntegratedServer> grantedServer = new WeakReference<>(null);

    private SafraHostPermissions() {
    }

    public static void activate(IntegratedServer server, long generation, boolean grantHostOperator) {
        P2pManager manager = P2pManager.getInstance();
        synchronized (manager) {
            if (!grantHostOperator || !manager.isHosting() || manager.getHostStartGeneration() != generation
                || server.getHostProfile() == null) {
                return;
            }
            grantedServer = new WeakReference<>(server);
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (isGranted(server, player.getGameProfile())) {
                    server.getPlayerManager().sendCommandTree(player);
                }
            }
        }
    }

    public static boolean isGranted(MinecraftServer server, GameProfile profile) {
        IntegratedServer granted = grantedServer.get();
        return granted != null && granted == server && granted.getHostProfile() != null
            && granted.getHostProfile().getId().equals(profile.getId());
    }

    public static void reset() {
        grantedServer = new WeakReference<>(null);
    }
}
