package io.izzel.arclight.common.mixin.optimization.general.activationrange.entity;

import io.izzel.arclight.common.mixin.optimization.general.activationrange.EntityMixin_ActivationRange;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for AbstractArrow within the ActivationRange system.
 * Ensures the "time stuck in ground" counter keeps advancing
 * for inactive projectiles, which is required for arrow despawn logic.
 */
@Mixin(value = AbstractArrow.class, priority = 1100)
public abstract class AbstractArrowMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow public boolean inGround;
    @Shadow protected int inGroundTime;
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();

        // Only increment while the arrow is actually stuck in a block
        // inGroundTime is used to trigger despawn after prolonged ground time
        if (this.inGround) {
            // Overflow guard
            if (this.inGroundTime < Integer.MAX_VALUE) {
                this.inGroundTime++;
            }
        }
    }
}