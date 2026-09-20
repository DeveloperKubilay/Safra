package org.developerkubilay.safra.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
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
        try {
            for (String getter : new String[]{"getServerHostname", "getServerIp", "func_71221_J"}) {
                try {
                    Object host = server.getClass().getMethod(getter).invoke(server);
                    if (host == null) {
                        for (String setter : new String[]{"setHostname", "setServerIp", "func_71256_d"}) {
                            try {
                                server.getClass().getMethod(setter, String.class).invoke(server, "127.0.0.1");
                                break;
                            } catch (NoSuchMethodException ignored) {}
                        }
                    }
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {
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
        if (!SafraClientConfig.get().getOpenToLanGameRules().isEmpty()) {
            ForgeLanGameRules.applyToServer(server, ForgeLanSessionState.getGameRuleSnapshot());
        }
        int tcpPort = server.getPort();
        Minecraft client = Minecraft.getInstance();
        client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.starting"));
        String fixedCode = ForgeLanSessionState.isFixedCodeEnabled() ? ForgeLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> client.execute(() -> safra$publishRelayWarning(client)))
            .whenComplete((shareCode, throwable) -> {
                client.execute(() -> {
                    if (throwable != null) {
                        safra$publishStartFailure(client, tcpPort, throwable);
                        return;
                    }

                    safra$publishShareCode(client, tcpPort, shareCode);
                });
            });
    }

    private static void safra$publishRelayWarning(Minecraft client) {
        client.gui.getChat().addMessage(
            Component.translatable("safra.p2p.host.relay_warning").copy().withStyle(ChatFormatting.YELLOW)
        );
        String discordUrl = RemoteRendezvousConfigUpdater.discordUrl();
        client.gui.getChat().addMessage(safra$clickableLink(discordUrl));
        String youtubeUrl = RemoteRendezvousConfigUpdater.youtubeUrl();
        if (!youtubeUrl.isEmpty()) {
            client.gui.getChat().addMessage(safra$clickableLink(youtubeUrl));
        }
    }

    private static Component safra$clickableLink(String url) {
        Component link = Component.literal(url).withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE);
        try {
            Class<?> clickEventClass = Class.forName("net.minecraft.network.chat.ClickEvent");
            Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
            Object action = Enum.valueOf((Class<Enum>) actionClass, "OPEN_URL");
            Object clickEvent = clickEventClass.getConstructor(actionClass, String.class).newInstance(action, url);
            Object style = null;
            try {
                style = Class.forName("net.minecraft.network.chat.Style").getField("EMPTY").get(null);
            } catch (Throwable ignored) {
                style = Class.forName("net.minecraft.network.chat.Style").newInstance();
            }
            for (String name : new String[]{"withClickEvent", "setClickEvent"}) {
                try {
                    style = style.getClass().getMethod(name, clickEventClass).invoke(style, clickEvent);
                    break;
                } catch (Throwable ignored) {}
            }
            for (String name : new String[]{"withStyle", "setStyle"}) {
                try {
                    link = (Component) link.getClass().getMethod(name, style.getClass()).invoke(link, style);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
        return link;
    }

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        boolean hidden = SafraClientConfig.get().isDontSayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}",
            tcpPort, hidden ? "hidden" : shareCodeText);
        client.keyboardHandler.setClipboard(shareCodeText);

        Component shareText = Component.literal(shareCodeText).withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
        if (!hidden) {
            client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.started", shareText));
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            client.gui.getChat().addMessage(
                Component.translatable("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion()).copy().withStyle(ChatFormatting.YELLOW)
            );
        }

        if (!hidden) {
            client.gui.getChat().addMessage(Component.translatable("safra.p2p.host.copied"));
        }
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
