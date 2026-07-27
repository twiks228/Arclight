package io.izzel.arclight.common.mixin.optimization.general.realtime;

import io.izzel.arclight.common.mod.ArclightConstants;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for ItemEntity that enforces wall-clock time usage
 * instead of raw tick counting for pickupDelay and age fields.
 * This ensures correct behavior during server lag/tick skips.
 */
@Mixin(value = ItemEntity.class, priority = 1100)
public abstract class ItemEntityMixin_Realtime {

    @Shadow public int pickupDelay;
    @Shadow public int age;

    /**
     * Initialized one tick behind so the first invocation
     * correctly resolves elapsed = 1.
     */
    private int arclight$lastTick = ArclightConstants.currentTick - 1;

    /**
     * Injects after Entity#tick() call to apply time correction
     * based on actually elapsed ticks (wall time).
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/entity/Entity;tick()V"
        )
    )
    private void arclight$useWallTime(CallbackInfo ci) {
        final int currentTick = ArclightConstants.currentTick;
        // Calculate skipped ticks (beyond the one already processed by vanilla tick())
        int elapsedTicks = currentTick - this.arclight$lastTick - 1;

        // Guard against negative values (e.g. tick counter reset)
        if (elapsedTicks <= 0) {
            this.arclight$lastTick = currentTick;
            return;
        }

        // Update pickupDelay: 32767 is a special "never pick up" marker
        if (this.pickupDelay > 0 && this.pickupDelay != 32767) {
            this.pickupDelay = Math.max(0, this.pickupDelay - elapsedTicks);
        }

        // Update age: -32768 is a special "never despawn by age" marker
        if (this.age != -32768) {
            // Overflow guard
            long newAge = (long) this.age + elapsedTicks;
            this.age = (int) Math.min(newAge, Integer.MAX_VALUE);
        }

        this.arclight$lastTick = currentTick;
    }
}