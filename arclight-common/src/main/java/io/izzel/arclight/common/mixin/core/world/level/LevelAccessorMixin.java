package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.level.LevelAccessorBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin for the {@link LevelAccessor} interface that bridges
 * {@link LevelAccessorBridge#bridge$getMinecraftWorld()} into a public
 * default method on the interface for use by other mixins.
 *
 * <p>This provides a stable way to retrieve the underlying {@link ServerLevel}
 * from any {@link LevelAccessor} implementation without casting.</p>
 */
@Mixin(value = LevelAccessor.class, priority = 1100)
public interface LevelAccessorMixin extends LevelAccessorBridge {

    /**
     * Returns the underlying {@link ServerLevel} for this accessor.
     * Delegates to {@link LevelAccessorBridge#bridge$getMinecraftWorld()}.
     *
     * @return the server level, or {@code null} for non-server accessors
     */
    default ServerLevel getMinecraftWorld() {
        return this.bridge$getMinecraftWorld();
    }
}