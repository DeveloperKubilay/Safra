package org.developerkubilay.safra.client.p2p;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;

import java.lang.ref.WeakReference;
import java.util.List;
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
            safra$syncPermissions(server, hostId);
        }
    }

    public static boolean isGranted(MinecraftServer server, GameProfile profile) {
        Grant current = grant;
        return current != null && current.server().get() == server && current.hostId().equals(profile.getId());
    }

    public static void reset() {
        grant = null;
    }

    private static void safra$syncPermissions(IntegratedServer server, UUID hostId) {
        try {
            Object playerList = null;
            for (String method : new String[]{"getPlayerList", "func_184103_al"}) {
                try {
                    playerList = server.getClass().getMethod(method).invoke(server);
                    if (playerList != null) {
                        break;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            if (playerList == null) {
                return;
            }

            List<?> players = null;
            for (String method : new String[]{"getPlayers", "func_187240_v"}) {
                try {
                    Object result = playerList.getClass().getMethod(method).invoke(playerList);
                    if (result instanceof List) {
                        players = (List<?>) result;
                        break;
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            if (players == null) {
                return;
            }

            for (Object player : players) {
                if (player == null) {
                    continue;
                }
                GameProfile profile = null;
                for (String method : new String[]{"getGameProfile", "func_146103_bH"}) {
                    try {
                        Object result = player.getClass().getMethod(method).invoke(player);
                        if (result instanceof GameProfile) {
                            profile = (GameProfile) result;
                            break;
                        }
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
                if (profile != null && isGranted(server, profile)) {
                    for (String method : new String[]{"sendPlayerPermissionLevel", "func_187243_f", "sendCommandTree", "func_195560_a"}) {
                        try {
                            playerList.getClass().getMethod(method, player.getClass()).invoke(playerList, player);
                            break;
                        } catch (ReflectiveOperationException ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
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
