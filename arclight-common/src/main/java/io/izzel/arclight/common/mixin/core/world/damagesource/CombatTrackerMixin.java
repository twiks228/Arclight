package io.izzel.arclight.common.mixin.core.world.damagesource;

import io.izzel.arclight.common.bridge.core.world.damagesource.CombatEntryBridge;
import io.izzel.arclight.common.bridge.core.world.damagesource.CombatTrackerBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.CombatTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin for {@link CombatTracker} that intercepts {@code getDeathMessage()}
 * to return a plugin-overridden death message if one has been set.
 *
 * <p>Plugin death message overrides are stored in two places:</p>
 * <ul>
 *   <li>On the most recent {@link CombatEntry} via {@link CombatEntryBridge},
 *       if the combat log has at least one entry (normal death during combat).</li>
 *   <li>On this mixin as {@link #arclight$pendingDeathMessage},
 *       for cases where the entity died with an empty combat log
 *       (e.g., died instantly from instant damage or fall damage with no prior hits).</li>
 * </ul>
 */
@Mixin(value = CombatTracker.class, priority = 1100)
public class CombatTrackerMixin implements CombatTrackerBridge {

    @Shadow @Final private List<CombatEntry> entries;

    /**
     * Stores a death message override when the combat log is empty at the time
     * {@link #bridge$setDeathMessage} is called.
     * Consumed (set back to {@code null}) after being returned once.
     */
    @Unique
    private Component arclight$pendingDeathMessage;

    /**
     * Intercepts {@code getDeathMessage()} to return the plugin-overridden
     * message if one is available.
     *
     * <p>Priority: entry-level override (set via the last {@link CombatEntry})
     * takes precedence over the pending message. The pending message is consumed
     * (cleared) after being returned to prevent stale overrides from leaking
     * into subsequent deaths on the same entity.</p>
     *
     * @param cir callback info for return value injection
     */
    @Inject(
        method = "getDeathMessage",
        cancellable = true,
        at = @At("HEAD")
    )
    private void arclight$useOverride(CallbackInfoReturnable<Component> cir) {
        if (!this.entries.isEmpty()) {
            // Check if the most recent combat entry has an overridden message
            CombatEntry lastEntry = this.entries.get(this.entries.size() - 1);
            Component override = ((CombatEntryBridge) (Object) lastEntry).bridge$getDeathMessage();
            if (override != null) {
                cir.setReturnValue(override);
            }
        } else if (this.arclight$pendingDeathMessage != null) {
            // No combat entries — use the pending override (e.g., instant death)
            cir.setReturnValue(this.arclight$pendingDeathMessage);
        }

        // Always clear the pending message after each getDeathMessage() call
        // to prevent it from being returned on subsequent invocations
        this.arclight$pendingDeathMessage = null;
    }

    /**
     * Sets a custom death message override.
     *
     * <p>If the combat log has entries, the override is stored on the most recent
     * entry (so it survives additional entries being added before death).
     * If the log is empty, it is stored as a pending message on this tracker.</p>
     *
     * @param component the custom death message to use
     */
    @Override
    public void bridge$setDeathMessage(Component component) {
        this.arclight$pendingDeathMessage = component;
        if (!this.entries.isEmpty()) {
            CombatEntry lastEntry = this.entries.getLast();
            ((CombatEntryBridge) (Object) lastEntry).bridge$setDeathMessage(component);
        }
    }
}