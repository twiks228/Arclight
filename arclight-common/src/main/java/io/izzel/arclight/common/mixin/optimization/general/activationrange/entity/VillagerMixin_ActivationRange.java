package io.izzel.arclight.common.mixin.optimization.general.activationrange.entity;

import io.izzel.arclight.common.bridge.core.world.level.WorldBridge;
import io.izzel.arclight.common.mixin.optimization.general.activationrange.EntityMixin_ActivationRange;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for Villager within the ActivationRange system.
 * Optionally ticks villager AI while inactive, controlled by the
 * spigot.yml setting "tick-inactive-villagers".
 */
@Mixin(value = Villager.class, priority = 1100)
public abstract class VillagerMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow protected abstract void customServerAiStep();
    // @formatter:on

    @Override
    public void inactiveTick() {
        final Villager villager = (Villager) (Object) this;
        final WorldBridge worldBridge = (WorldBridge) this.level();

        // Run the AI step for inactive villagers only if:
        // 1. tickInactiveVillagers is enabled in spigot.yml
        // 2. The villager has effective AI (not spawned with NoAI tag)
        // 3. We are on the server side (AI must not run client-side)
        if (!this.level().isClientSide
                && worldBridge.bridge$spigotConfig().tickInactiveVillagers
                && villager.isEffectiveAi()) {
            this.customServerAiStep();
        }

        super.inactiveTick();
    }
}