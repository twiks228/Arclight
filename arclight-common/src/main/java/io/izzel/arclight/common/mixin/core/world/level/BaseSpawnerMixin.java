package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.entity.MobBridge;
import io.izzel.arclight.common.bridge.core.world.level.WorldBridge;
import io.izzel.arclight.common.bridge.core.world.level.BaseSpawnerBridge;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import io.izzel.arclight.mixin.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.SimpleWeightedRandomList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link BaseSpawner} that integrates Bukkit spawner events and
 * Spigot mob nerf configuration into the vanilla mob spawner logic.
 *
 * <p>Key behaviours:</p>
 * <ul>
 *   <li>Clears the spawn potentials list when the entity ID is set via API,
 *       ensuring the spawner only spawns the configured entity type.</li>
 *   <li>Applies Spigot's {@code nerfSpawnerMobs} config option to newly
 *       spawned mobs, disabling their AI if configured.</li>
 *   <li>Fires {@code SpawnerSpawnEvent} before adding the entity to the world,
 *       cancelling the spawn if the event is cancelled.</li>
 *   <li>Sets the Bukkit spawn reason to {@link CreatureSpawnEvent.SpawnReason#SPAWNER}.</li>
 * </ul>
 */
@Mixin(value = BaseSpawner.class, priority = 1100)
public abstract class BaseSpawnerMixin implements BaseSpawnerBridge {

    @Shadow
    public SimpleWeightedRandomList<SpawnData> spawnPotentials;

    // ── Entity ID change ──────────────────────────────────────────────────────

    /**
     * Clears the spawn potentials when the entity ID is explicitly set via
     * {@link BaseSpawner#setEntityId}. This ensures the spawner exclusively
     * spawns the newly configured entity type, without using old potentials.
     *
     * @param ci injection callback info
     */
    @Inject(
        method = "setEntityId",
        at = @At("RETURN")
    )
    public void arclight$clearMobs(CallbackInfo ci) {
        this.spawnPotentials = SimpleWeightedRandomList.empty();
    }

    // ── Mob nerf ──────────────────────────────────────────────────────────────

    /**
     * Applies Spigot's {@code nerfSpawnerMobs} option to each mob about to be
     * spawned. When enabled, the mob's AI is disabled (it will stand still and
     * not pathfind), significantly reducing server load near spawners.
     *
     * @param mob the mob about to spawn
     */
    @Decorate(
        method = "serverTick",
        inject = true,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/SpawnData;getEquipment()Ljava/util/Optional;"
        )
    )
    private void arclight$nerf(@Local(ordinal = -1) Mob mob) {
        if (((WorldBridge) mob.level()).bridge$spigotConfig().nerfSpawnerMobs) {
            ((MobBridge) mob).bridge$setAware(false);
        }
    }

    // ── Spawner spawn event ───────────────────────────────────────────────────

    /**
     * Fires the Bukkit {@code SpawnerSpawnEvent} before the spawned entity is
     * added to the world. Cancels the spawn (jumps to the next loop iteration)
     * if the event is cancelled.
     *
     * <p>Also sets the spawn reason to {@link CreatureSpawnEvent.SpawnReason#SPAWNER}
     * so that {@link org.bukkit.event.entity.CreatureSpawnEvent} correctly reports
     * the reason for mobs spawned by mob spawner blocks.</p>
     *
     * @param level  the server level
     * @param entity the entity being spawned
     * @param pos    the spawner block position (for the event)
     * @return the result of the original {@code tryAddFreshEntityWithPassengers}
     * @throws Throwable if the spawn loop should be skipped (event cancelled)
     */
    @Decorate(
        method = "serverTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z"
        )
    )
    private boolean arclight$spawnerSpawn(
            ServerLevel level,
            Entity entity,
            ServerLevel levelLocal,
            BlockPos pos
    ) throws Throwable {
        if (CraftEventFactory.callSpawnerSpawnEvent(entity, pos).isCancelled()) {
            // Skip to the next spawner tick loop iteration without adding the entity
            throw DecorationOps.jumpToLoopStart();
        }
        ((WorldBridge) level).bridge$pushAddEntityReason(CreatureSpawnEvent.SpawnReason.SPAWNER);
        return (boolean) DecorationOps.callsite().invoke(level, entity);
    }
}