package io.izzel.arclight.common.mixin.core.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Prevents ConcurrentModificationException during CompoundTag network serialization.
 *
 * <p>Some mods (e.g. Weather2) may mutate a CompoundTag while it is being
 * encoded into a custom payload packet on the Netty IO thread.
 * Vanilla CompoundTag#write iterates directly over the live key set of the
 * backing HashMap, which throws ConcurrentModificationException if the map
 * is structurally modified from another thread during serialization.</p>
 *
 * <p>Fix: redirect keySet() inside write() to a stable snapshot.
 * The values are still read from the live map, only iteration is stabilized.</p>
 *
 * <p>Root cause log:
 * java.util.ConcurrentModificationException
 *   at HashMap$KeyIterator.next
 *   at CompoundTag.write
 *   at FriendlyByteBuf.writeNbt
 *   ...triggered by weather2:nbt_client custom payload encoding</p>
 */
@Mixin(value = CompoundTag.class, priority = 1100)
public abstract class CompoundTagMixin_Concurrency {

    /**
     * Returns a stable snapshot of the key set instead of the live HashMap keys.
     * This prevents ConcurrentModificationException when another thread modifies
     * the CompoundTag while this thread is serializing it to a network packet.
     *
     * @param map the backing tag map of the CompoundTag being written
     * @return an immutable snapshot of the key set at the time of serialization
     */
    @Redirect(
        method = "write(Ljava/io/DataOutput;)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;keySet()Ljava/util/Set;"
        )
    )
    private Set<String> arclight$snapshotKeysForSafeIteration(Map<String, Tag> map) {
        // LinkedHashSet preserves insertion order (same as Minecraft's internal map)
        // and is not backed by the original map, so CME cannot occur
        return new LinkedHashSet<>(map.keySet());
    }
}