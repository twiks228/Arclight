package io.izzel.arclight.common.mixin.optimization.general.activationrange.entity;

import io.izzel.arclight.common.mixin.optimization.general.activationrange.EntityMixin_ActivationRange;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for LivingEntity within the ActivationRange system.
 * Ensures the no-action-time counter still increments for inactive
 * living entities.
 *
 * noActionTime is used for:
 * - Determining AFK/idle mob state
 * - Despawning mobs that have been idle for too long
 * - Various AI-related timing calculations
 */
@Mixin(value = LivingEntity.class, priority = 1100)
public abstract class LivingEntityMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow protected int noActionTime;
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();

        // Overflow guard: prevent wraparound which could break
        // despawn logic relying on this counter
        if (this.noActionTime < Integer.MAX_VALUE) {
            this.noActionTime++;
        }
    }
}