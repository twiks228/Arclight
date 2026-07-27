package io.izzel.arclight.common.mixin.optimization.general.activationrange.entity;

import io.izzel.arclight.common.bridge.core.world.entity.item.ItemEntityBridge;
import io.izzel.arclight.common.bridge.core.world.level.WorldBridge;
import io.izzel.arclight.common.mixin.optimization.general.activationrange.EntityMixin_ActivationRange;
import io.izzel.arclight.common.mod.ArclightConstants;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for ItemEntity within the ActivationRange system.
 * Handles correct pickupDelay/age progression for inactive item entities
 * and their eventual despawn once the item lifetime is exceeded.
 */
@Mixin(value = ItemEntity.class, priority = 1100)
public abstract class ItemEntityMixin_ActivationRange extends EntityMixin_ActivationRange implements ItemEntityBridge {

    // @formatter:off
    @Shadow public int pickupDelay;
    @Shadow public int age;
    @Shadow public abstract ItemStack getItem();
    // @formatter:on

    private int arclight$lastTick = ArclightConstants.currentTick - 1;

    @Override
    public void inactiveTick() {
        super.inactiveTick();

        final int currentTick = ArclightConstants.currentTick;
        int elapsedTicks = currentTick - this.arclight$lastTick;

        if (elapsedTicks > 0) {
            // Update pickupDelay: 32767 is a special "never pick up" marker
            if (this.pickupDelay > 0 && this.pickupDelay != 32767) {
                this.pickupDelay = Math.max(0, this.pickupDelay - elapsedTicks);
            }

            // Update age: -32768 is a special "never despawn by age" marker
            if (this.age != -32768) {
                long newAge = (long) this.age + elapsedTicks;
                this.age = (int) Math.min(newAge, Integer.MAX_VALUE);
            }
        }

        this.arclight$lastTick = currentTick;

        // Check if this item entity should be discarded due to age
        this.bridge$forge$optimization$discardItemEntity();
    }

    @Override
    public void bridge$forge$optimization$discardItemEntity() {
        if (this.level().isClientSide) return;

        final int despawnRate = ((WorldBridge) this.level()).bridge$spigotConfig().itemDespawnRate;

        // despawnRate <= 0 means items never despawn by age
        if (despawnRate > 0 && this.age >= despawnRate) {
            this.bridge$pushEntityRemoveCause(EntityRemoveEvent.Cause.DEATH);
            this.discard();
        }
    }
}