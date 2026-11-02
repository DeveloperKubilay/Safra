package org.developerkubilay.safra.mixin.client;

import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import org.developerkubilay.safra.client.p2p.SafraStoredUserList;
import org.developerkubilay.safra.client.p2p.SafraWorldOpsManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(StoredUserList.class)
abstract class StoredUserListMixin<K, V extends StoredUserEntry<K>> implements SafraStoredUserList<K, V> {
    @Shadow
    @Final
    private Map<String, V> map;

    @Override
    public Map<String, V> safra$getMap() {
        return this.map;
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void safra$interceptSave(CallbackInfo ci) {
        if (SafraWorldOpsManager.isRecognizedOpsList((StoredUserList<?, ?>) (Object) this)) {
            ci.cancel();
            SafraWorldOpsManager.handleSave((StoredUserList<?, ?>) (Object) this);
        }
    }

    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void safra$interceptLoad(CallbackInfo ci) {
        if (SafraWorldOpsManager.isRecognizedOpsList((StoredUserList<?, ?>) (Object) this)) {
            ci.cancel();
            SafraWorldOpsManager.handleLoad((StoredUserList<?, ?>) (Object) this);
        }
    }
}
