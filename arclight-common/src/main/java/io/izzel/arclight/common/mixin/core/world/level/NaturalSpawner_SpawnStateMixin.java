package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.spawner.WorldEntitySpawnerBridge;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for {@link NaturalSpawner.SpawnState} that exposes the internal mob cap
 * and density tracking to the Bukkit/Arclight spawning infrastructure.
 *
 * <p>The {@link WorldEntitySpawnerBridge.EntityDensityManagerBridge} interface
 * allows custom spawn handling in {@link NaturalSpawnerMixin} to use the
 * manager's cap logic without accessing internal NMS fields directly.</p>
 */
@Mixin(value = NaturalSpawner.SpawnState.class, priority = 1100)
public abstract class NaturalSpawner_SpawnStateMixin
        implements WorldEntitySpawnerBridge.EntityDensityManagerBridge {

    // @formatter:off
    @Shadow protected abstract void afterSpawn(Mob mob, ChunkAccess chunk);
    @Shadow @Final private int spawnableChunkCount;
    @Shadow @Final private Object2IntOpenHashMap<MobCategory> mobCategoryCounts;
    @Shadow protected abstract boolean canSpawn(EntityType<?> entityType, BlockPos pos, ChunkAccess chunk);
    @Shadow @Final private LocalMobCapCalculator localMobCapCalculator;
    // @formatter:on

    /**
     * Checks whether the given entity type can spawn at the specified position.
     * Delegates to the vanilla {@link NaturalSpawner.SpawnState#canSpawn} check.
     */
    @Override
    public boolean bridge$canSpawn(EntityType<?> entityType, BlockPos pos, ChunkAccess chunk) {
        return this.canSpawn(entityType, pos, chunk);
    }

    /**
     * Notifies the spawn state that a mob has been spawned, updating
     * the internal density counters for its mob category.
     */
    @Override
    public void bridge$updateDensity(Mob mob, ChunkAccess chunk) {
        this.afterSpawn(mob, chunk);
    }

    /**
     * Checks whether the given mob category can still spawn mobs in the
     * chunk at the given position, considering both global and local caps.
     *
     * <p>The global cap is scaled by {@code spawnableChunkCount / 289} to
     * normalize across the number of loaded spawnable chunks.</p>
     *
     * @param classification the mob category to check
     * @param pos            the chunk position to check local caps for
     * @param limit          the per-world spawn limit for this category
     * @return {@code true} if spawning is permitted
     */
    @Override
    public boolean bridge$canSpawn(MobCategory classification, ChunkPos pos, int limit) {
        // Scale the limit by the fraction of loaded chunks out of the standard 289 (17x17)
        int scaledLimit = limit * this.spawnableChunkCount / 289;
        if (this.mobCategoryCounts.getInt(classification) >= scaledLimit) {
            return false;
        }
        return this.localMobCapCalculator.canSpawn(classification, pos);
    }
}