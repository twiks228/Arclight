package io.izzel.arclight.common.mixin.core.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerTickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/**
 * Mixin for {@link ServerTickRateManager} that adds a {@code sendLog} flag
 * to suppress sprint completion messages when not needed.
 *
 * <p>The vanilla implementation always sends a log message to the command sender
 * when a tick sprint finishes. This mixin allows callers (e.g., internal timer
 * logic) to suppress that message while still performing the sprint stop.</p>
 *
 * <p>The flag is stored as a transient field and reset in a {@code finally} block
 * to ensure it is always cleaned up even if {@code stopSprinting()} throws.</p>
 */
@Mixin(value = ServerTickRateManager.class, priority = 1100)
public abstract class ServerTickRateManagerMixin {

    // @formatter:off
    @Shadow public abstract boolean stopSprinting();
    // @formatter:on

    /**
     * Controls whether the sprint-finish log message is sent.
     * Defaults to {@code true} (vanilla behavior).
     * Set to {@code false} by {@link #stopSprinting(boolean)} to suppress output.
     */
    @Unique
    private boolean arclight$sendLog = true;

    /**
     * Stops an active tick sprint with optional log message suppression.
     *
     * @param sendLog if {@code true}, sends the vanilla sprint-finish message;
     *                if {@code false}, suppresses it
     * @return {@code true} if a sprint was active and has been stopped
     */
    public boolean stopSprinting(boolean sendLog) {
        try {
            arclight$sendLog = sendLog;
            return this.stopSprinting();
        } finally {
            // Always reset to true to prevent stale state from suppressing
            // future log messages if an exception is thrown
            arclight$sendLog = true;
        }
    }

    /**
     * Conditionally suppresses the sprint-finish success message
     * based on the current value of {@link #arclight$sendLog}.
     *
     * @param source   the command source to send the message to
     * @param supplier the message supplier
     * @param flag     whether to also log to console (vanilla parameter)
     */
    @Redirect(
        method = "finishTickSprint",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/CommandSourceStack;sendSuccess(Ljava/util/function/Supplier;Z)V"
        )
    )
    private void arclight$conditionalSend(
            CommandSourceStack source,
            Supplier<Component> supplier,
            boolean flag
    ) {
        if (arclight$sendLog) {
            source.sendSuccess(supplier, flag);
        }
    }
}