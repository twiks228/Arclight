package io.izzel.arclight.common.mixin.optimization.general.realtime;

import io.izzel.arclight.common.mod.ArclightConstants;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin for ServerPlayerGameMode that corrects gameTicks counting
 * using wall-clock time. Ensures block breaking mechanics remain
 * accurate under server lag.
 */
@Mixin(value = ServerPlayerGameMode.class, priority = 1100)
public abstract class ServerPlayerGameModeMixin_Realtime {

    @Shadow private int gameTicks;

    /**
     * Initialized one tick behind for correct first invocation.
     */
    private int arclight$lastTick = ArclightConstants.currentTick - 1;

    /**
     * Redirects the gameTicks++ field write and replaces it with
     * an increment based on actually elapsed ticks (wall time).
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "FIELD",
            opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;gameTicks:I"
        )
    )
    private void arclight$useWallTime(ServerPlayerGameMode instance, int ignoredValue) {
        final int currentTick = ArclightConstants.currentTick;
        int elapsedTicks = currentTick - this.arclight$lastTick;

        // Minimum of 1 tick to preserve original semantics
        if (elapsedTicks < 1) {
            elapsedTicks = 1;
        }

        // Guard against abnormal tick jumps (e.g. after world load)
        // Cap at 20 ticks (1 second) to avoid instant block break exploits
        if (elapsedTicks > 20) {
            elapsedTicks = 20;
        }

        // Overflow guard
        long newGameTicks = (long) this.gameTicks + elapsedTicks;
        this.gameTicks = (int) Math.min(newGameTicks, Integer.MAX_VALUE);

        this.arclight$lastTick = currentTick;
    }
}