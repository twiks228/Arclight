package io.izzel.arclight.common.mixin.core.stats;

import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.event.Cancellable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Mixin for {@link StatsCounter} that fires Bukkit statistic-increase hooks
 * before the updated value is committed.
 *
 * <p>If the Bukkit event is cancelled, the statistic increment is aborted.</p>
 */
@Mixin(value = StatsCounter.class, priority = 1100)
public abstract class StatsCounterMixin {

    // @formatter:off
    @Shadow public abstract int getValue(Stat<?> stat);
    // @formatter:on

    /**
     * Intercepts statistic increments immediately before the new value is written
     * and fires the Bukkit statistics increase event.
     *
     * @param player the player whose stat is being incremented
     * @param stat   the statistic being modified
     * @param amount the increment amount
     * @param ci     callback info
     * @param newValue the new calculated stat value captured from the target method
     */
    @Inject(
        method = "increment",
        cancellable = true,
        locals = LocalCapture.CAPTURE_FAILHARD,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/stats/StatsCounter;setValue(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/stats/Stat;I)V"
        )
    )
    public void arclight$statsIncrement(
            Player player,
            Stat<?> stat,
            int amount,
            CallbackInfo ci,
            int newValue
    ) {
        int oldValue = this.getValue(stat);
        Cancellable event = CraftEventFactory.handleStatisticsIncrease(player, stat, oldValue, newValue);

        if (event != null && event.isCancelled()) {
            ci.cancel();
        }
    }
}