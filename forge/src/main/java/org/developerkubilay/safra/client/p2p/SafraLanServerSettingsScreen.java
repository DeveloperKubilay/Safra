package org.developerkubilay.safra.client.p2p;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.io.IOException;

public final class SafraLanServerSettingsScreen extends GuiScreen {
    private final GuiScreen parent;
    private GuiButton allowCommandsButton;

    public SafraLanServerSettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.allowCommandsButton = this.addButton(new GuiButton(0, this.width / 2 - 100, this.height / 4 + 24, 200, 20, getAllowCommandsText()));
        this.addButton(new GuiButton(1, this.width / 2 - 100, this.height / 4 + 48, 200, 20, I18n.format("safra.p2p.server_settings.reset")));
        this.addButton(new GuiButton(2, this.width / 2 - 100, this.height / 4 + 96, 98, 20, I18n.format("gui.done")));
        this.addButton(new GuiButton(3, this.width / 2 + 2, this.height / 4 + 96, 98, 20, I18n.format("gui.back")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            ForgeLanSessionState.setAllowCommandsEnabled(!ForgeLanSessionState.isAllowCommandsEnabled());
            button.displayString = getAllowCommandsText();
        } else if (button.id == 1) {
            ForgeLanSessionState.resetServerSettings();
            if (allowCommandsButton != null) {
                allowCommandsButton.displayString = getAllowCommandsText();
            }
        } else if (button.id == 2 || button.id == 3) {
            this.mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, I18n.format("safra.p2p.server_settings"), this.width / 2, this.height / 4, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String getAllowCommandsText() {
        return I18n.format(ForgeLanSessionState.isAllowCommandsEnabled() ? "safra.p2p.allow_commands.on" : "safra.p2p.allow_commands.off");
    }
}
