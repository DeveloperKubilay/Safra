package org.developerkubilay.safra.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.FabricLanGameRules;
import org.developerkubilay.safra.client.p2p.FabricLanSessionState;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(ShareToLanScreen.class)
abstract class OpenToLanScreenMixin extends Screen {
    @Unique
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Shadow
    private int port;

    @Shadow
    private EditBox portEdit;

    @Shadow
    private boolean commands;

    @Unique
    private Button safra$p2pButton;

    @Unique
    private Button safra$onlineModeButton;

    @Unique
    private Button safra$serverSettingsButton;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$onlineModeEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected OpenToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void safra$loadLanSettings(CallbackInfo ci) {
        FabricLanSessionState.loadFromConfig();
        this.commands = FabricLanSessionState.isAllowCommandsEnabled();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            SafraClientConfig config = SafraClientConfig.get();
            this.safra$p2pEnabled = config.isOpenToLanP2pEnabled();
            this.safra$onlineModeEnabled = config.isOpenToLanOnlineModeEnabled();
        }
        if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
            FabricLanSessionState.initializeGameRules(this.minecraft, this.minecraft.getSingleplayerServer().overworld().getGameRules());
        }

        this.portEdit.setRectangle(70, 20, this.width / 2 - 80, 156);
        this.safra$p2pButton = this.addRenderableWidget(
            Button.builder(this.safra$getToggleText(), button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setOpenToLanP2pEnabled(this.safra$p2pEnabled);
                button.setMessage(this.safra$getToggleText());
            }).bounds(this.width / 2 - 5, 156, 85, 20).build()
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            Button.builder(this.safra$getOnlineModeText(), button -> {
                this.safra$onlineModeEnabled = !this.safra$onlineModeEnabled;
                SafraClientConfig.get().setOpenToLanOnlineModeEnabled(this.safra$onlineModeEnabled);
                button.setMessage(this.safra$getOnlineModeText());
            }).bounds(this.width / 2 - 100, 180, 98, 20).build()
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            Button.builder(Component.translatable("safra.p2p.server_settings.short"), button ->
                this.minecraft.setScreenAndShow(new org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen((Screen) (Object) this))
            ).bounds(this.width / 2 + 2, 180, 98, 20).build()
        );
        this.safra$p2pInitialized = true;
    }

    @Inject(method = {"method_19851", "lambda$init$2"}, at = @At("HEAD"), require = 0)
    private void safra$applyOnlineMode(IntegratedServer server, Button button, CallbackInfo ci) {
        if (server != null) {
            this.commands = FabricLanSessionState.isAllowCommandsEnabled();
            server.setUsesAuthentication(this.safra$onlineModeEnabled);
            if (this.safra$p2pEnabled) {
                server.setPreventProxyConnections(false);
            }
            SAFRA_LOGGER.debug(
                "Safra LAN auth settings: onlineMode={}, preventProxyConnections={}, enforceSecureProfile={}",
                server.usesAuthentication(),
                server.getPreventProxyConnections(),
                server.enforceSecureProfile()
            );
        }
    }

    @Inject(method = {"method_19851", "lambda$init$2"}, at = @At("TAIL"), require = 0)
    private void safra$startP2pHost(IntegratedServer server, Button button, CallbackInfo ci) {
        if (server == null || server.getPort() != this.port) {
            return;
        }

        FabricLanGameRules.applyToServer(server, FabricLanSessionState.getGameRuleSnapshot());

        if (!this.safra$p2pEnabled) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        int tcpPort = this.port;
        this.safra$addClientSystemMessage(Component.translatable("safra.p2p.host.starting"));
        String fixedCode = FabricLanSessionState.isFixedCodeEnabled() ? FabricLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode).whenComplete((shareCode, throwable) -> {
            if (this.minecraft == null) {
                return;
            }

            this.minecraft.execute(() -> {
                if (throwable != null) {
                    safra$publishStartFailure(tcpPort, throwable);
                    return;
                }

                safra$publishShareCode(tcpPort, shareCode);
            });
        });
    }

    @Unique
    private MutableComponent safra$getToggleText() {
        return Component.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private MutableComponent safra$getOnlineModeText() {
        return Component.translatable(this.safra$onlineModeEnabled ? "safra.p2p.online_mode.short.on" : "safra.p2p.online_mode.short.off");
    }

    @Unique
    private void safra$publishShareCode(int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        this.minecraft.keyboardHandler.setClipboard(shareCodeText);

        Component shareText = Component.literal(shareCodeText)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withInsertion(shareCodeText)
                .withClickEvent(new ClickEvent.CopyToClipboard(shareCodeText))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("safra.p2p.copy_hint"))));
        this.safra$addClientSystemMessage(Component.translatable("safra.p2p.host.started", shareText));
        if (!shareCode.isRendezvous()) {
            this.safra$addClientSystemMessage(
                Component.literal("Safra Error: ")
                    .append(Component.translatable("safra.p2p.error.direct_fallback"))
                    .withStyle(ChatFormatting.RED)
            );
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            this.safra$addClientSystemMessage(
                Component.translatable("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion())
                    .copy()
                    .withStyle(ChatFormatting.YELLOW)
            );
        }

        this.safra$addClientSystemMessage(Component.translatable("safra.p2p.host.copied"));
        this.safra$addClientSystemMessage(Component.translatable("safra.p2p.host.instructions"));
        this.minecraft.getNarrator().saySystemQueued(Component.translatable("safra.p2p.host.narration", shareText));
        this.safra$startBedrockRelay();
    }

    @Unique
    private void safra$startBedrockRelay() {
        P2pManager.getInstance().startBedrockRelay(
            address -> this.minecraft.execute(() -> {
                this.safra$addClientSystemMessage(Component.translatable("safra.bedrock.host.started", address).copy().withStyle(ChatFormatting.AQUA));
                IntegratedServer server = this.minecraft.getSingleplayerServer();
                if (server != null && !server.getPlayerList().isUsingWhitelist()) {
                    this.safra$addClientSystemMessage(Component.translatable("safra.bedrock.whitelist_warning").copy().withStyle(ChatFormatting.RED));
                }
            }),
            () -> this.minecraft.execute(() -> this.safra$addClientSystemMessage(Component.translatable("safra.bedrock.host.unavailable").copy().withStyle(ChatFormatting.YELLOW)))
        );
    }

    @Unique
    private void safra$publishStartFailure(int tcpPort, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
            ? throwable.getCause()
            : throwable;
        if (cause instanceof CancellationException) {
            return;
        }

        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        SAFRA_LOGGER.warn("Safra P2P could not start on local TCP port {}", tcpPort, cause);
        this.safra$addClientSystemMessage(
            Component.translatable("safra.p2p.host.failed", message).copy().withStyle(ChatFormatting.RED)
        );
    }

    @Unique
    private void safra$addClientSystemMessage(Component message) {
        this.minecraft.gui.getChat().addMessage(message);
    }
}
