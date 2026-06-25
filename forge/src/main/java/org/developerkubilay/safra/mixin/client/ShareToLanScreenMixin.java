package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.developerkubilay.safra.client.p2p.ForgeLanGameRules;
import org.developerkubilay.safra.client.p2p.ForgeLanSessionState;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen;
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

@Mixin(MultiplayerOptionsScreen.class)
abstract class ShareToLanScreenMixin extends Screen {
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
    private boolean safra$p2pInitialized;

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void safra$loadLanSettings(CallbackInfo ci) {
        ForgeLanSessionState.loadFromConfig();
        this.commands = ForgeLanSessionState.isAllowCommandsEnabled();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            ForgeLanSessionState.loadFromConfig();
        }
        if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
            ForgeLanSessionState.initializeGameRules(this.minecraft);
        }

        this.safra$p2pButton = this.addRenderableWidget(
            Button.builder(this.safra$getToggleText(), button -> {
                    ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                    button.setMessage(this.safra$getToggleText());
                })
                .bounds(this.width / 2 - 5, this.height - 52, 85, 20)
                .build()
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            Button.builder(this.safra$getOnlineModeText(), button -> {
                    ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                    button.setMessage(this.safra$getOnlineModeText());
                })
                .bounds(this.width / 2 - 100, this.height - 28, 98, 20)
                .build()
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            Button.builder(Component.translatable("safra.p2p.server_settings.short"), button ->
                    this.minecraft.gui.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this)))
                .bounds(this.width / 2 + 2, this.height - 28, 98, 20)
                .build()
        );
        this.safra$p2pInitialized = true;
    }

    @Inject(method = "publish", at = @At("HEAD"))
    private void safra$applyOnlineMode(IntegratedServer server, MinecraftServer.MultiplayerScope scope, CallbackInfo ci) {
        if (server != null && scope == MinecraftServer.MultiplayerScope.LAN) {
            server.setUsesAuthentication(ForgeLanSessionState.isOnlineModeEnabled());
            if (ForgeLanSessionState.isP2pEnabled()) {
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

    @Inject(method = "publish", at = @At("TAIL"))
    private void safra$startP2pHost(IntegratedServer server, MinecraftServer.MultiplayerScope scope, CallbackInfo ci) {
        if (server == null || scope != MinecraftServer.MultiplayerScope.LAN) {
            return;
        }

        ForgeLanGameRules.applyToServer(server, ForgeLanSessionState.getGameRuleSnapshot());

        if (!ForgeLanSessionState.isP2pEnabled()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        int tcpPort = this.port;
        safra$addSystemMessage(Component.translatable("safra.p2p.host.starting"));
        P2pManager.getInstance().startHostingAsync(tcpPort).whenComplete((shareCode, throwable) -> {
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
    private Component safra$getToggleText() {
        return Component.translatable(ForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return Component.translatable(ForgeLanSessionState.isOnlineModeEnabled()
            ? "safra.p2p.online_mode.short.on"
            : "safra.p2p.online_mode.short.off");
    }

    @Unique
    private void safra$publishShareCode(int tcpPort, P2pShareCode shareCode) {
        String code = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, code);
        this.minecraft.keyboardHandler.setClipboard(code);
        safra$addSystemMessage(Component.translatable("safra.p2p.host.started", Component.literal(code)));
        safra$addSystemMessage(Component.translatable("safra.p2p.host.copied"));
        safra$addSystemMessage(Component.translatable("safra.p2p.host.instructions"));
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
        safra$addSystemMessage(Component.translatable("safra.p2p.host.failed", message));
    }

    @Unique
    private void safra$addSystemMessage(Component message) {
        this.minecraft.gui.hud.getChat().addClientSystemMessage(message);
    }
}
