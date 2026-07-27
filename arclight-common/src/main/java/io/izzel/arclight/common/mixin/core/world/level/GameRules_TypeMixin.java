package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.level.GameRules_TypeBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.BiConsumer;

/**
 * Mixin for {@link GameRules.Type} that stores a per-world callback
 * registered by {@link GameRulesMixin#arclight$initPerWorldCallback}.
 *
 * <p>The callback is invoked when the game rule value changes through
 * a command in a specific world, replacing the global server-level callback.</p>
 *
 * @param <T> the game rule value type
 */
@Mixin(value = GameRules.Type.class, priority = 1100)
public class GameRules_TypeMixin<T extends GameRules.Value<T>> implements GameRules_TypeBridge<T> {

    /**
     * The per-world callback to invoke when this rule's value changes.
     * {@code null} if no per-world callback has been registered.
     */
    @Unique
    private BiConsumer<ServerLevel, T> arclight$perWorldCallback;

    @Override
    public void arclight$setPerWorldCallback(BiConsumer<ServerLevel, T> callback) {
        this.arclight$perWorldCallback = callback;
    }

    @Override
    public void arclight$runCallback(ServerLevel level, T value) {
        if (arclight$perWorldCallback != null) {
            arclight$perWorldCallback.accept(level, value);
        }
    }
}