package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.level.IWorldWriterBridge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelWriter;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin for the {@link LevelWriter} interface that bridges the Bukkit
 * {@link CreatureSpawnEvent.SpawnReason} into entity addition calls.
 *
 * <p>Provides a convenience overload of {@code addFreshEntity} that accepts
 * a spawn reason, delegating to {@link IWorldWriterBridge#bridge$addEntity}.</p>
 */
@Mixin(value = LevelWriter.class, priority = 1100)
public interface LevelWriterMixin extends IWorldWriterBridge {

    /**
     * Adds a freshly created entity to the world with an explicit Bukkit spawn reason.
     * Delegates to {@link IWorldWriterBridge#bridge$addEntity} which fires
     * {@link CreatureSpawnEvent} before adding.
     *
     * @param entity the entity to add
     * @param reason the Bukkit spawn reason for event dispatch
     * @return {@code true} if the entity was successfully added
     */
    default boolean addFreshEntity(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        return bridge$addEntity(entity, reason);
    }
}