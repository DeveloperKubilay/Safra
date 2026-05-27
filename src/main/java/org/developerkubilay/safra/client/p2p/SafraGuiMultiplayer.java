package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ChatComponentText;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class SafraGuiMultiplayer extends GuiMultiplayer {
    private static final Field SELECTION_LIST_FIELD;
    private static final Method GET_SELECTED_INDEX_METHOD;
    private static final Method GET_LIST_ENTRY_METHOD;
    private static final Method GET_SERVER_DATA_METHOD;

    static {
        try {
            SELECTION_LIST_FIELD = GuiMultiplayer.class.getDeclaredField("field_146803_h");
            SELECTION_LIST_FIELD.setAccessible(true);
            Class<?> selectionListClass = Class.forName("net.minecraft.client.gui.ServerSelectionList");
            GET_SELECTED_INDEX_METHOD = selectionListClass.getDeclaredMethod("func_148193_k");
            GET_SELECTED_INDEX_METHOD.setAccessible(true);
            GET_LIST_ENTRY_METHOD = selectionListClass.getDeclaredMethod("getListEntry", Integer.TYPE);
            GET_LIST_ENTRY_METHOD.setAccessible(true);
            Class<?> normalEntryClass = Class.forName("net.minecraft.client.gui.ServerListEntryNormal");
            GET_SERVER_DATA_METHOD = normalEntryClass.getDeclaredMethod("func_148296_a");
            GET_SERVER_DATA_METHOD.setAccessible(true);
        } catch (Exception exception) {
            throw new RuntimeException("Could not access multiplayer selection state", exception);
        }
    }

    public SafraGuiMultiplayer(GuiScreen parentScreen) {
        super(parentScreen);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
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
    public void func_146796_h() {
        if (connectSelectedP2p()) {
            return;
        }
        super.func_146796_h();
    }

    private boolean connectSelectedP2p() {
        ServerData selected = getSelectedServer();
        if (selected == null || !P2pManager.isP2pStoredAddress(selected.serverIP)) {
            return false;
        }

        ServerData snapshot = new ServerData(selected.serverName, selected.serverIP, selected.func_152585_d());
        snapshot.func_152583_a(selected);
        try {
            P2pManager.RewriteResult rewriteResult = P2pManager.getInstance().createRewrite(snapshot);
            this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, rewriteResult.serverInfo()));
            return true;
        } catch (IOException exception) {
            this.mc.displayGuiScreen(new net.minecraft.client.gui.GuiDisconnected(this, "connect.failed", new ChatComponentText(exception.getMessage())));
            return true;
        }
    }

    private ServerData getSelectedServer() {
        try {
            Object selectionList = SELECTION_LIST_FIELD.get(this);
            if (selectionList == null) {
                return null;
            }
            int index = ((Integer) GET_SELECTED_INDEX_METHOD.invoke(selectionList)).intValue();
            if (index < 0) {
                return null;
            }
            Object entry = GET_LIST_ENTRY_METHOD.invoke(selectionList, Integer.valueOf(index));
            if (entry == null || !GET_SERVER_DATA_METHOD.getDeclaringClass().isInstance(entry)) {
                return null;
            }
            return (ServerData) GET_SERVER_DATA_METHOD.invoke(entry);
        } catch (Exception exception) {
            return null;
        }
    }
}
