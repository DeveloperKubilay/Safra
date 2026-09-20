package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiShareToLan;
import net.minecraft.client.resources.I18n;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.world.GameType;
import org.developerkubilay.safra.client.config.RemoteRendezvousConfigUpdater;
import org.developerkubilay.safra.client.config.SafraClientConfig;
import org.developerkubilay.safra.p2p.P2pShareCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

public final class SafraGuiShareToLan extends GuiScreen {
    private static final Logger SAFRA_LOGGER = LoggerFactory.getLogger("Safra P2P");
    private final GuiScreen parent;
    private String gameMode = "survival";
    private boolean allowCommands;
    private GuiButton gameModeButton;
    private GuiButton commandsButton;
    private GuiButton p2pButton;
    private GuiButton onlineModeButton;

    public SafraGuiShareToLan(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        ForgeLanSessionState.loadFromConfig();
        ForgeLanSessionState.initializeGameRules(this.mc);
        this.allowCommands = ForgeLanSessionState.isAllowCommandsEnabled();
        this.buttonList.clear();
        this.gameModeButton = this.addButton(new GuiButton(0, this.width / 2 - 155, this.height - 112, 150, 20, getGameModeText()));
        this.commandsButton = this.addButton(new GuiButton(1, this.width / 2 + 5, this.height - 112, 150, 20, getAllowCommandsText()));
        this.p2pButton = this.addButton(new GuiButton(2, this.width / 2 - 155, this.height - 88, 150, 20, getP2pText()));
        this.onlineModeButton = this.addButton(new GuiButton(3, this.width / 2 + 5, this.height - 88, 150, 20, getOnlineModeText()));
        this.addButton(new GuiButton(4, this.width / 2 - 155, this.height - 52, 150, 20, I18n.format("selectServer.open")));
        this.addButton(new GuiButton(5, this.width / 2 + 5, this.height - 52, 150, 20, I18n.format("gui.cancel")));
        this.addButton(new GuiButton(6, this.width / 2 - 100, this.height - 28, 200, 20, I18n.format("safra.p2p.server_settings.short")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            cycleGameMode();
            button.displayString = getGameModeText();
            return;
        }
        if (button.id == 1) {
            this.allowCommands = !this.allowCommands;
            ForgeLanSessionState.setAllowCommandsEnabled(this.allowCommands);
            button.displayString = getAllowCommandsText();
            return;
        }
        if (button.id == 2) {
            ForgeLanSessionState.setP2pEnabled(!ForgeLanSessionState.isP2pEnabled());
            button.displayString = getP2pText();
            return;
        }
        if (button.id == 3) {
            ForgeLanSessionState.setOnlineModeEnabled(!ForgeLanSessionState.isOnlineModeEnabled());
            button.displayString = getOnlineModeText();
            return;
        }
        if (button.id == 5) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 6) {
            this.mc.displayGuiScreen(new SafraLanServerSettingsScreen(this));
            return;
        }
        if (button.id == 4) {
            openLan();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, I18n.format("lanServer.title"), this.width / 2, 50, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void openLan() {
        IntegratedServer server = this.mc.getIntegratedServer();
        if (server == null) {
            return;
        }

        try {
            for (String getter : new String[]{"getServerHostname", "getServerIp", "func_71221_J"}) {
                try {
                    java.lang.reflect.Method getMethod = server.getClass().getMethod(getter);
                    Object current = getMethod.invoke(server);
                    if (current == null) {
                        for (String setter : new String[]{"setHostname", "setServerIp", "func_71256_d"}) {
                            try {
                                java.lang.reflect.Method setMethod = server.getClass().getMethod(setter, String.class);
                                setMethod.invoke(server, "127.0.0.1");
                                break;
                            } catch (NoSuchMethodException ignored) {}
                        }
                    }
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {
        }

        server.setOnlineMode(ForgeLanSessionState.isOnlineModeEnabled());
        String port = server.shareToLAN(resolveGameType(), this.allowCommands);
        if (port == null) {
            this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("commands.publish.failed"));
            this.mc.displayGuiScreen(null);
            return;
        }

        if (!SafraClientConfig.get().getOpenToLanGameRules().isEmpty()) {
            ForgeLanGameRules.applyToServer(server, ForgeLanSessionState.getGameRuleSnapshot());
        }
        this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("commands.publish.started", port));
        this.mc.displayGuiScreen(null);

        if (!ForgeLanSessionState.isP2pEnabled()) {
            P2pManager.getInstance().stopHosting();
            return;
        }

        final int tcpPort = server.getServerPort();
        this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.starting"));
        String fixedCode = ForgeLanSessionState.isFixedCodeEnabled() ? ForgeLanSessionState.getFixedCode() : null;
        P2pManager.getInstance().startHostingAsync(tcpPort, fixedCode, () -> mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                publishRelayWarning();
            }
        })).whenComplete((shareCode, throwable) -> mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (throwable != null) {
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (cause instanceof CancellationException) {
                        return;
                    }
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    SAFRA_LOGGER.warn("Safra P2P could not start on local TCP port {}", tcpPort, cause);
                    mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.failed", message).setStyle(new Style().setColor(TextFormatting.RED)));
                    return;
                }
                publishShareCode(tcpPort, shareCode);
            }
        }));
    }

    private void publishRelayWarning() {
        this.mc.ingameGUI.getChatGUI().printChatMessage(
            new TextComponentTranslation("safra.p2p.host.relay_warning")
                .setStyle(new Style().setColor(TextFormatting.YELLOW))
        );
        String discordUrl = RemoteRendezvousConfigUpdater.discordUrl();
        this.mc.ingameGUI.getChatGUI().printChatMessage(clickableLink(discordUrl));
        String youtubeUrl = RemoteRendezvousConfigUpdater.youtubeUrl();
        if (!youtubeUrl.isEmpty()) {
            this.mc.ingameGUI.getChatGUI().printChatMessage(clickableLink(youtubeUrl));
        }
    }

    private static TextComponentString clickableLink(String url) {
        TextComponentString link = new TextComponentString(url);
        Style style = new Style().setColor(TextFormatting.BLUE).setUnderlined(Boolean.TRUE);
        style.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        link.setStyle(style);
        return link;
    }

    private void publishShareCode(int tcpPort, P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        boolean hidden = SafraClientConfig.get().isDontSayCode();
        SAFRA_LOGGER.info("Safra P2P server opened on local TCP port {}. Share code: {}",
            tcpPort, hidden ? "hidden" : shareCodeText);
        GuiScreen.setClipboardString(shareCodeText);
        TextComponentString display = new TextComponentString(shareCodeText);
        display.setStyle(new Style().setColor(TextFormatting.AQUA).setUnderlined(Boolean.TRUE));
        if (!hidden) {
            this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.started", display));
        }
        if (RemoteRendezvousConfigUpdater.hasNewerModVersion()) {
            this.mc.ingameGUI.getChatGUI().printChatMessage(
                new TextComponentTranslation("safra.p2p.host.update_available", RemoteRendezvousConfigUpdater.latestModVersion())
                    .setStyle(new Style().setColor(TextFormatting.YELLOW))
            );
        }
        if (!hidden) {
            this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.copied"));
        }
        this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.instructions"));
    }

    private void cycleGameMode() {
        if ("survival".equals(gameMode)) {
            gameMode = "spectator";
        } else if ("spectator".equals(gameMode)) {
            gameMode = "creative";
        } else if ("creative".equals(gameMode)) {
            gameMode = "adventure";
        } else {
            gameMode = "survival";
        }
    }

    private GameType resolveGameType() {
        GameType type = GameType.parseGameTypeWithDefault(gameMode, GameType.SURVIVAL);
        return type == null ? GameType.SURVIVAL : type;
    }

    private String getGameModeText() {
        return I18n.format("selectWorld.gameMode") + ": " + I18n.format("selectWorld.gameMode." + gameMode);
    }

    private String getAllowCommandsText() {
        return I18n.format(this.allowCommands ? "safra.p2p.allow_commands.on" : "safra.p2p.allow_commands.off");
    }

    private String getP2pText() {
        return I18n.format(ForgeLanSessionState.isP2pEnabled() ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }

    private String getOnlineModeText() {
        return I18n.format(ForgeLanSessionState.isOnlineModeEnabled() ? "safra.p2p.online_mode.short.on" : "safra.p2p.online_mode.short.off");
    }
}
