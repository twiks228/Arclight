package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.api.EnumHelper;
import io.izzel.arclight.common.mod.mixins.annotation.TransformAccess;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Mixin for {@link Level.ExplosionInteraction} that adds a custom
 * {@code STANDARD} enum constant used by Arclight to represent
 * "standard" explosion behaviour (destroy without decay).
 *
 * <p>The {@code STANDARD} constant is intercepted in {@link LevelMixin} and
 * remapped to {@link Level.ExplosionInteraction#BLOCK} for vanilla processing,
 * while forcing {@link net.minecraft.world.level.Explosion.BlockInteraction#DESTROY}
 * as the actual block destruction mode.</p>
 *
 * <p>Made {@code public static final} via {@link TransformAccess} so it can be
 * referenced from {@link io.izzel.arclight.common.mod.ArclightConstants}.</p>
 */
@Mixin(value = Level.ExplosionInteraction.class, priority = 1100)
public class Level_ExplosionInteractionMixin {

    /**
     * The custom {@code STANDARD} explosion interaction mode.
     * Added at the end of the enum (index = {@code values().length}).
     *
     * <p>Requires a {@code String} argument in the enum constructor
     * to match the Brigadier argument format used by the vanilla enum.</p>
     */
    @TransformAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
    private static final Level.ExplosionInteraction STANDARD = EnumHelper.makeEnum(
        Level.ExplosionInteraction.class,
        "STANDARD",
        Level.ExplosionInteraction.values().length,
        List.of(String.class),
        List.of("standard")
    );
}