package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.NarratorManager;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
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

@Mixin(OpenToLanScreen.class)
abstract class OpenToLanScreenMixin extends Screen {
    @Unique
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Shadow
    private boolean allowCommands;

    @Unique
    private ButtonWidget safra$p2pButton;

    @Unique
    private ButtonWidget safra$onlineModeButton;

    @Unique
    private ButtonWidget safra$serverSettingsButton;

    @Unique
    private boolean safra$p2pEnabled;

    @Unique
    private boolean safra$onlineModeEnabled;

    @Unique
    private boolean safra$p2pInitialized;

    protected OpenToLanScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void safra$loadLanSettings(CallbackInfo ci) {
        FabricLanSessionState.loadFromConfig();
        this.allowCommands = FabricLanSessionState.isAllowCommandsEnabled();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            SafraClientConfig config = SafraClientConfig.get();
            this.safra$p2pEnabled = config.isOpenToLanP2pEnabled();
            this.safra$onlineModeEnabled = config.isOpenToLanOnlineModeEnabled();
        }
        if (this.minecraft != null) {
            FabricLanSessionState.initializeGameRules(this.minecraft, null);
        }

        this.safra$p2pButton = this.addButton(new ButtonWidget(
            this.width / 2 - 100,
            this.height / 4 + 72,
            98,
            20,
            this.safra$getToggleText(),
            button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setOpenToLanP2pEnabled(this.safra$p2pEnabled);
                button.setMessage(this.safra$getToggleText());
            }
        ));
        this.safra$onlineModeButton = this.addButton(new ButtonWidget(
            this.width / 2 + 2,
            this.height / 4 + 72,
            98,
            20,
            this.safra$getOnlineModeText(),
            button -> {
                this.safra$onlineModeEnabled = !this.safra$onlineModeEnabled;
                SafraClientConfig.get().setOpenToLanOnlineModeEnabled(this.safra$onlineModeEnabled);
                button.setMessage(this.safra$getOnlineModeText());
            }
        ));
        this.safra$serverSettingsButton = this.addButton(new ButtonWidget(
            this.width / 2 - 100,
            this.height / 4 + 96,
            200,
            20,
            new TranslatableText("safra.p2p.server_settings.short").getString(),
            button -> {
                if (this.minecraft != null) {
                    this.minecraft.openScreen(new org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen((Screen) (Object) this));
                }
            }
        ));
        this.safra$p2pInitialized = true;
    }

    @Inject(method = "method_19851", at = @At("HEAD"))
    private void safra$applyOnlineMode(ButtonWidget button, CallbackInfo ci) {
        IntegratedServer server = this.minecraft == null ? null : this.minecraft.getServer();
        if (server != null) {
            this.allowCommands = FabricLanSessionState.isAllowCommandsEnabled();
            server.setOnlineMode(this.safra$onlineModeEnabled);
            if (this.safra$p2pEnabled) {
                server.setPreventProxyConnections(false);
            }
            SAFRA_LOGGER.debug(
                "Safra LAN auth settings: onlineMode={}, preventProxyConnections={}",
                server.isOnlineMode(),
                server.shouldPreventProxyConnections()
            );
        }
    }

    @Inject(method = "method_19851", at = @At("TAIL"))
    private void safra$startP2pHost(ButtonWidget button, CallbackInfo ci) {
        IntegratedServer server = this.minecraft == null ? null : this.minecraft.getServer();
        if (server == null || server.getServerPort() <= 0) {
            return;
        }

        FabricLanGameRules.applyToServer(server, FabricLanSessionState.getGameRuleSnapshot());

        if (!this.safra$p2pEnabled) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        int tcpPort = server.getServerPort();
        this.minecraft.inGameHud.getChatHud().addMessage(new TranslatableText("safra.p2p.host.starting"));
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
    private String safra$getToggleText() {
        return new TranslatableText(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off").getString();
    }

    @Unique
    private String safra$getOnlineModeText() {
        return new TranslatableText(this.safra$onlineModeEnabled ? "safra.p2p.online_mode.short.on" : "safra.p2p.online_mode.short.off").getString();
    }

    @Unique
    private void safra$publishShareCode(int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        this.minecraft.keyboard.setClipboard(shareCodeText);

        Text shareText = new LiteralText(shareCodeText).formatted(Formatting.AQUA);
        this.minecraft.inGameHud.getChatHud().addMessage(new TranslatableText("safra.p2p.host.started", shareText));
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            this.minecraft.inGameHud.getChatHud().addMessage(
                new TranslatableText("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion()).formatted(Formatting.YELLOW)
            );
        }
        this.minecraft.inGameHud.getChatHud().addMessage(new TranslatableText("safra.p2p.host.copied"));
        this.minecraft.inGameHud.getChatHud().addMessage(new TranslatableText("safra.p2p.host.instructions"));
        NarratorManager.INSTANCE.narrate(new TranslatableText("safra.p2p.host.narration", shareCodeText).getString());
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
        this.minecraft.inGameHud.getChatHud().addMessage(
            new TranslatableText("safra.p2p.host.failed", message).copy().formatted(Formatting.RED)
        );
    }
}
