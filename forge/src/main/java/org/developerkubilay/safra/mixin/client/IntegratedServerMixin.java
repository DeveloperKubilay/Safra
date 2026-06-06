package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameType;
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

@Mixin(value = IntegratedServer.class, remap = false)
abstract class IntegratedServerMixin {
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Inject(method = "shareToLAN", at = @At("HEAD"), remap = false)
    private void safra$applyOnlineMode(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        IntegratedServer server = (IntegratedServer) (Object) this;
        server.setOnlineMode(ForgeLanSessionState.isOnlineModeEnabled());
        if (ForgeLanSessionState.isP2pEnabled()) {
            server.setPreventProxyConnections(false);
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
        ForgeLanGameRules.applyToServer(server, ForgeLanSessionState.getGameRuleSnapshot());
        int tcpPort = server.getServerPort();
        Minecraft client = Minecraft.getInstance();
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.starting"));
        String fixedCode = ForgeLanSessionState.isFixedCodeEnabled() ? ForgeLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode).whenComplete((shareCode, throwable) -> {
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

        ITextComponent shareText = new StringTextComponent(shareCodeText).mergeStyle(TextFormatting.AQUA, TextFormatting.UNDERLINE);
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.started", shareText));
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
            new TranslationTextComponent("safra.p2p.host.failed", message).mergeStyle(TextFormatting.RED)
        );
    }
}
