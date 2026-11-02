package org.developerkubilay.safra.client.p2p;

import net.minecraft.server.players.StoredUserEntry;

import java.util.Map;

public interface SafraStoredUserList<K, V extends StoredUserEntry<K>> {
    Map<String, V> safra$getMap();
}
