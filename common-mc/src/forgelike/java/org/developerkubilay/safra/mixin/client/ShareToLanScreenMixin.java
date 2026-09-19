package org.developerkubilay.safra.mixin.client;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.LanGameRules;
import org.developerkubilay.safra.client.p2p.LanSessionState;
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

@Mixin(WorldOptionsScreen.class)
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
    private Boolean wantedAllowCommands;

    @Shadow
    private MinecraftServer.MultiplayerScope wantedMultiplayerScope;

    @Shadow
    private MinecraftServer.MultiplayerScope initialMultiplayerScope;

    @Shadow
    private Button applyChanges;

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
        LanSessionState.loadFromConfig();
        this.wantedAllowCommands = LanSessionState.isAllowCommandsEnabled();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void safra$initP2pUi(CallbackInfo ci) {
        if (!this.safra$p2pInitialized) {
            LanSessionState.loadFromConfig();
        }
        if (this.minecraft != null && this.minecraft.getSingleplayerServer() != null) {
            LanSessionState.initializeGameRules(this.minecraft);
        }

        this.wantedMultiplayerScope = MinecraftServer.MultiplayerScope.LAN;
        this.safra$invokeUpdatePortControlsState();
        this.safra$lanWorldLabel = this.safra$createLegacyLabel(Component.literal("LAN World"), 62 + SAFRA_LAYOUT_Y_OFFSET);
        this.safra$otherPlayersLabel = this.safra$createLegacyLabel(Component.literal("Settings for Other Players"), 93 + SAFRA_LAYOUT_Y_OFFSET);
        this.safra$customPortLabel = this.safra$createLegacyLabel(Component.literal("Port Number"), 143 + SAFRA_LAYOUT_Y_OFFSET);

        this.safra$p2pButton = this.addRenderableWidget(
            Button.builder(this.safra$getToggleText(), button -> {
                    LanSessionState.setP2pEnabled(!LanSessionState.isP2pEnabled());
                    button.setMessage(this.safra$getToggleText());
                })
                .bounds(this.width / 2 - 5, 156 + SAFRA_LAYOUT_Y_OFFSET, 85, 20)
                .build()
        );
        this.safra$onlineModeButton = this.addRenderableWidget(
            Button.builder(this.safra$getOnlineModeText(), button -> {
                    LanSessionState.setOnlineModeEnabled(!LanSessionState.isOnlineModeEnabled());
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
            this.wantedAllowCommands = LanSessionState.isAllowCommandsEnabled();
            server.setUsesAuthentication(LanSessionState.isOnlineModeEnabled());
            if (LanSessionState.isP2pEnabled()) {
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

        if (!SafraClientConfig.get().getOpenToLanGameRules().isEmpty()) {
            LanGameRules.applyToServer(server, LanSessionState.getGameRuleSnapshot());
        }

        if (!LanSessionState.isP2pEnabled()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        int tcpPort = this.port;
        String fixedCode = LanSessionState.isFixedCodeEnabled() ? LanSessionState.getFixedCode() : null;
        safra$addSystemMessage(Component.translatable("safra.p2p.host.starting"));
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> this.minecraft.execute(() -> {
            safra$addChatLines(Component.translatable("safra.p2p.host.relay_warning"), ChatFormatting.YELLOW);
            safra$addSystemMessage(Component.literal("Discord: ").append(safra$discordLink()));
            String youtubeUrl = RemoteRendezvousConfigUpdater.youtubeUrl();
            if (!youtubeUrl.isBlank()) {
                safra$addSystemMessage(Component.literal("Youtube: ").append(safra$youtubeLink(youtubeUrl)));
            }
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
        return Component.translatable(LanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private Component safra$getOnlineModeText() {
        return Component.translatable(LanSessionState.isOnlineModeEnabled()
            ? "safra.p2p.online_mode.short.on"
            : "safra.p2p.online_mode.short.off");
    }

    @Unique
    private void safra$publishShareCode(int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        boolean hidden = SafraClientConfig.get().isDontSayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}",
            tcpPort, hidden ? "hidden" : shareCodeText);
        this.minecraft.keyboardHandler.setClipboard(shareCodeText);

        Component shareText = Component.literal(shareCodeText)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withInsertion(shareCodeText)
                .withClickEvent(new ClickEvent.CopyToClipboard(shareCodeText))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("safra.p2p.copy_hint"))));
        if (!hidden) {
            safra$addSystemMessage(Component.translatable("safra.p2p.host.started", shareText));
        }
        if (!shareCode.isRendezvous()) {
            safra$addChatLines(
                Component.literal("Safra Error: ").append(Component.translatable("safra.p2p.error.direct_fallback")),
                ChatFormatting.RED);
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            safra$addSystemMessage(
                Component.translatable("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion())
                    .copy()
                    .withStyle(ChatFormatting.YELLOW)
            );
        }

        if (!hidden) {
            safra$addSystemMessage(Component.translatable("safra.p2p.host.copied"));
            this.minecraft.getNarrator().saySystemQueued(Component.translatable("safra.p2p.host.narration", shareText));
        }
        safra$addSystemMessage(Component.translatable("safra.p2p.host.instructions"));
        safra$startBedrockRelay();
    }

    @Unique
    private void safra$startBedrockRelay() {
        P2pManager.getInstance().startBedrockRelay(
            address -> this.minecraft.execute(() -> {
                safra$addSystemMessage(
                    Component.translatable("safra.bedrock.host.started", address).copy().withStyle(ChatFormatting.AQUA)
                );
                IntegratedServer server = this.minecraft.getSingleplayerServer();
                if (server != null && !server.getPlayerList().isUsingWhitelist()) {
                    safra$addSystemMessage(
                        Component.translatable("safra.bedrock.whitelist_warning").copy().withStyle(ChatFormatting.RED)
                    );
                }
            }),
            () -> this.minecraft.execute(() -> safra$addSystemMessage(
                Component.translatable("safra.bedrock.host.unavailable").copy().withStyle(ChatFormatting.YELLOW)
            ))
        );
    }

    @Unique
    private void safra$addChatLines(Component message, ChatFormatting colour) {
        for (String line : message.getString().split("\n")) {
            safra$addSystemMessage(Component.literal(line).withStyle(colour));
        }
    }

    @Unique
    private static Component safra$youtubeLink(String url) {
        return Component.literal(url)
            .withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)
            .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url))));
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
