package io.izzel.arclight.common.mixin.core.server.players;

import io.izzel.arclight.common.bridge.core.server.players.StoredUserListBridge;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;
import java.util.Map;

/**
 * Mixin for {@link StoredUserList} that exposes the internal entry map
 * via the {@link StoredUserListBridge} interface.
 *
 * <p>Used by Bukkit to enumerate ban list, whitelist, and operator list
 * entries without reflection or direct field access.</p>
 *
 * @param <K> the key type (e.g., {@link com.mojang.authlib.GameProfile} or IP string)
 * @param <V> the entry type (e.g., {@code UserBanListEntry} or {@code IpBanListEntry})
 */
@Mixin(value = StoredUserList.class, priority = 1100)
public class StoredUserListMixin<K, V extends StoredUserEntry<K>>
        implements StoredUserListBridge<V> {

    @Shadow @Final private Map<String, V> map;

    /**
     * Returns all entries in this stored user list.
     * The returned collection is backed by the internal map and reflects
     * any concurrent modifications.
     *
     * @return a live view of all stored entries
     */
    public Collection<V> getValues() {
        return this.map.values();
    }

    @Override
    public Collection<V> bridge$getValues() {
        return getValues();
    }
}