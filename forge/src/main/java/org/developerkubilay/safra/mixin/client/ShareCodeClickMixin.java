package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.event.ClickEvent;
import org.developerkubilay.safra.client.p2p.ShareCodeClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Screen.class, remap = false)
abstract class ShareCodeClickMixin {
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void safra$copyShareCode(ITextComponent component, CallbackInfoReturnable<Boolean> cir) {
        if (component == null || Screen.hasShiftDown()) {
            return;
        }
        ClickEvent click = component.getStyle().getClickEvent();
        if (click instanceof ShareCodeClickEvent) {
            Minecraft.getInstance().keyboardListener.setClipboardString(click.getValue());
            cir.setReturnValue(true);
        }
    }
}
