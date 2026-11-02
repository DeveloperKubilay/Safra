package org.developerkubilay.safra.mixin.client;

import com.google.gson.JsonObject;
import net.minecraft.server.players.StoredUserEntry;
import org.developerkubilay.safra.client.p2p.SafraStoredUserEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(StoredUserEntry.class)
abstract class StoredUserEntryMixin implements SafraStoredUserEntry {
    @Shadow
    protected abstract void serialize(JsonObject jsonObject);

    @Override
    public void safra$serialize(JsonObject jsonObject) {
        this.serialize(jsonObject);
    }
}
