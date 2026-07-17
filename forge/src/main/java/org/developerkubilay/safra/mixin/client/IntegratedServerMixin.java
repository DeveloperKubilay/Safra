package org.developerkubilay.safra.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
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
        server.setUsesAuthentication(ForgeLanSessionState.isOnlineModeEnabled());
        if (ForgeLanSessionState.isP2pEnabled()) {
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

        if (!ForgeLanSessionState.isP2pEnabled()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        IntegratedServer server = (IntegratedServer) (Object) this;
        ForgeLanGameRules.applyToServer(server, ForgeLanSessionState.getGameRuleSnapshot());
        int tcpPort = server.getPort();
        Minecraft client = Minecraft.getInstance();
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.starting"));
        String fixedCode = ForgeLanSessionState.isFixedCodeEnabled() ? ForgeLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> client.execute(() -> {
            client.gui.getChat().addMessage(
                Component.translatable("safra.p2p.host.relay_warning").copy().withStyle(ChatFormatting.YELLOW)
            );
            client.gui.getChat().addMessage(safra$discordLink());
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
            .withStyle(style -> safra$withOpenUrl(style, url));
    }

    private static net.minecraft.network.chat.Style safra$withOpenUrl(net.minecraft.network.chat.Style style, String url) {
        try {
            Class<?> clickEventClass = Class.forName("net.minecraft.network.chat.ClickEvent");
            Object clickEvent;
            try {
                Class<?> openUrlClass = Class.forName("net.minecraft.network.chat.ClickEvent$OpenUrl");
                clickEvent = openUrlClass.getConstructor(java.net.URI.class).newInstance(java.net.URI.create(url));
            } catch (ReflectiveOperationException ignored) {
                Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
                @SuppressWarnings({"rawtypes", "unchecked"}) Object action = Enum.valueOf((Class) actionClass, "OPEN_URL");
                clickEvent = clickEventClass.getConstructor(actionClass, String.class).newInstance(action, url);
            }
            for (java.lang.reflect.Method method : style.getClass().getMethods()) {
                if (method.getName().equals("withClickEvent") && method.getParameterCount() == 1) {
                    Object styled = method.invoke(style, clickEvent);
                    if (styled instanceof net.minecraft.network.chat.Style result) {
                        return result;
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return style;
    }

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        client.keyboardHandler.setClipboard(shareCodeText);

        Component shareText = Component.literal(shareCodeText).withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.started", shareText));
        if (!shareCode.isRendezvous()) {
            client.gui.getChat().addMessage(Component.literal("Safra Error: ").append(Component.translatable("safra.p2p.error.direct_fallback")).withStyle(ChatFormatting.RED));
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            client.gui.getChat().addMessage(
                Component.translatable("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion()).copy().withStyle(ChatFormatting.YELLOW)
            );
        }

        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.copied"));
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.instructions"));
        safra$startBedrockRelay(client);
    }

    private static void safra$startBedrockRelay(Minecraft client) {
        P2pManager.getInstance().startBedrockRelay(
            address -> client.execute(() -> {
                client.gui.getChat().addMessage(Component.translatable("safra.bedrock.host.started", address).copy().withStyle(ChatFormatting.AQUA));
                IntegratedServer server = client.getSingleplayerServer();
                if (server != null && !server.getPlayerList().isUsingWhitelist()) {
                    client.gui.getChat().addMessage(Component.translatable("safra.bedrock.whitelist_warning").copy().withStyle(ChatFormatting.RED));
                }
            }),
            () -> client.execute(() -> client.gui.getChat().addMessage(Component.translatable("safra.bedrock.host.unavailable").copy().withStyle(ChatFormatting.YELLOW)))
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
        client.gui.getChat().addMessage(
            Component.translatable("safra.p2p.host.failed", message).copy().withStyle(ChatFormatting.RED)
        );
    }
}
