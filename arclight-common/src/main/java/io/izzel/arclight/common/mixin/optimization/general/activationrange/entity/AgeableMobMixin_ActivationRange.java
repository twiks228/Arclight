package io.izzel.arclight.common.mixin.optimization.general.activationrange.entity;

import io.izzel.arclight.common.bridge.core.world.entity.AgeableMobBridge;
import io.izzel.arclight.common.mixin.optimization.general.activationrange.EntityMixin_ActivationRange;
import net.minecraft.world.entity.AgeableMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for AgeableMob within the ActivationRange system.
 * Ensures aging/growth progression (baby growing up, breeding cooldown
 * decreasing) still occurs for inactive entities.
 */
@Mixin(value = AgeableMob.class, priority = 1100)
public abstract class AgeableMobMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow public abstract int getAge();
    @Shadow public abstract void setAge(int age);
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();

        final AgeableMobBridge bridge = (AgeableMobBridge) this;

        // If age is locked (e.g. via /age lock command),
        // only refresh dimensions without modifying the age value
        if (bridge.bridge$isAgeLocked()) {
            this.refreshDimensions();
            return;
        }

        final int currentAge = this.getAge();

        if (currentAge == 0) {
            // Already adult and not in breeding cooldown, nothing to do
            return;
        }

        if (currentAge < 0) {
            // Baby entity — progress towards adulthood (age 0)
            this.setAge(currentAge + 1);
        } else {
            // Breeding cooldown — progress towards being ready again (age 0)
            this.setAge(currentAge - 1);
        }
    }
}