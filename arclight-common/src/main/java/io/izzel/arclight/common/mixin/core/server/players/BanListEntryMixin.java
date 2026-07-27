package io.izzel.arclight.common.mixin.core.server.players;

import io.izzel.arclight.common.bridge.core.server.players.BanListEntryBridge;
import net.minecraft.server.players.BanListEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Date;

/**
 * Mixin for {@link BanListEntry} that exposes the {@code created} timestamp
 * via the {@link BanListEntryBridge} interface.
 *
 * <p>The creation date is used by the Bukkit ban API to report when a ban
 * was issued. Since {@code created} is a {@code final} field in vanilla NMS,
 * it cannot be accessed directly from CraftBukkit without this bridge.</p>
 */
@Mixin(value = BanListEntry.class, priority = 1100)
public class BanListEntryMixin implements BanListEntryBridge {

    @Shadow @Final protected Date created;

    /**
     * Returns the date and time when this ban entry was created.
     *
     * @return the creation timestamp, never {@code null}
     */
    public Date getCreated() {
        return this.created;
    }

    @Override
    public Date bridge$getCreated() {
        return getCreated();
    }
}