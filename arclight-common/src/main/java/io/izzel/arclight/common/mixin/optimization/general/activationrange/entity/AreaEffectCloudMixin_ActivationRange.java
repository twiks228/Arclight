package io.izzel.arclight.common.mixin.optimization.general.activationrange.entity;

import io.izzel.arclight.common.mixin.optimization.general.activationrange.EntityMixin_ActivationRange;
import net.minecraft.world.entity.AreaEffectCloud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for AreaEffectCloud within the ActivationRange system.
 * Ensures inactive area effect clouds are still discarded correctly
 * once their wait time + duration has elapsed.
 */
@Mixin(value = AreaEffectCloud.class, priority = 1100)
public abstract class AreaEffectCloudMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow public int waitTime;
    @Shadow private int duration;
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();

        // tickCount is advanced inside super.inactiveTick() -> Entity#inactiveTick()
        // waitTime — delay before the cloud starts applying effects
        // duration — how long the cloud persists after waitTime
        final int totalLifetime = this.waitTime + this.duration;

        if (this.tickCount >= totalLifetime) {
            // Server is authoritative for entity removal
            if (!this.level().isClientSide) {
                this.discard();
            }
        }
    }
}