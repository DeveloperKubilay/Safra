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
import net.minecraft.world.GameType;
import org.developerkubilay.safra.p2p.P2pShareCode;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

public final class SafraGuiShareToLan extends GuiScreen {
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
        this.drawCenteredString(this.fontRendererObj, I18n.format("lanServer.title"), this.width / 2, 50, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void openLan() {
        IntegratedServer server = this.mc.getIntegratedServer();
        if (server == null) {
            return;
        }

        server.setOnlineMode(ForgeLanSessionState.isOnlineModeEnabled());
        String port = server.shareToLAN(resolveGameType(), this.allowCommands);
        if (port == null) {
            this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("commands.publish.failed"));
            this.mc.displayGuiScreen(null);
            return;
        }

        ForgeLanGameRules.applyToServer(server, ForgeLanSessionState.getGameRuleSnapshot());
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
                mc.ingameGUI.getChatGUI().printChatMessage(
                    new TextComponentTranslation("safra.p2p.host.relay_warning")
                        .setStyle(new Style().setColor(TextFormatting.YELLOW))
                );
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
                    mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.failed", message).setStyle(new Style().setColor(TextFormatting.RED)));
                    return;
                }
                publishShareCode(shareCode);
            }
        }));
    }

    private void publishShareCode(P2pShareCode shareCode) {
        String shareCodeText = shareCode.toDisplayCode();
        GuiScreen.setClipboardString(shareCodeText);
        TextComponentString display = new TextComponentString(shareCodeText);
        display.setStyle(new Style().setColor(TextFormatting.AQUA).setUnderlined(true));
        this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.started", display));
        this.mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("safra.p2p.host.copied"));
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
