package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.level.BlockGetterBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;

/**
 * Mixin for the {@link BlockGetter} interface that exposes a block ray-trace
 * helper method via {@link BlockGetterBridge}.
 *
 * <p>Provides a default {@link #clip(ClipContext, BlockPos)} implementation
 * that tests both the block shape and the fluid shape and returns whichever
 * hit is closer to the ray origin. This is equivalent to the vanilla logic
 * extracted into a reusable form for Arclight's bridge layer.</p>
 */
@Mixin(value = BlockGetter.class, priority = 1100)
public interface BlockGetterMixin extends BlockGetterBridge {

    // @formatter:off
    @Shadow BlockState getBlockState(BlockPos pos);
    @Shadow FluidState getFluidState(BlockPos pos);
    @Shadow @Nullable BlockHitResult clipWithInteractionOverride(
            Vec3 startVec, Vec3 endVec, BlockPos pos, VoxelShape shape, BlockState state);
    // @formatter:on

    /**
     * Casts a ray through a single block, testing both the block's solid shape
     * and its fluid shape, returning the closest hit.
     *
     * @param context the ray clip context (from/to positions, shape type)
     * @param pos     the block position to test
     * @return the closest {@link BlockHitResult}, or {@code null} if no hit
     */
    @Nullable
    default BlockHitResult clip(ClipContext context, BlockPos pos) {
        BlockState blockState = this.getBlockState(pos);
        FluidState fluidState = this.getFluidState(pos);

        Vec3 from = context.getFrom();
        Vec3 to   = context.getTo();

        // Test block solid shape
        VoxelShape blockShape = context.getBlockShape(blockState, (BlockGetter) this, pos);
        BlockHitResult blockHit = this.clipWithInteractionOverride(from, to, pos, blockShape, blockState);

        // Test fluid shape (for water, lava interaction)
        VoxelShape fluidShape = context.getFluidShape(fluidState, (BlockGetter) this, pos);
        BlockHitResult fluidHit = fluidShape.clip(from, to, pos);

        // Return whichever hit is closer to the ray origin
        double blockDist = (blockHit == null) ? Double.MAX_VALUE
            : context.getFrom().distanceToSqr(blockHit.getLocation());
        double fluidDist = (fluidHit == null) ? Double.MAX_VALUE
            : context.getFrom().distanceToSqr(fluidHit.getLocation());

        return (blockDist <= fluidDist) ? blockHit : fluidHit;
    }

    @Override
    default BlockHitResult bridge$rayTraceBlock(ClipContext context, BlockPos pos) {
        return clip(context, pos);
    }
}