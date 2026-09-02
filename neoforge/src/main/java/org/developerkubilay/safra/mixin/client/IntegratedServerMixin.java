package org.developerkubilay.safra.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.GameType;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.p2p.NeoForgeLanGameRules;
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
        NeoForgeLanGameRules.applyToServer(server, NeoForgeLanSessionState.getGameRuleSnapshot());
        int tcpPort = server.getPort();
        Minecraft client = Minecraft.getInstance();
        client.gui.getChat().addClientSystemMessage(Component.translatable("safra.p2p.host.starting"));
        String fixedCode = NeoForgeLanSessionState.isFixedCodeEnabled() ? NeoForgeLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> client.execute(() -> {
            client.gui.getChat().addClientSystemMessage(
                Component.translatable("safra.p2p.host.relay_warning").copy().withStyle(ChatFormatting.YELLOW)
            );
            client.gui.getChat().addClientSystemMessage(safra$discordLink());
        })).whenComplete((shareCode, throwable) -> {
            client.execute(() -> {
                if (throwable != null) {
                    safra$publishStartFailure(client, tcpPort, throwable);
                    return;
                }

                safra$publishShareCode(client, tcpPort, shareCode);
            });
        });
    }
    private static Component safra$discordLink() {
        String url = RemoteRendezvousConfigUpdater.discordUrl();
        return Component.literal(url)
            .withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)
            .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(url))));
    }

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        client.keyboardHandler.setClipboard(shareCodeText);

        Component shareText = Component.literal(shareCodeText)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withInsertion(shareCodeText)
                .withClickEvent(new ClickEvent.CopyToClipboard(shareCodeText))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("safra.p2p.copy_hint"))));
        client.gui.getChat().addClientSystemMessage(Component.translatable("safra.p2p.host.started", shareText));
        if (!shareCode.isRendezvous()) {
            client.gui.getChat().addClientSystemMessage(
                Component.literal("Safra Error: ")
                    .append(Component.translatable("safra.p2p.error.direct_fallback"))
                    .withStyle(ChatFormatting.RED)
            );
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            client.gui.getChat().addClientSystemMessage(
                Component.translatable("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion())
                    .copy()
                    .withStyle(ChatFormatting.YELLOW)
            );
        }

        client.gui.getChat().addClientSystemMessage(Component.translatable("safra.p2p.host.copied"));
        client.gui.getChat().addClientSystemMessage(Component.translatable("safra.p2p.host.instructions"));
        client.getNarrator().saySystemQueued(Component.translatable("safra.p2p.host.narration", shareText));
        safra$startBedrockRelay(client);
    }

    private static void safra$startBedrockRelay(Minecraft client) {
        P2pManager.getInstance().startBedrockRelay(
            address -> client.execute(() -> {
                client.gui.getChat().addClientSystemMessage(
                    Component.translatable("safra.bedrock.host.started", address).copy().withStyle(ChatFormatting.AQUA)
                );
                IntegratedServer server = client.getSingleplayerServer();
                if (server != null && !server.getPlayerList().isUsingWhitelist()) {
                    client.gui.getChat().addClientSystemMessage(
                        Component.translatable("safra.bedrock.whitelist_warning").copy().withStyle(ChatFormatting.RED)
                    );
                }
            }),
            () -> client.execute(() -> client.gui.getChat().addClientSystemMessage(
                Component.translatable("safra.bedrock.host.unavailable").copy().withStyle(ChatFormatting.YELLOW)
            ))
        );
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
        client.gui.getChat().addClientSystemMessage(
            Component.translatable("safra.p2p.host.failed", message).copy().withStyle(ChatFormatting.RED)
        );
    }
}
