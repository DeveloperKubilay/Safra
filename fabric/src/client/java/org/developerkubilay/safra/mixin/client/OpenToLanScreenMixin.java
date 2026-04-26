package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.OpenToLanScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.client.p2p.FabricLanGameRules;
import org.developerkubilay.safra.client.p2p.FabricLanSessionState;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

@Mixin(OpenToLanScreen.class)
abstract class OpenToLanScreenMixin extends Screen {
    @Unique
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");

    @Shadow
    private int port;

    @Shadow
    private TextFieldWidget portField;

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
        if (this.client != null && this.client.getServer() != null) {
            FabricLanSessionState.initializeGameRules(this.client.getServer().getOverworld().getGameRules());
        }

        this.portField.setDimensionsAndPosition(70, 20, this.width / 2 - 80, 156);
        this.safra$p2pButton = this.addDrawableChild(
            ButtonWidget.builder(this.safra$getToggleText(), button -> {
                this.safra$p2pEnabled = !this.safra$p2pEnabled;
                SafraClientConfig.get().setOpenToLanP2pEnabled(this.safra$p2pEnabled);
                button.setMessage(this.safra$getToggleText());
            }).dimensions(this.width / 2 - 5, 156, 85, 20).build()
        );
        this.safra$onlineModeButton = this.addDrawableChild(
            ButtonWidget.builder(this.safra$getOnlineModeText(), button -> {
                this.safra$onlineModeEnabled = !this.safra$onlineModeEnabled;
                SafraClientConfig.get().setOpenToLanOnlineModeEnabled(this.safra$onlineModeEnabled);
                button.setMessage(this.safra$getOnlineModeText());
            }).dimensions(this.width / 2 - 100, 180, 98, 20).build()
        );
        this.safra$serverSettingsButton = this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("safra.p2p.server_settings.short"), button ->
                this.client.setScreen(new org.developerkubilay.safra.client.p2p.SafraLanServerSettingsScreen((Screen) (Object) this))
            ).dimensions(this.width / 2 + 2, 180, 98, 20).build()
        );
        this.safra$p2pInitialized = true;
    }

    @Inject(method = "method_19851", at = @At("HEAD"))
    private void safra$applyOnlineMode(IntegratedServer server, ButtonWidget button, CallbackInfo ci) {
        if (server != null) {
            this.allowCommands = FabricLanSessionState.isAllowCommandsEnabled();
            server.setOnlineMode(this.safra$onlineModeEnabled);
            if (this.safra$p2pEnabled) {
                server.setPreventProxyConnections(false);
            }
            SAFRA_LOGGER.debug(
                "Safra LAN auth settings: onlineMode={}, preventProxyConnections={}, enforceSecureProfile={}",
                server.isOnlineMode(),
                server.shouldPreventProxyConnections(),
                server.shouldEnforceSecureProfile()
            );
        }
    }

    @Inject(method = "method_19851", at = @At("TAIL"))
    private void safra$startP2pHost(IntegratedServer server, ButtonWidget button, CallbackInfo ci) {
        if (server == null || server.getServerPort() != this.port) {
            return;
        }

        FabricLanGameRules.applyToServer(server, FabricLanSessionState.getGameRuleSnapshot());

        if (!this.safra$p2pEnabled) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        int tcpPort = this.port;
        this.client.inGameHud.getChatHud().addMessage(Text.translatable("safra.p2p.host.starting"));
        P2pManager.getInstance().startHostingAsync(tcpPort).whenComplete((shareCode, throwable) -> {
            if (this.client == null) {
                return;
            }

            this.client.execute(() -> {
                if (throwable != null) {
                    safra$publishStartFailure(tcpPort, throwable);
                    return;
                }

                safra$publishShareCode(tcpPort, shareCode);
            });
        });
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void safra$renderHint(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        context.drawCenteredTextWithShadow(
            this.textRenderer,
            Text.translatable("safra.p2p.open_hint"),
            this.width / 2,
            232,
            0xA0A0A0
        );
    }

    @Unique
    private MutableText safra$getToggleText() {
        return Text.translatable(this.safra$p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    @Unique
    private MutableText safra$getOnlineModeText() {
        return Text.translatable(this.safra$onlineModeEnabled ? "safra.p2p.online_mode.short.on" : "safra.p2p.online_mode.short.off");
    }

    @Unique
    private void safra$publishShareCode(int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}", tcpPort, shareCodeText);
        this.client.keyboard.setClipboard(shareCodeText);

        Text shareText = Text.literal(shareCodeText)
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withInsertion(shareCodeText)
                .withClickEvent(new ClickEvent.CopyToClipboard(shareCodeText))
                .withHoverEvent(new HoverEvent.ShowText(Text.translatable("safra.p2p.copy_hint"))));
        this.client.inGameHud.getChatHud().addMessage(Text.translatable("safra.p2p.host.started", shareText));
        this.client.inGameHud.getChatHud().addMessage(Text.translatable("safra.p2p.host.copied"));
        this.client.inGameHud.getChatHud().addMessage(Text.translatable("safra.p2p.host.instructions"));
        this.client.getNarratorManager().narrateSystemMessage(Text.translatable("safra.p2p.host.narration", shareText));
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
        this.client.inGameHud.getChatHud().addMessage(
            Text.translatable("safra.p2p.host.failed", message).copy().formatted(Formatting.RED)
        );
    }
}
