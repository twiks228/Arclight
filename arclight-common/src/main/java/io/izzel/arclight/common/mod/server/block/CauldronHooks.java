package io.izzel.arclight.common.mod.server.block;

import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.craftbukkit.v.block.CraftBlockState;
import org.bukkit.craftbukkit.v.block.CraftBlockStates;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Static hooks for firing {@link CauldronLevelChangeEvent} from NMS code.
 *
 * <p><b>Thread safety:</b> These fields are intended to be used only on the
 * main server thread within a single tick call stack (set → changeLevel → reset),
 * so no synchronization is required. However, callers must ensure {@link #reset()}
 * is called after every use to prevent stale state leaks between ticks.</p>
 */
public final class CauldronHooks {

    // Utility class — prevent instantiation
    private CauldronHooks() {}

    @Nullable
    private static Entity entity;
    
    private static CauldronLevelChangeEvent.ChangeReason reason =
        CauldronLevelChangeEvent.ChangeReason.UNKNOWN;
    
    /** Cached result of the last {@link #changeLevel} call. */
    private static boolean lastRet = true;

    // ── State setters ─────────────────────────────────────────────────────────

    /**
     * Sets the entity and reason that will be passed to the next
     * {@link CauldronLevelChangeEvent}. Must be followed by {@link #reset()}.
     *
     * @param entity the entity causing the change, or {@code null}
     * @param reason the reason for the level change
     */
    public static void setChangeReason(
            @Nullable Entity entity,
            CauldronLevelChangeEvent.ChangeReason reason) {
        CauldronHooks.entity = entity;
        CauldronHooks.reason = reason;
    }

    /**
     * Resets all thread-local state. Must be called after each cauldron level
     * change attempt to prevent stale data leaking into subsequent events.
     */
    public static void reset() {
        CauldronHooks.entity  = null;
        CauldronHooks.reason  = CauldronLevelChangeEvent.ChangeReason.UNKNOWN;
        CauldronHooks.lastRet = true;
    }

    // ── State getters ─────────────────────────────────────────────────────────

    /** Returns the entity set by {@link #setChangeReason}, or {@code null}. */
    @Nullable
    public static Entity getEntity() {
        return entity;
    }

    /** Returns the change reason set by {@link #setChangeReason}. */
    public static CauldronLevelChangeEvent.ChangeReason getReason() {
        return reason;
    }

    /** Returns the result of the last {@link #changeLevel} call. */
    public static boolean getResult() {
        return lastRet;
    }

    // ── Event dispatch ────────────────────────────────────────────────────────

    /**
     * Fires {@link CauldronLevelChangeEvent} and applies the new block state
     * if the event is not cancelled.
     *
     * @param old    the previous block state (unused directly, kept for context)
     * @param world  the level containing the cauldron
     * @param pos    the position of the cauldron block
     * @param state  the new block state to apply if event is not cancelled
     * @param entity the entity causing the change, or {@code null}
     * @param reason the reason for the level change
     * @return {@code true} if the change was applied, {@code false} if cancelled
     */
    public static boolean changeLevel(
            BlockState old,
            Level world,
            BlockPos pos,
            BlockState state,
            @Nullable Entity entity,
            CauldronLevelChangeEvent.ChangeReason reason
    ) {
        CraftBlockState newState = CraftBlockStates.getBlockState(world, pos);
        newState.setData(state);

        var bukkitEntity = (entity != null)
            ? ((EntityBridge) entity).bridge$getBukkitEntity()
            : null;

        CauldronLevelChangeEvent event = new CauldronLevelChangeEvent(
            CraftBlock.at(world, pos),
            bukkitEntity,
            reason,
            newState
        );

        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return lastRet = false;
        }

        newState.update(true);
        return lastRet = true;
    }
}