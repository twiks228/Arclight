package io.izzel.arclight.common.mixin.core.server.level;

import com.google.common.collect.Lists;
import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.world.level.saveddata.maps.MapItemSavedDataBridge;
import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.bukkit.inventory.InventoryHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for the inner {@code ServerLevel.EntityCallbacks} class that hooks
 * into entity tracking start/end events to integrate Bukkit's validity system.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Marks entities as valid and in-world when tracking starts</li>
 *   <li>Cleans up map carrying data for players on tracking end</li>
 *   <li>Closes open inventories for inventory-holding entities on tracking end</li>
 *   <li>Marks entities as invalid and notifies players of entity removal</li>
 * </ul>
 */
@Mixin(
    targets = "net/minecraft/server/level/ServerLevel$EntityCallbacks",
    priority = 1100
)
public class ServerLevel_EntityCallbacksMixin {

    /**
     * Reference to the enclosing {@link ServerLevel} instance.
     * Field name varies by mapping set.
     */
    @Shadow(aliases = {"f_143351_", "this$0", "field_26936"})
    private ServerLevel outerThis;

    // ── Tracking start ────────────────────────────────────────────────────────

    /**
     * Marks the entity as valid and in-world when it begins being tracked.
     * Both flags must be set so that Bukkit entity state checks are consistent.
     *
     * @param entity the entity that started being tracked
     * @param ci     callback info
     */
    @Inject(
        method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("RETURN")
    )
    private void arclight$markValid(Entity entity, CallbackInfo ci) {
        ((EntityBridge) entity).bridge$setInWorld(true);
        ((EntityBridge) entity).bridge$setValid(true);
    }

    // ── Tracking end ──────────────────────────────────────────────────────────

    /**
     * Cleans up entity-related state when the entity stops being tracked.
     *
     * <p>For players: removes them from all map carrying lists across all dimensions.
     * This prevents dead player references from causing NullPointerExceptions
     * during map update ticks.</p>
     *
     * <p>For inventory holders: closes any open inventories to prevent item
     * duplication and stale UI state on clients.</p>
     *
     * @param entity the entity that stopped being tracked
     * @param ci     callback info
     */
    @Inject(
        method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD")
    )
    private void arclight$entityCleanup(Entity entity, CallbackInfo ci) {
        // Clean up map carrying data for players
        if (entity instanceof Player player) {
            for (ServerLevel serverLevel : ArclightServer.getMinecraftServer().getAllLevels()) {
                DimensionDataStorage storage = serverLevel.getDataStorage();
                for (Object savedData : storage.cache.values()) {
                    if (savedData instanceof MapItemSavedData map) {
                        // Remove from vanilla carrying map
                        map.carriedByPlayers.remove(player);
                        // Remove from Arclight's extended carrying list
                        ((MapItemSavedDataBridge) map).bridge$getCarriedBy()
                            .removeIf(entry -> entry.player == entity);
                    }
                }
            }
        }

        // Close inventories for entities that implement InventoryHolder
        if (((EntityBridge) entity).bridge$getBukkitEntity() instanceof InventoryHolder holder) {
            // Copy list to avoid ConcurrentModificationException during close
            for (org.bukkit.entity.HumanEntity viewer :
                    Lists.newArrayList(holder.getInventory().getViewers())) {
                viewer.closeInventory();
            }
        }
    }

    /**
     * Marks the entity as invalid after tracking ends, and notifies all
     * online players to remove it from their visibility lists.
     *
     * <p>Server players are excluded because their removal is handled
     * separately by the PlayerList disconnect pipeline.</p>
     *
     * @param entity the entity that stopped being tracked
     * @param ci     callback info
     */
    @Inject(
        method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("RETURN")
    )
    private void arclight$markInvalid(Entity entity, CallbackInfo ci) {
        ((EntityBridge) entity).bridge$setValid(false);

        // Notify players of non-player entity removal (for canSee tracking)
        if (!(entity instanceof ServerPlayer)) {
            for (Entity player : outerThis.players()) {
                ((ServerPlayerBridge) player).bridge$getBukkitEntity().onEntityRemove(entity);
            }
        }
    }
}