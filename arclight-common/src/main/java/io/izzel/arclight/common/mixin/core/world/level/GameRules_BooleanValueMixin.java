package io.izzel.arclight.common.mixin.core.world.level;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin for {@link GameRules.BooleanValue} that adds per-world change support.
 * Overrides {@link GameRules_ValueMixin#setFrom} to copy the boolean value
 * and fire the per-world callback.
 */
@Mixin(value = GameRules.BooleanValue.class, priority = 1100)
public abstract class GameRules_BooleanValueMixin
        extends GameRules_ValueMixin<GameRules.BooleanValue> {

    @Shadow private boolean value;

    /**
     * Sets the boolean rule value and fires the per-world callback.
     *
     * @param newValue the new boolean value
     * @param level    the level this change is scoped to
     */
    @Unique
    public void set(boolean newValue, @Nullable ServerLevel level) {
        this.value = newValue;
        onChanged(level);
    }

    @Override
    public void setFrom(GameRules.BooleanValue source, @Nullable ServerLevel level) {
        set(source.get(), level);
    }

    @Override
    public void arclight$set(Object value, @Nullable ServerLevel level) {
        this.set((boolean) value, level);
    }
}