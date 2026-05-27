package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

public final class SafraDirectConnectScreen extends GuiScreen {
    private final GuiScreen parent;
    private final ServerData serverData;
    private GuiTextField ipField;
    private GuiButton selectButton;
    private boolean p2pEnabled;

    public SafraDirectConnectScreen(GuiScreen parent, ServerData serverData) {
        this.parent = parent;
        this.serverData = serverData;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        Keyboard.enableRepeatEvents(true);
        boolean storedAddress = P2pManager.isP2pStoredAddress(serverData.serverIP);
        this.p2pEnabled = storedAddress || org.developerkubilay.safra.client.config.SafraClientConfig.get().isDirectConnectP2pEnabled();
        String shownAddress = storedAddress ? P2pManager.toDisplayAddress(serverData.serverIP) : serverData.serverIP;
        this.ipField = new GuiTextField(0, this.fontRendererObj, this.width / 2 - 100, 116, 200, 20);
        this.ipField.setMaxStringLength(200);
        this.ipField.setText(shownAddress);
        this.ipField.setFocused(true);

        this.selectButton = this.addButton(new GuiButton(0, this.width / 2 - 100, this.height / 4 + 96, 98, 20, I18n.format("selectServer.select")));
        this.addButton(new GuiButton(1, this.width / 2 + 2, this.height / 4 + 96, 98, 20, I18n.format("gui.cancel")));
        this.addButton(new GuiButton(2, this.width / 2 - 100, this.height / 4 + 120, 200, 20, getToggleText()));
        updateValidation();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 1) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 2) {
            this.p2pEnabled = !this.p2pEnabled;
            org.developerkubilay.safra.client.config.SafraClientConfig.get().setDirectConnectP2pEnabled(this.p2pEnabled);
            button.displayString = getToggleText();
            updateValidation();
            return;
        }
        if (button.id == 0) {
            connect();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.ipField.textboxKeyTyped(typedChar, keyCode)) {
            updateValidation();
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            actionPerformed(this.selectButton);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.ipField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        this.ipField.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, I18n.format("selectServer.direct"), this.width / 2, 20, 0xFFFFFF);
        this.drawString(this.fontRendererObj, I18n.format("addServer.enterIp"), this.width / 2 - 100, 100, 0xA0A0A0);
        this.ipField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void connect() {
        String address = this.ipField.getText().trim();
        serverData.serverIP = address;
        try {
            if (p2pEnabled) {
                if (!P2pManager.isValidP2pAddress(address)) {
                    return;
                }
                serverData.serverIP = P2pManager.toStoredAddress(address);
                P2pManager.RewriteResult rewriteResult = P2pManager.getInstance().createRewrite(serverData);
                this.mc.displayGuiScreen(new GuiConnecting(parent, this.mc, rewriteResult.serverInfo()));
            } else {
                this.mc.displayGuiScreen(new GuiConnecting(parent, this.mc, serverData));
            }
        } catch (IOException exception) {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            this.mc.displayGuiScreen(new net.minecraft.client.gui.GuiDisconnected(parent, "connect.failed", new TextComponentTranslation("safra.p2p.prepare_failed", message)));
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof CancellationException) {
                return;
            }
            this.mc.displayGuiScreen(new net.minecraft.client.gui.GuiDisconnected(parent, "connect.failed", new TextComponentString(cause.getMessage())));
        }
    }

    private void updateValidation() {
        if (this.selectButton != null) {
            String address = this.ipField.getText();
            this.selectButton.enabled = this.p2pEnabled ? P2pManager.isValidP2pAddress(address) : ServerAddress.fromString(address) != null;
        }
    }

    private String getToggleText() {
        return I18n.format(this.p2pEnabled ? "safra.p2p.button.on" : "safra.p2p.button.off");
    }
}
