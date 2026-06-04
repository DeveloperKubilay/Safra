package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.net.InetAddress;
import java.util.List;

@Mixin(LanServerDetection.LanServerList.class)
abstract class LanServerDetectionMixin {
    @Shadow
    private boolean isDirty;

    @Shadow
    private List<LanServer> servers;

    /**
     * @author Safra
     * @reason Avoid Forge's patched Guava InetAddresses path crashing on newer 1.21.x clients.
     */
    @Overwrite
    public synchronized void addServer(String pingResponse, InetAddress address) {
        String motd = LanServerPinger.parseMotd(pingResponse);
        String parsedAddress = LanServerPinger.parseAddress(pingResponse);
        if (parsedAddress == null) {
            return;
        }

        String resolvedAddress = address.getHostAddress() + ":" + parsedAddress;
        boolean knownServer = false;
        for (LanServer server : this.servers) {
            if (server.getAddress().equals(resolvedAddress)) {
                server.updatePingTime();
                knownServer = true;
                break;
            }
        }

        if (!knownServer) {
            this.servers.add(new LanServer(motd, resolvedAddress));
            this.isDirty = true;
        }
    }
}
