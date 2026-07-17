package org.developerkubilay.safra.mixin.client;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
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
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerOptionsScreen.class)
abstract class ShareToLanScreenMixin extends Screen {
    @Unique
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");
    @Unique
    private static final int SAFRA_LAYOUT_Y_OFFSET = -10;

    @Shadow
    private int port;

    @Shadow
    private EditBox portEdit;

    @Shadow
    private boolean commands;

    @Shadow
    private MinecraftServer.MultiplayerScope wantedMultiplayerScope;

    @Shadow
    private MinecraftServer.MultiplayerScope initialMultiplayerScope;

    @Shadow
    private Button applyChanges;

    @Shadow
    private StringWidget portLabel;

    @Unique
    private Button safra$p2pButton;

    @Unique
    private Button safra$onlineModeButton;

    @Unique
    private Button safra$serverSettingsButton;
    @Unique
    private StringWidget safra$lanWorldLabel;
    @Unique
    private StringWidget safra$otherPlayersLabel;
    @Unique
    private StringWidget safra$customPortLabel;

    @Unique
    private boolean safra$p2pInitialized;

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Invoker("updatePortControlsState")
    abstract void safra$invokeUpdatePortControlsState();

    @Invoker("updateApplyChangesActiveState")
    abstract void safra$invokeUpdateApplyChangesActiveState();

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

        this.wantedMultiplayerScope = MinecraftServer.MultiplayerScope.LAN;
        this.safra$invokeUpdatePortControlsState();
        this.safra$lanWorldLabel = this.safra$createLegacyLabel(Component.literal("LAN World"), 62 + SAFRA_LAYOUT_Y_OFFSET);
        this.safra$otherPlayersLabel = this.safra$createLegacyLabel(Component.literal("Settings for Other Players"), 93 + SAFRA_LAYOUT_Y_OFFSET);
        this.safra$customPortLabel = this.safra$createLegacyLabel(Component.literal("Port Number"), 143 + SAFRA_LAYOUT_Y_OFFSET);

        this.safra$p2pButton = this.addRenderableWidget(
            Button.builder(this.safra$getToggleText(), button -> {
                    ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
                    button.setMessage(this.safra$getToggleText());
                })
                .bounds(this.width / 2 - 5, 156 + SAFRA_LAYOUT_Y_OFFSET, 85, 20)
                .build()
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            Button.builder(this.safra$getOnlineModeText(), button -> {
                    ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
                    button.setMessage(this.safra$getOnlineModeText());
                })
                .bounds(this.width / 2 - 100, 180 + SAFRA_LAYOUT_Y_OFFSET, 98, 20)
                .build()
        );
        this.safra$serverSettingsButton = this.addRenderableWidget(
            Button.builder(Component.translatable("safra.p2p.server_settings.short"), button ->
                    this.minecraft.gui.setScreen(new SafraLanServerSettingsScreen((Screen) (Object) this)))
                .bounds(this.width / 2 + 2, 180 + SAFRA_LAYOUT_Y_OFFSET, 98, 20)
                .build()
        );
        this.safra$applyCustomLayout();
        this.safra$p2pInitialized = true;
        this.safra$invokeUpdateApplyChangesActiveState();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void safra$repositionCustomLayout(CallbackInfo ci) {
        this.safra$applyCustomLayout();
    }

