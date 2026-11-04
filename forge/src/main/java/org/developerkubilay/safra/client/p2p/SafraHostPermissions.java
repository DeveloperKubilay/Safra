package org.developerkubilay.safra.client.p2p;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;

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
            grant = new Grant(server, hostId);
            ServerPlayerEntity player = server.getPlayerList().getPlayerByUUID(hostId);
            if (player != null) {
                server.getPlayerList().updatePermissionLevel(player);
            }
        }
    }

    public static boolean isGranted(MinecraftServer server, GameProfile profile) {
        Grant current = grant;
        return current != null && profile != null && current.server.get() == server && current.hostId.equals(profile.getId());
    }

    public static void reset() {
        Grant current = grant;
        grant = null;
        IntegratedServer server = current == null ? null : current.server.get();
        if (server != null) {
            server.execute(() -> {
                ServerPlayerEntity player = server.getPlayerList().getPlayerByUUID(current.hostId);
                if (player != null) {
                    server.getPlayerList().updatePermissionLevel(player);
                }
            });
        }
    }

    private static final class Grant {
        private final WeakReference<IntegratedServer> server;
        private final UUID hostId;

        private Grant(IntegratedServer server, UUID hostId) {
            this.server = new WeakReference<>(server);
            this.hostId = hostId;
        }
    }
}
