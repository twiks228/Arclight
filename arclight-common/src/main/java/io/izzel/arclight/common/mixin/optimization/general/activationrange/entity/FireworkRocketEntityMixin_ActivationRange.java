package io.izzel.arclight.common.mixin.optimization.general.activationrange.entity;

import io.izzel.arclight.common.mixin.optimization.general.activationrange.EntityMixin_ActivationRange;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for FireworkRocketEntity within the ActivationRange system.
 * Ensures the firework's lifetime countdown and explosion trigger
 * still function correctly while the entity is inactive.
 */
@Mixin(value = FireworkRocketEntity.class, priority = 1100)
public abstract class FireworkRocketEntityMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow private int life;
    @Shadow public int lifetime;
    @Shadow protected abstract void explode();
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();

        ++this.life;

        // Explosion logic should only be authoritative on the server side
        if (this.level().isClientSide) return;

        if (this.life > this.lifetime) {
            // Fire the Bukkit event before triggering the actual explosion
            if (!CraftEventFactory.callFireworkExplodeEvent(
                    (FireworkRocketEntity) (Object) this).isCancelled()) {
                this.explode();
            }
        }
    }
}