package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraft.world.GameType;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
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
import java.util.Collections;
import java.util.Map;

@Mixin(value = IntegratedServer.class, remap = false)
abstract class IntegratedServerMixin {
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Inject(method = {"shareToLAN", "func_195565_a"}, at = @At("HEAD"), remap = false)
    private void safra$applyOnlineMode(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        IntegratedServer server = (IntegratedServer) (Object) this;
        server.setOnlineMode(safra$isOnlineModeEnabled());
        if (safra$isP2pEnabled()) {
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

    @Inject(method = {"shareToLAN", "func_195565_a"}, at = @At("RETURN"), remap = false)
    private void safra$startP2pHost(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        if (!safra$isP2pEnabled()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        IntegratedServer server = (IntegratedServer) (Object) this;
        if (!SafraClientConfig.get().getOpenToLanGameRules().isEmpty()) {
            ForgeLanGameRules.applyToServer(server, safra$getGameRuleSnapshot());
        }
        int tcpPort = server.getServerPort();
        Minecraft client = Minecraft.getInstance();
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.starting"));
        String fixedCode = safra$isFixedCodeEnabled() ? safra$getFixedCode() : null;
        String rawWorldName = safra$getWorldName(server);
        long seed = safra$getSeed(server);
        String worldCode = org.developerkubilay.safra.p2p.P2pHostSupport.formatWorldCode(rawWorldName, seed);
        java.util.UUID hostId = safra$getHostId(client);
        long hostGeneration = P2pManager.getInstance().getHostStartGeneration();
        boolean grantHostOperator = safra$isGrantHostOperatorEnabled();
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, worldCode, () -> client.execute(() -> safra$publishRelayWarning(client)))
            .whenComplete((shareCode, throwable) -> {
            if (client == null) {
                return;
            }

            if (throwable == null) {
                server.execute(() -> org.developerkubilay.safra.client.p2p.SafraHostPermissions.activate(server, hostId, hostGeneration, grantHostOperator));
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

        ITextComponent shareText = safra$buildShareComponent(shareCodeText);
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

    private static ITextComponent safra$buildShareComponent(String shareCodeText) {
        StringTextComponent text = new StringTextComponent(shareCodeText);
        try {
            Object style;
            try {
                style = Style.class.getField("EMPTY").get(null);
            } catch (ReflectiveOperationException ignored) {
                style = Style.class.newInstance();
            }
            ClickEvent click = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, shareCodeText);
            HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TranslationTextComponent("safra.p2p.copy_hint"));
            Object currentStyle = safra$invokeStyleClickEvent(style, click);
            if (currentStyle == null) currentStyle = style;
            currentStyle = safra$invokeStyleHoverEvent(currentStyle, hover);
            if (currentStyle == null) currentStyle = style;
            currentStyle = safra$invokeStyleInsertion(currentStyle, shareCodeText);
            if (currentStyle == null) currentStyle = style;
            text.getClass().getMethod("setStyle", Style.class).invoke(text, currentStyle);
        } catch (ReflectiveOperationException ignored) {
        }
        return safra$withStyle(text, TextFormatting.AQUA, TextFormatting.UNDERLINE);
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
        try {
            return text.getClass().getMethod("mergeStyle", TextFormatting.class).invoke(text, format);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            return text.getClass().getMethod("func_240699_a_", TextFormatting.class).invoke(text, format);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            return text.getClass().getMethod("withStyle", TextFormatting.class).invoke(text, format);
        } catch (ReflectiveOperationException ignored) {
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
        for (String methodName : new String[]{"setClickEvent", "withClickEvent", "func_240715_a_"}) {
            try {
                return style.getClass().getMethod(methodName, ClickEvent.class).invoke(style, event);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return style;
    }

    private static Object safra$invokeStyleHoverEvent(Object style, HoverEvent event) {
        for (String methodName : new String[]{"setHoverEvent", "withHoverEvent", "func_240716_a_"}) {
            try {
                return style.getClass().getMethod(methodName, HoverEvent.class).invoke(style, event);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return style;
    }

    private static Object safra$invokeStyleInsertion(Object style, String insertion) {
        for (String methodName : new String[]{"setInsertion", "withInsertion", "func_240717_a_"}) {
            try {
                return style.getClass().getMethod(methodName, String.class).invoke(style, insertion);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return style;
    }

    private static boolean safra$isGrantHostOperatorEnabled() {
        return safra$getBooleanSetting("isGrantHostOperatorEnabled", false);
    }

    private static java.util.UUID safra$getHostId(Minecraft client) {
        if (client != null && client.player != null && client.player.getGameProfile() != null) {
            return client.player.getGameProfile().getId();
        }
        return null;
    }

    private static String safra$getWorldName(IntegratedServer server) {
        for (String m : new String[]{"getWorldName", "func_230415_a_"}) {
            try {
                Object config = server.getClass().getMethod("getServerConfiguration").invoke(server);
                if (config != null) {
                    Object name = config.getClass().getMethod(m).invoke(config);
                    if (name instanceof String) {
                        return (String) name;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Object config = server.getClass().getMethod("func_230407_a_").invoke(server);
                if (config != null) {
                    Object name = config.getClass().getMethod(m).invoke(config);
                    if (name instanceof String) {
                        return (String) name;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return "World";
    }

    private static long safra$getSeed(IntegratedServer server) {
        for (String m : new String[]{"getSeed", "func_72905_C"}) {
            try {
                Object world = server.getClass().getMethod("func_241755_D_").invoke(server);
                if (world != null) {
                    Object seed = world.getClass().getMethod(m).invoke(world);
                    if (seed instanceof Long) {
                        return (Long) seed;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Object world = server.getClass().getMethod("getOverworld").invoke(server);
                if (world != null) {
                    Object seed = world.getClass().getMethod(m).invoke(world);
                    if (seed instanceof Long) {
                        return (Long) seed;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return 0L;
    }

    private static boolean safra$isP2pEnabled() {
        return safra$getBooleanSetting("isP2pEnabled", true);
    }

    private static boolean safra$isOnlineModeEnabled() {
        return safra$getBooleanSetting("isOnlineModeEnabled", false);
    }

    private static boolean safra$isFixedCodeEnabled() {
        return safra$getBooleanSetting("isFixedCodeEnabled", false);
    }

    private static boolean safra$getBooleanSetting(String methodName, boolean fallback) {
        Object value = safra$invokeLanSessionState(methodName);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static String safra$getFixedCode() {
        Object value = safra$invokeLanSessionState("getFixedCode");
        return value instanceof String ? (String) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> safra$getGameRuleSnapshot() {
        Object value = safra$invokeLanSessionState("getGameRuleSnapshot");
        return value instanceof Map<?, ?> ? (Map<String, String>) value : Collections.<String, String>emptyMap();
    }

    private static Object safra$invokeLanSessionState(String methodName) {
        try {
            Class<?> stateClass = Class.forName("org.developerkubilay.safra.client.p2p.ForgeLanSessionState");
            return stateClass.getMethod(methodName).invoke(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
