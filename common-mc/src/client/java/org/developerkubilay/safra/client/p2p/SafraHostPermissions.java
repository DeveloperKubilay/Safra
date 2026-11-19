package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.lang.ref.WeakReference;

public final class SafraHostPermissions {
    private static volatile WeakReference<IntegratedServer> grantedServer = new WeakReference<>(null);
    private static volatile WeakReference<IntegratedServer> lanAllowCommandsServer = new WeakReference<>(null);

    private SafraHostPermissions() {
    }

    public static void activate(IntegratedServer server, long generation, boolean grantHostOperator) {
        P2pManager manager = P2pManager.getInstance();
        synchronized (manager) {
            if (!grantHostOperator || !manager.isHosting() || manager.getHostStartGeneration() != generation) {
                return;
            }
            grantedServer = new WeakReference<>(server);
            refreshHostPermissionLevel(server);
        }
    }

    public static void setLanAllowCommands(IntegratedServer server, boolean allowCommands) {
        if (server == null) {
            return;
        }
        if (allowCommands) {
            lanAllowCommandsServer = new WeakReference<>(server);
        } else if (lanAllowCommandsServer.get() == server) {
            lanAllowCommandsServer = new WeakReference<>(null);
        }
        refreshHostPermissionLevel(server);
    }

    public static boolean isGranted(MinecraftServer server, NameAndId profile) {
        if (server == null || profile == null) {
            return false;
        }
        IntegratedServer granted = grantedServer.get();
        IntegratedServer lanGranted = lanAllowCommandsServer.get();
        IntegratedServer active = granted == server ? granted : (lanGranted == server ? lanGranted : null);
        return active != null && active.getSingleplayerProfile() != null
            && active.getSingleplayerProfile().id().equals(profile.id());
    }

    public static void reset() {
        grantedServer = new WeakReference<>(null);
        lanAllowCommandsServer = new WeakReference<>(null);
    }

    private static void refreshHostPermissionLevel(IntegratedServer server) {
        if (server.getSingleplayerProfile() == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.getSingleplayerProfile().id().equals(player.nameAndId().id())) {
                server.getPlayerList().sendPlayerPermissionLevel(player);
            }
        }
    }
}
