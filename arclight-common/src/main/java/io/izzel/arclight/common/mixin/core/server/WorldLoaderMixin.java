package io.izzel.arclight.common.mixin.core.server;

import io.izzel.arclight.common.mod.util.ArclightCaptures;
import net.minecraft.server.WorldLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mixin for {@link WorldLoader} that captures the {@link WorldLoader.DataLoadContext}
 * during world loading for use by Arclight's registry and world setup code.
 *
 * <p>The {@link WorldLoader.DataLoadContext} provides access to the bootstrapped
 * registries and data pack contents at the point when world data is being created.
 * Capturing it here allows the Arclight bootstrap code to access these registries
 * without depending on a specific call order.</p>
 */
@Mixin(value = WorldLoader.class, priority = 1100)
public class WorldLoaderMixin {

    /**
     * Intercepts the data context passed to the world data supplier and
     * stores it in {@link ArclightCaptures} for later retrieval.
     *
     * @param context the data load context containing bootstrapped registries
     * @return the same context, unmodified (we only observe, not modify)
     */
    @ModifyArg(
        method = "load",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/WorldLoader$WorldDataSupplier;get(" +
                     "Lnet/minecraft/server/WorldLoader$DataLoadContext;" +
                     ")Lnet/minecraft/server/WorldLoader$DataLoadOutput;"
        )
    )
    private static WorldLoader.DataLoadContext arclight$captureContext(
            WorldLoader.DataLoadContext context) {
        ArclightCaptures.captureDataLoadContext(context);
        return context;
    }
}