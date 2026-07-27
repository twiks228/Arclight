package io.izzel.arclight.common.mod.server.api;

import io.izzel.arclight.api.TickingTracker;
import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Default implementation of {@link TickingTracker} that resolves the current
 * ticking context from {@link ArclightCaptures}.
 *
 * <p>Priority order for source resolution:</p>
 * <ol>
 *   <li>Ticking entity (highest priority)</li>
 *   <li>Ticking block entity (tile entity)</li>
 *   <li>Ticking block (lowest priority)</li>
 * </ol>
 */
public class DefaultTickingTracker implements TickingTracker {

    @Nullable
    @Override
    public Object getTickingSource() {
        // Priority order: entity > block entity > block
        Entity entity = getTickingEntity();
        if (entity != null) return entity;

        TileState tileState = getTickingBlockEntity();
        if (tileState != null) return tileState;

        return getTickingBlock();
    }

    @Nullable
    @Override
    public Entity getTickingEntity() {
        var tickingEntity = ArclightCaptures.getTickingEntity();
        if (tickingEntity == null) return null;

        return ((EntityBridge) tickingEntity).bridge$getBukkitEntity();
    }

    @Nullable
    @Override
    public Block getTickingBlock() {
        var level = ArclightCaptures.getTickingLevel();
        var pos   = ArclightCaptures.getTickingPosition();

        if (level == null || pos == null) return null;

        return CraftBlock.at(level, pos);
    }

    @Nullable
    @Override
    public TileState getTickingBlockEntity() {
        var blockEntity = ArclightCaptures.getTickingBlockEntity();
        if (blockEntity == null) return null;

        var level = blockEntity.getLevel();
        if (level == null) return null;

        CraftBlock block = CraftBlock.at(level, blockEntity.getBlockPos());
        BlockState state = block.getState();

        return (state instanceof TileState tileState) ? tileState : null;
    }
}