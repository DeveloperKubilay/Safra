package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.world.GameType;
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

    @Inject(method = "shareToLAN", at = @At("HEAD"), remap = false)
    private void safra$applyOnlineMode(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        IntegratedServer server = (IntegratedServer) (Object) this;
        server.setOnlineMode(ForgeLanSessionState.isOnlineModeEnabled());
        if (ForgeLanSessionState.isP2pEnabled()) {
            server.setPreventProxyConnections(false);
        }
        if (server.getServerHostname() == null) {
            server.setHostname("127.0.0.1");
        }
        SAFRA_LOGGER.debug(
            "Safra LAN auth settings: onlineMode={}, preventProxyConnections={}",
            server.isServerInOnlineMode(),
            server.getPreventProxyConnections()
        );
    }

    @Inject(method = "shareToLAN", at = @At("RETURN"), remap = false)
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
        int tcpPort = server.getServerPort();
        Minecraft client = Minecraft.getInstance();
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.starting"));
        String fixedCode = ForgeLanSessionState.isFixedCodeEnabled() ? ForgeLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> client.execute(() -> safra$publishRelayWarning(client)))
            .whenComplete((shareCode, throwable) -> {
                if (client == null) {
                    return;
                }

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
        client.ingameGUI.getChatGUI().printChatMessage(
            safra$withStyle(new TranslationTextComponent("safra.p2p.host.relay_warning"), TextFormatting.YELLOW)
        );
        String discordUrl = RemoteRendezvousConfigUpdater.discordUrl();
        client.ingameGUI.getChatGUI().printChatMessage(safra$clickableLink(discordUrl));
        String youtubeUrl = RemoteRendezvousConfigUpdater.youtubeUrl();
        if (!youtubeUrl.isEmpty()) {
            client.ingameGUI.getChatGUI().printChatMessage(safra$clickableLink(youtubeUrl));
        }
    }

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        boolean hidden = SafraClientConfig.get().isDontSayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}",
            tcpPort, hidden ? "hidden" : shareCodeText);
        client.keyboardListener.setClipboardString(shareCodeText);

        ITextComponent shareText = safra$withStyle(new StringTextComponent(shareCodeText), TextFormatting.AQUA, TextFormatting.UNDERLINE);
        if (!hidden) {
            client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.started", shareText));
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            client.ingameGUI.getChatGUI().printChatMessage(
                safra$withStyle(
                    new TranslationTextComponent("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion()),
                    TextFormatting.YELLOW
                )
            );
        }

        if (!hidden) {
            client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.copied"));
        }
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.instructions"));
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
        client.ingameGUI.getChatGUI().printChatMessage(
            safra$withStyle(new TranslationTextComponent("safra.p2p.host.failed", message), TextFormatting.RED)
        );
    }

    private static ITextComponent safra$withStyle(ITextComponent text, TextFormatting... formats) {
        Object current = text;
        for (TextFormatting format : formats) {
            current = safra$applyFormat(current, format);
        }
        return current instanceof ITextComponent ? (ITextComponent) current : text;
    }

    private static Object safra$applyFormat(Object text, TextFormatting format) {
        for (String methodName : new String[]{"applyTextStyle", "mergeStyle", "func_240699_a_", "withStyle"}) {
            try {
                return text.getClass().getMethod(methodName, TextFormatting.class).invoke(text, format);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return text;
    }

    private static ITextComponent safra$clickableLink(String url) {
        ITextComponent link = safra$withStyle(new StringTextComponent(url), TextFormatting.BLUE, TextFormatting.UNDERLINE);
        try {
            Object style;
            try {
                style = Style.class.getField("EMPTY").get(null);
            } catch (ReflectiveOperationException ignored) {
                style = Style.class.newInstance();
            }
            ClickEvent event = new ClickEvent(ClickEvent.Action.OPEN_URL, url);
            Object clickedStyle = safra$invokeStyleClickEvent(style, event);
            if (clickedStyle != null) {
                link.getClass().getMethod("setStyle", Style.class).invoke(link, clickedStyle);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return link;
    }

    private static Object safra$invokeStyleClickEvent(Object style, ClickEvent event) {
        for (String methodName : new String[]{"setClickEvent", "withClickEvent"}) {
            try {
                return style.getClass().getMethod(methodName, ClickEvent.class).invoke(style, event);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
