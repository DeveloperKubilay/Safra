package org.developerkubilay.safra.client.p2p;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;

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
            if (server.getPlayerManager() != null) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (isGranted(server, player.getGameProfile())) {
                        server.getPlayerManager().sendCommandTree(player);
                    }
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

    private static final class Grant {
        private final WeakReference<IntegratedServer> server;
        private final UUID hostId;

        private Grant(WeakReference<IntegratedServer> server, UUID hostId) {
            this.server = server;
            this.hostId = hostId;
        }

        public WeakReference<IntegratedServer> server() {
            return server;
        }

        public UUID hostId() {
            return hostId;
        }
    }
}
