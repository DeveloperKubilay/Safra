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
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.server.ServerWorld;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.client.p2p.SafraHostPermissions;
import org.developerkubilay.safra.client.p2p.ShareCodeClickEvent;
import org.developerkubilay.safra.p2p.P2pHostSupport;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Mixin(value = IntegratedServer.class, remap = false)
abstract class IntegratedServerMixin {
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Inject(method = {"shareToLAN", "func_195565_a"}, at = @At("HEAD"), remap = false)
    private void safra$applyOnlineMode(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        IntegratedServer server = (IntegratedServer) (Object) this;
        server.setOnlineMode(ForgeLanSessionState.isOnlineModeEnabled());
        if (ForgeLanSessionState.isP2pEnabled()) {
            server.setPreventProxyConnections(false);
        }
        if (server.getServerHostname() == null) {
            server.setHostname("127.0.0.1");
        }
        SAFRA_LOGGER.debug("Safra LAN auth settings: onlineMode={}, preventProxyConnections={}",
            server.isServerInOnlineMode(), server.getPreventProxyConnections());
    }

    @Inject(method = {"shareToLAN", "func_195565_a"}, at = @At("RETURN"), remap = false)
    private void safra$startP2pHost(GameType gameType, boolean allowCommands, int port, CallbackInfoReturnable<Boolean> cir) {
        P2pManager manager = P2pManager.getInstance();
        if (!cir.getReturnValueZ() || !ForgeLanSessionState.isP2pEnabled()) {
            manager.stopHosting();
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
        ServerWorld world = server.func_71218_a(DimensionType.OVERWORLD);
        String worldCode = P2pHostSupport.formatWorldCode(server.getWorldName(), world == null ? 0L : world.getSeed());
        UUID hostId = client.player == null ? null : client.player.getGameProfile().getId();
        boolean grantHostOperator = ForgeLanSessionState.isGrantHostOperatorEnabled();
        synchronized (manager) {
            CompletableFuture<P2pShareCode> future = manager.startHostingAsync(tcpPort, fixedCode, worldCode,
                () -> client.execute(() -> {
                    if (client.world != null && client.getIntegratedServer() == server && manager.isHosting()) {
                        safra$publishRelayWarning(client);
                    }
                }));
            long generation = manager.getHostStartGeneration();
            future.whenComplete((shareCode, throwable) -> {
                if (throwable == null) {
                    server.execute(() -> SafraHostPermissions.activate(server, hostId, generation, grantHostOperator));
                }
                client.execute(() -> {
                    if (client.world == null || client.getIntegratedServer() != server || manager.getHostStartGeneration() != generation) {
                        return;
                    }
                    if (throwable != null) {
                        safra$publishStartFailure(client, tcpPort, throwable);
                    } else {
                        safra$publishShareCode(client, tcpPort, shareCode);
                    }
                });
            });
        }
    }

    private static void safra$publishRelayWarning(Minecraft client) {
        client.ingameGUI.getChatGUI().printChatMessage(
            safra$withStyle(new TranslationTextComponent("safra.p2p.host.relay_warning"), TextFormatting.YELLOW));
        client.ingameGUI.getChatGUI().printChatMessage(safra$clickableLink(RemoteRendezvousConfigUpdater.discordUrl()));
        String youtubeUrl = RemoteRendezvousConfigUpdater.youtubeUrl();
        if (!youtubeUrl.isEmpty()) {
            client.ingameGUI.getChatGUI().printChatMessage(safra$clickableLink(youtubeUrl));
        }
    }

    private static void safra$publishShareCode(Minecraft client, int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        boolean hidden = SafraClientConfig.get().isDontSayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, hidden ? "hidden" : shareCodeText);
        client.keyboardListener.setClipboardString(shareCodeText);
        if (!hidden) {
            ITextComponent shareText = new StringTextComponent(shareCodeText).setStyle(new Style()
                .setColor(TextFormatting.AQUA)
                .setUnderlined(true)
                .setClickEvent(new ShareCodeClickEvent(shareCodeText))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TranslationTextComponent("safra.p2p.copy_hint")))
                .setInsertion(shareCodeText));
            client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.started", shareText));
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            client.ingameGUI.getChatGUI().printChatMessage(safra$withStyle(
                new TranslationTextComponent("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion()), TextFormatting.YELLOW));
        }
        if (!hidden) {
            client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.copied"));
        }
        client.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("safra.p2p.host.instructions"));
    }

    private static void safra$publishStartFailure(Minecraft client, int tcpPort, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
        if (cause instanceof CancellationException) {
            return;
        }
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        SAFRA_LOGGER.warn("Safra P2P could not start on local TCP port {}", tcpPort, cause);
        client.ingameGUI.getChatGUI().printChatMessage(safra$withStyle(new TranslationTextComponent("safra.p2p.host.failed", message), TextFormatting.RED));
    }

    private static ITextComponent safra$withStyle(ITextComponent text, TextFormatting color) {
        return text.setStyle(new Style().setColor(color));
    }

    private static ITextComponent safra$clickableLink(String url) {
        return new StringTextComponent(url).setStyle(new Style().setColor(TextFormatting.BLUE).setUnderlined(true)
            .setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }
}
