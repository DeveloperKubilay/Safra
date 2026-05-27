package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.IOException;
import java.lang.reflect.Field;

public class SafraGuiMultiplayer extends GuiMultiplayer {
    private static final Field SELECTED_SERVER_FIELD;

    static {
        try {
            SELECTED_SERVER_FIELD = GuiMultiplayer.class.getDeclaredField("selectedServer");
            SELECTED_SERVER_FIELD.setAccessible(true);
        } catch (Exception exception) {
            throw new RuntimeException("Could not access selectedServer field", exception);
        }
    }

    public SafraGuiMultiplayer(GuiScreen parentScreen) {
        super(parentScreen);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 4) {
            this.mc.displayGuiScreen(new SafraDirectConnectScreen(this, new ServerData("", "", false)));
            return;
        }
        if (button.id == 1 && connectSelectedP2p()) {
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    public void connectToSelected() {
        if (connectSelectedP2p()) {
            return;
        }
        super.connectToSelected();
    }

    private boolean connectSelectedP2p() {
        ServerData selected = getSelectedServer();
        if (selected == null || !P2pManager.isP2pStoredAddress(selected.serverIP)) {
            return false;
        }

        ServerData snapshot = new ServerData(selected.serverName, selected.serverIP, selected.isOnLAN());
        snapshot.copyFrom(selected);
        try {
            P2pManager.RewriteResult rewriteResult = P2pManager.getInstance().createRewrite(snapshot);
            this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, rewriteResult.serverInfo()));
            return true;
        } catch (IOException exception) {
            this.mc.displayGuiScreen(new net.minecraft.client.gui.GuiDisconnected(this, "connect.failed", new net.minecraft.util.text.TextComponentString(exception.getMessage())));
            return true;
        }
    }

    private ServerData getSelectedServer() {
        try {
            return (ServerData) SELECTED_SERVER_FIELD.get(this);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }
}
