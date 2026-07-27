package io.izzel.arclight.common.mixin.core.world.damagesource;

import io.izzel.arclight.common.bridge.core.world.damagesource.CombatEntryBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.CombatEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin for {@link CombatEntry} that stores a custom death message override.
 *
 * <p>Arclight allows plugins to override death messages via
 * {@link org.bukkit.event.entity.PlayerDeathEvent#setDeathMessage(String)}.
 * The overridden message is stored here and retrieved by
 * {@link CombatTrackerMixin} to replace the vanilla-generated death message.</p>
 */
@Mixin(value = CombatEntry.class, priority = 1100)
public class CombatEntryMixin implements CombatEntryBridge {

    /**
     * The plugin-overridden death message for this combat entry.
     * {@code null} if no override has been set.
     */
    @Unique
    private Component arclight$deathMessage;

    @Override
    public void bridge$setDeathMessage(Component component) {
        this.arclight$deathMessage = component;
    }

    @Override
    public Component bridge$getDeathMessage() {
        return this.arclight$deathMessage;
    }
}