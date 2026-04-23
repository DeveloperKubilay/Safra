package org.developerkubilay.safra.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.developerkubilay.safra.client.p2p.NeoForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(IntegratedServer.class)
abstract class IntegratedServerMixin {
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Inject(method = "publishServer", at = @At("HEAD"))
    private void safra$applyOnlineMode(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        IntegratedServer server = (IntegratedServer) (Object) this;
        server.setUsesAuthentication(NeoForgeLanSessionState.isOnlineModeEnabled());
        if (NeoForgeLanSessionState.isP2pEnabled()) {
            server.setPreventProxyConnections(false);
        }
        SAFRA_LOGGER.debug(
            "Safra LAN auth settings: onlineMode={}, preventProxyConnections={}",
            server.usesAuthentication(),
            server.getPreventProxyConnections()
        );
    }

    @Inject(method = "publishServer", at = @At("RETURN"))
    private void safra$startP2pHost(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        if (!NeoForgeLanSessionState.isP2pEnabled()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        IntegratedServer server = (IntegratedServer) (Object) this;
        int tcpPort = server.getPort();
        Minecraft client = Minecraft.getInstance();
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.starting"));
        P2pManager.getInstance().startHostingAsync(tcpPort).whenComplete((shareCode, throwable) -> {
            client.execute(() -> {
                if (throwable != null) {
                    safra$publishStartFailure(client, tcpPort, throwable);
                    return;
                }

                safra$publishShareCode(client, tcpPort, shareCode);
            });
        });
    }

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        client.keyboardHandler.setClipboard(shareCodeText);

        Component shareText = Component.literal(shareCodeText).withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.started", shareText));
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.copied"));
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.instructions"));
    }

    private static void safra$publishStartFailure(Minecraft client, int tcpPort, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        if (cause instanceof CancellationException) {
            return;
        }

        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        SAFRA_LOGGER.warn("Safra P2P could not start on local TCP port {}", tcpPort, cause);
        client.gui.getChat().addMessage(
            Component.translatable("safra.p2p.host.failed", message).copy().withStyle(ChatFormatting.RED)
        );
    }
}
