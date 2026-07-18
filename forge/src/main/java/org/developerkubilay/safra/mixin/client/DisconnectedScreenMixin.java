package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.util.text.ITextComponent;
import org.developerkubilay.safra.client.p2p.P2pErrorComponents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = DisconnectedScreen.class, remap = false)
abstract class DisconnectedScreenMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void safra$replaceP2pReason(CallbackInfo ci) {
        for (Field field : DisconnectedScreen.class.getDeclaredFields()) {
            if (!ITextComponent.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(this);
                if (value instanceof ITextComponent) {
                    field.set(this, P2pErrorComponents.disconnectFailure((ITextComponent) value));
                }
            } catch (ReflectiveOperationException ignored) {
            }
            return;
        }
    }
}
