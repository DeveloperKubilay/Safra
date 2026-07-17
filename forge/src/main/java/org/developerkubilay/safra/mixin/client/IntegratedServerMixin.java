package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameType;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
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
        ForgeLanGameRules.applyToServer(server, safra$getGameRuleSnapshot());
        int tcpPort = server.getServerPort();
        Minecraft client = Minecraft.getInstance();
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.starting"));
        String fixedCode = safra$isFixedCodeEnabled() ? safra$getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> client.execute(() ->
            client.ingameGUI.getChatGUI().printChatMessage(
                new TranslationTextComponent("safra.p2p.host.relay_warning").mergeStyle(TextFormatting.YELLOW)
            )
        )).whenComplete((shareCode, throwable) -> {
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

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        client.keyboardListener.setClipboardString(shareCodeText);

        ITextComponent shareText = safra$withStyle(new StringTextComponent(shareCodeText), TextFormatting.AQUA, TextFormatting.UNDERLINE);
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.started", shareText));
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            client.ingameGUI.getChatGUI().printChatMessage(
                safra$withStyle(
                    new TranslationTextComponent("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion()),
                    TextFormatting.YELLOW
                )
            );
        }

        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.copied"));
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
