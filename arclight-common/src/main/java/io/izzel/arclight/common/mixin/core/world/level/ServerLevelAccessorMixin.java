package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.server.level.ServerLevelBridge;
import io.izzel.arclight.common.mod.util.DistValidate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for the {@link ServerLevelAccessor} interface that overrides
 * {@code addFreshEntityWithPassengers} to propagate Bukkit spawn reasons
 * to each passenger entity independently.
 */
@Mixin(value = ServerLevelAccessor.class, priority = 1100)
public interface ServerLevelAccessorMixin extends LevelAccessor, ServerLevelBridge {

    // @formatter:off
    @Shadow ServerLevel getLevel();
    // @formatter:on

    @Override
    default ServerLevel bridge$getMinecraftWorld() {
        return this.getLevel();
    }

    /**
     * @author IzzelAliz
     * @reason Overwritten to propagate Bukkit CreatureSpawnEvent.SpawnReason
     * to each passenger entity independently, preventing loss of spawn context.
     */
    @Overwrite
    default void addFreshEntityWithPassengers(Entity entity) {
        if (!DistValidate.isValid((LevelAccessor) this)) {
            // Non-real level: add entities directly without spawn reason tracking
            for (Entity passenger : entity.getSelfAndPassengers().toList()) {
                this.addFreshEntity(passenger);
            }
            return;
        }

        // Real server level: propagate the current spawn reason to all passengers
        CreatureSpawnEvent.SpawnReason spawnReason = bridge$getAddEntityReason();
        for (Entity passenger : entity.getSelfAndPassengers().toList()) {
            bridge$pushAddEntityReason(spawnReason);
            this.addFreshEntity(passenger);
        }
    }

    /**
     * Variant of {@code addFreshEntityWithPassengers} with an explicit spawn reason.
     * Used by world-gen code and custom spawning logic that provides its own reason.
     *
     * @param entity the root entity (passengers are also added)
     * @param reason the Bukkit spawn reason to attach to each entity
     * @return {@code true} if the root entity was not removed after addition
     */
    default boolean addFreshEntityWithPassengers(
            Entity entity,
            CreatureSpawnEvent.SpawnReason reason
    ) {
        for (Entity passenger : entity.getSelfAndPassengers().toList()) {
            if (DistValidate.isValid((LevelAccessor) this)) {
                bridge$pushAddEntityReason(reason);
            }
            this.addFreshEntity(passenger);
        }
        return !entity.isRemoved();
    }

    @Override
    default boolean bridge$addAllEntities(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        return this.addFreshEntityWithPassengers(entity, reason);
    }
}