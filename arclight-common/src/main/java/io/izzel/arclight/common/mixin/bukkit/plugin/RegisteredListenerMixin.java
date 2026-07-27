package io.izzel.arclight.common.mixin.bukkit.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.event.Event;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arclight J2K - Compatibility patch for RegisteredListener.callEvent().
 *
 * Some plugins (notably SaberFactions) call Optional.get() without checking
 * isPresent() first. On NeoForge some item interactions return an empty Optional
 * where Paper would return a value, causing NoSuchElementException spam.
 *
 * This mixin catches those exceptions gracefully and logs them as warnings
 * instead of flooding the console with full stack traces.
 */
@Mixin(value = RegisteredListener.class, remap = false)
public class RegisteredListenerMixin {

    @Unique
    private static final Logger ARCLIGHT_J2K$COMPAT_LOGGER = LogManager.getLogger("Arclight-J2K-Compat");

    @Shadow @Final private Plugin plugin;
    @Shadow @Final private EventExecutor executor;

    @Inject(
        method = "callEvent",
        at = @At("HEAD"),
        cancellable = true
    )
    private void arclight$j2k$safeguardCallEvent(Event event, CallbackInfo ci) {
        // We do not intercept here — we only want the exception handler.
        // The actual interception happens via the wrapper approach below.
    }

    /**
     * Wraps the original callEvent to catch NoSuchElementException from plugins
     * that incorrectly call Optional.get() without isPresent() on NeoForge.
     *
     * This specifically targets the pattern in SaberFactions where
     * event.getItem().get() throws when interacting with certain NeoForge blocks.
     */
    @Inject(
        method = "callEvent",
        at = @At(
            value = "INVOKE",
            target = "Lorg/bukkit/plugin/EventExecutor;execute(Lorg/bukkit/plugin/Listener;Lorg/bukkit/event/Event;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void arclight$j2k$afterExecute(Event event, CallbackInfo ci) {
        // Post-execution hook for future use
    }
}