    @Inject(method = "publish", at = @At("HEAD"))
    private void safra$applyOnlineMode(IntegratedServer server, MinecraftServer.MultiplayerScope scope, CallbackInfo ci) {
        if (server != null && scope == MinecraftServer.MultiplayerScope.LAN) {
            this.commands = ForgeLanSessionState.isAllowCommandsEnabled();
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
        String fixedCode = ForgeLanSessionState.isFixedCodeEnabled() ? ForgeLanSessionState.getFixedCode() : null;
        safra$addSystemMessage(Component.translatable("safra.p2p.host.starting"));
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> this.minecraft.execute(() -> {
            safra$addSystemMessage(Component.translatable("safra.p2p.host.relay_warning").copy().withStyle(ChatFormatting.YELLOW));
            safra$addSystemMessage(safra$discordLink());
        })).whenComplete((shareCode, throwable) -> {
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
        safra$addSystemMessage(Component.translatable("safra.p2p.host.started", shareText));
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            safra$addSystemMessage(
                Component.translatable("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion())
                    .copy()
                    .withStyle(ChatFormatting.YELLOW)
            );
        }

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
        safra$addSystemMessage(Component.translatable("safra.p2p.host.failed", message).copy().withStyle(ChatFormatting.RED));
    }

    @Unique
    private void safra$layoutVanillaOptionsWidgets() {
        String gameModeLabel = Component.translatable("selectWorld.gameMode").getString();
        String allowCommandsLabel = Component.translatable("selectWorld.allowCommands").getString();
        for (GuiEventListener element : this.children()) {
            if (!(element instanceof AbstractWidget widget)) {
                continue;
            }
            String message = widget.getMessage().getString();
            if (message.contains(gameModeLabel)) {
                widget.active = true;
                widget.visible = true;
                widget.setPosition(this.width / 2 - 151, 108 + SAFRA_LAYOUT_Y_OFFSET);
                widget.setWidth(148);
            } else if (message.contains(allowCommandsLabel)) {
                widget.active = true;
                widget.visible = true;
                widget.setPosition(this.width / 2 + 3, 108 + SAFRA_LAYOUT_Y_OFFSET);
                widget.setWidth(148);
            }
        }
    }

    @Unique
    private void safra$hideLanScopeWidgets() {
        safra$hideWidget(this.portLabel);
        String lanLabel = Component.translatable("menu.multiplayerOptions.lan").getString();
        String otherPlayersLabel = Component.translatable("menu.multiplayerOptions.otherPlayers.header").getString();
        for (GuiEventListener element : this.children()) {
            if (!(element instanceof AbstractWidget widget)) {
                continue;
            }
            if (widget == this.safra$lanWorldLabel || widget == this.safra$otherPlayersLabel || widget == this.safra$customPortLabel) {
                continue;
            }
            String message = widget.getMessage().getString();
            if (message.contains(lanLabel) || message.contains(otherPlayersLabel)) {
                safra$hideWidget(widget);
            }
        }
    }

    @Unique
    private void safra$applyCustomLayout() {
        if (this.portEdit != null) {
            this.portEdit.setPosition(this.width / 2 - 80, 156 + SAFRA_LAYOUT_Y_OFFSET);
            this.portEdit.setWidth(70);
            this.portEdit.setHint(Component.translatable("lanServer.port"));
        }
        this.safra$layoutVanillaOptionsWidgets();
        this.safra$hideLanScopeWidgets();
        if (this.safra$p2pButton != null) {
            this.safra$p2pButton.setPosition(this.width / 2 - 5, 156 + SAFRA_LAYOUT_Y_OFFSET);
        }
        if (this.safra$onlineModeButton != null) {
            this.safra$onlineModeButton.setPosition(this.width / 2 - 100, 180 + SAFRA_LAYOUT_Y_OFFSET);
        }
        if (this.safra$serverSettingsButton != null) {
            this.safra$serverSettingsButton.setPosition(this.width / 2 + 2, 180 + SAFRA_LAYOUT_Y_OFFSET);
        }
        this.safra$positionLegacyLabel(this.safra$lanWorldLabel, 62 + SAFRA_LAYOUT_Y_OFFSET);
        this.safra$positionLegacyLabel(this.safra$otherPlayersLabel, 93 + SAFRA_LAYOUT_Y_OFFSET);
        this.safra$positionLegacyLabel(this.safra$customPortLabel, 143 + SAFRA_LAYOUT_Y_OFFSET);
    }

    @Unique
    private StringWidget safra$createLegacyLabel(Component text, int y) {
        int textWidth = this.font.width(text);
        return this.addRenderableWidget(new StringWidget(this.width / 2 - textWidth / 2, y, textWidth, 9, text, this.font));
    }

    @Unique
    private void safra$positionLegacyLabel(StringWidget widget, int y) {
        if (widget == null) {
            return;
        }
        widget.setPosition(this.width / 2 - widget.getWidth() / 2, y);
    }

    @Unique
    private void safra$hideWidget(AbstractWidget widget) {
        if (widget == null) {
            return;
        }
        widget.active = false;
        widget.visible = false;
        widget.setPosition(-1000, -1000);
    }

    @Unique
    private static Component safra$discordLink() {
        String url = RemoteRendezvousConfigUpdater.discordUrl();
        return Component.literal(url)
            .withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)
            .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url))));
    }

    @Unique
    private void safra$addSystemMessage(Component message) {
        this.minecraft.gui.hud.getChat().addClientSystemMessage(message);
    }
}
