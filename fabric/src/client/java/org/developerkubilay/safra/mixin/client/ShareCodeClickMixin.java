package org.developerkubilay.safra.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import org.developerkubilay.safra.client.p2p.P2pManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
abstract class ShareCodeClickMixin {
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void safra$copyShareCode(Text text, CallbackInfoReturnable<Boolean> cir) {
        if (text == null || Screen.hasShiftDown()) {
            return;
        }
        ClickEvent event = text.getStyle().getClickEvent();
        if (event == null || event.getAction() != ClickEvent.Action.SUGGEST_COMMAND
            || !event.getValue().startsWith(P2pManager.COPY_SHARE_CODE_PREFIX)) {
            return;
        }
        String shareCode = event.getValue().substring(P2pManager.COPY_SHARE_CODE_PREFIX.length());
        if (P2pManager.isValidP2pAddress(shareCode)) {
            MinecraftClient.getInstance().keyboard.setClipboard(shareCode);
            cir.setReturnValue(true);
        }
    }
}
