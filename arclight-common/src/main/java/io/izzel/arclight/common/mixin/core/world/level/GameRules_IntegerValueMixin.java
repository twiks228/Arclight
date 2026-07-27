package io.izzel.arclight.common.mixin.core.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin for {@link GameRules.IntegerValue} that adds per-world change support.
 * Overrides {@link GameRules_ValueMixin#setFrom} to copy the integer value
 * and fire the per-world callback.
 */
@Mixin(value = GameRules.IntegerValue.class, priority = 1100)
public abstract class GameRules_IntegerValueMixin
        extends GameRules_ValueMixin<GameRules.IntegerValue> {

    @Shadow private int value;

    /**
     * Sets the integer rule value and fires the per-world callback.
     *
     * @param newValue the new integer value
     * @param level    the level this change is scoped to
     */
    @Unique
    public void set(int newValue, @Nullable ServerLevel level) {
        this.value = newValue;
        onChanged(level);
    }

    @Override
    public void setFrom(GameRules.IntegerValue source, @Nullable ServerLevel level) {
        set(source.get(), level);
    }

    @Override
    public void arclight$set(Object value, @Nullable ServerLevel level) {
        this.set((int) value, level);
    }
}