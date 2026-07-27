package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.level.IWorldWriterBridge;
import io.izzel.arclight.common.bridge.core.world.level.WorldBridge;
import io.izzel.arclight.common.bridge.core.world.spawner.WorldEntitySpawnerBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelData;
import org.bukkit.craftbukkit.v.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link NaturalSpawner} that integrates Bukkit's per-category
 * spawn rate and limit configuration into the vanilla spawning pipeline.
 *
 * <p>Key behaviours:</p>
 * <ul>
 *   <li>Respects per-world spawn limits from {@code CraftWorld#getSpawnLimit}</li>
 *   <li>Respects per-world ticks-per-spawn from {@link WorldBridge#bridge$ticksPerSpawnCategory}</li>
 *   <li>Fires {@link CreatureSpawnEvent.SpawnReason#NATURAL} for naturally spawned mobs</li>
 *   <li>Fires {@link CreatureSpawnEvent.SpawnReason#CHUNK_GEN} for world-gen spawns</li>
 *   <li>Skips the after-spawn callback for mobs removed during spawning</li>
 * </ul>
 */
@Mixin(value = NaturalSpawner.class, priority = 1100)
public abstract class NaturalSpawnerMixin {

    // @formatter:off
    @Shadow @Final private static MobCategory[] SPAWNING_CATEGORIES;
    @Shadow public static void spawnCategoryForChunk(MobCategory category, ServerLevel world, LevelChunk chunk,
                                                     NaturalSpawner.SpawnPredicate predicate,
                                                     NaturalSpawner.AfterSpawnCallback callback) {}
    // @formatter:on

    /**
     * Overrides the vanilla spawn loop to respect Bukkit's per-category
     * spawn tick rates and world spawn limits.
     *
     * <p>For each mob category:</p>
     * <ol>
     *   <li>Checks if this tick should attempt spawning (ticks-per-spawn interval)</li>
     *   <li>Checks if the world has a non-zero spawn limit for this category</li>
     *   <li>Checks the mob cap via the density manager bridge</li>
     *   <li>Delegates to vanilla {@code spawnCategoryForChunk} if all checks pass</li>
     * </ol>
     *
     * @author IzzelAliz
     * @reason Integrate Bukkit per-world spawn limits and ticks-per-spawn rates,
     *         which cannot be expressed through injections without rewriting the loop.
     */
    @Overwrite
    public static void spawnForChunk(
            ServerLevel world,
            LevelChunk chunk,
            NaturalSpawner.SpawnState manager,
            boolean spawnFriendlies,
            boolean spawnEnemies,
            boolean spawnPassives
    ) {
        world.getProfiler().push("spawner");

        LevelData worldInfo = world.getLevelData();
        WorldBridge worldBridge = (WorldBridge) world;
        WorldEntitySpawnerBridge.EntityDensityManagerBridge densityManager =
            (WorldEntitySpawnerBridge.EntityDensityManagerBridge) manager;

        for (MobCategory category : SPAWNING_CATEGORIES) {
            boolean spawnThisTick = true;
            int limit = category.getMaxInstancesPerChunk();

            SpawnCategory spawnCategory = CraftSpawnCategory.toBukkit(category);
            if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
                long ticksPerSpawn = worldBridge.bridge$ticksPerSpawnCategory()
                    .getLong(spawnCategory);

                // A ticksPerSpawn value of 0 means "never spawn this category"
                spawnThisTick = ticksPerSpawn != 0
                    && worldInfo.getGameTime() % ticksPerSpawn == 0;

                limit = world.bridge$getWorld().getSpawnLimit(spawnCategory);
            }

            // Skip this category if it should not spawn on this tick
            if (!spawnThisTick) {
                continue;
            }

            // A limit of 0 means spawning is completely disabled for this category
            if (limit == 0) {
                continue;
            }

            // Verify category flags and the mob cap before attempting spawn
            boolean categoryAllowed =
                (spawnFriendlies || !category.isFriendly()) &&
                (spawnEnemies    ||  category.isFriendly()) &&
                (spawnPassives   || !category.isPersistent());

            if (categoryAllowed
                    && densityManager.bridge$canSpawn(category, chunk.getPos(), limit)) {
                spawnCategoryForChunk(
                    category, world, chunk,
                    densityManager::bridge$canSpawn,
                    densityManager::bridge$updateDensity
                );
            }
        }

        world.getProfiler().pop();
    }

    /**
     * Sets the spawn reason to {@link CreatureSpawnEvent.SpawnReason#NATURAL}
     * before a naturally spawned mob is added to the world.
     */
    @Inject(
        method = "spawnCategoryForPosition(" +
                 "Lnet/minecraft/world/entity/MobCategory;" +
                 "Lnet/minecraft/server/level/ServerLevel;" +
                 "Lnet/minecraft/world/level/chunk/ChunkAccess;" +
                 "Lnet/minecraft/core/BlockPos;" +
                 "Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;" +
                 "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private static void arclight$naturalSpawn(
            MobCategory category,
            ServerLevel world,
            ChunkAccess chunk,
            BlockPos pos,
            NaturalSpawner.SpawnPredicate predicate,
            NaturalSpawner.AfterSpawnCallback callback,
            CallbackInfo ci
    ) {
        ((WorldBridge) world).bridge$pushAddEntityReason(CreatureSpawnEvent.SpawnReason.NATURAL);
    }

    /**
     * Skips the after-spawn callback (density counter update) for mobs that were
     * removed during the spawn attempt, e.g. cancelled by a Bukkit event.
     *
     * <p>Without this guard, the mob cap counter would be incremented for mobs
     * that never actually entered the world, causing spawn starvation.</p>
     */
    @Redirect(
        method = "spawnCategoryForPosition(" +
                 "Lnet/minecraft/world/entity/MobCategory;" +
                 "Lnet/minecraft/server/level/ServerLevel;" +
                 "Lnet/minecraft/world/level/chunk/ChunkAccess;" +
                 "Lnet/minecraft/core/BlockPos;" +
                 "Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;" +
                 "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;run(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"
        )
    )
    private static void arclight$skipRemovedMobCallback(
            NaturalSpawner.AfterSpawnCallback callback,
            Mob mob,
            ChunkAccess chunk
    ) {
        if (!mob.isRemoved()) {
            callback.run(mob, chunk);
        }
    }

    /**
     * Sets the spawn reason to {@link CreatureSpawnEvent.SpawnReason#CHUNK_GEN}
     * before a world-generation mob is added to the world.
     */
    @Inject(
        method = "spawnMobsForChunkGeneration",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private static void arclight$worldGenSpawn(
            ServerLevelAccessor accessor,
            Holder<Biome> biome,
            ChunkPos chunkPos,
            RandomSource random,
            CallbackInfo ci
    ) {
        ((IWorldWriterBridge) accessor).bridge$pushAddEntityReason(
            CreatureSpawnEvent.SpawnReason.CHUNK_GEN
        );
    }
}