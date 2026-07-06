package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {
    @Accessor("x")
    void safra$setX(int x);

    @Accessor("y")
    void safra$setY(int y);
}
