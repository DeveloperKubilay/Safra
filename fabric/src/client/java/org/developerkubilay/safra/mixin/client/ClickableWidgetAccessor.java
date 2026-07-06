package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClickableWidget.class)
public interface ClickableWidgetAccessor {
    @Accessor("x")
    void safra$setX(int x);

    @Accessor("y")
    void safra$setY(int y);
}
