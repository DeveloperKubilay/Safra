package org.developerkubilay.safra.client.p2p;

import net.minecraft.util.text.event.ClickEvent;

public final class ShareCodeClickEvent extends ClickEvent {
    public ShareCodeClickEvent(String shareCode) {
        super(Action.SUGGEST_COMMAND, shareCode);
    }
}
