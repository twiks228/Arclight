package io.izzel.arclight.common.mixin.core.world.damagesource;

import io.izzel.arclight.common.bridge.core.world.damagesource.DamageSourceBridge;
import io.izzel.arclight.common.bridge.core.world.damagesource.DamageSourcesBridge;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link DamageSources} that adds Arclight-specific damage source
 * constants and utility methods for Bukkit damage classification.
 *
 * <p>Adds two pre-built Arclight damage sources:</p>
 * <ul>
 *   <li>{@link #melting} — fire damage caused by ambient heat (not direct flame),
 *       used for lava, magma blocks, and environmental fire. Mapped to
 *       {@link org.bukkit.event.entity.EntityDamageEvent.DamageCause#MELTING}.</li>
 *   <li>{@link #poison} — magic damage classified as poison.
 *       Mapped to {@link org.bukkit.event.entity.EntityDamageEvent.DamageCause#POISON}.</li>
 * </ul>
 *
 * <p>These are separate from the vanilla {@code ON_FIRE} and {@code MAGIC}
 * sources because Bukkit distinguishes between them for plugin damage events.</p>
 */
@Mixin(value = DamageSources.class, priority = 1100)
public abstract class DamageSourcesMixin implements DamageSourcesBridge {

    // @formatter:off
    @Shadow protected abstract DamageSource source(ResourceKey<DamageType> key);
    @Shadow public abstract DamageSource badRespawnPointExplosion(Vec3 vec3);
    @Shadow public abstract DamageSource source(
            ResourceKey<DamageType> key,
            @Nullable Entity directEntity,
            @Nullable Entity causingEntity);
    // @formatter:on

    /**
     * Pre-built melting damage source (ON_FIRE type with Arclight's melting flag).
     * Initialized in {@link #arclight$init}.
     */
    @Unique
    public DamageSource melting;

    /**
     * Pre-built poison damage source (MAGIC type with Arclight's poison flag).
     * Initialized in {@link #arclight$init}.
     */
    @Unique
    public DamageSource poison;

    /**
     * Initializes Arclight's custom damage source constants after the
     * {@link DamageSources} constructor completes.
     *
     * <p>Both sources are derived from vanilla types (ON_FIRE and MAGIC)
     * and tagged with the appropriate Arclight flag via the bridge.</p>
     *
     * @param registryAccess the registry access (unused here, passed by vanilla)
     * @param ci             injection callback info
     */
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void arclight$init(RegistryAccess registryAccess, CallbackInfo ci) {
        this.melting = ((DamageSourceBridge) this.source(DamageTypes.ON_FIRE)).bridge$melting();
        this.poison  = ((DamageSourceBridge) this.source(DamageTypes.MAGIC)).bridge$poison();
    }

    // ── DamageSourcesBridge implementation ────────────────────────────────────

    public DamageSource poison() {
        return poison;
    }

    public DamageSource melting() {
        return melting;
    }

    @Override
    public DamageSource bridge$poison() {
        return poison();
    }

    @Override
    public DamageSource bridge$melting() {
        return melting();
    }

    /**
     * Creates an explosion damage source from a specific {@link DamageType} key,
     * attributing the explosion to the given entities.
     *
     * <p>Used by Arclight to create distinct explosion sources for different
     * explosion origins (TNT, beds, respawn anchors, etc.).</p>
     *
     * @param directEntity  the entity directly causing the explosion, or {@code null}
     * @param causingEntity the entity responsible for the explosion, or {@code null}
     * @param resourceKey   the damage type key to use
     * @return a new explosion {@link DamageSource}
     */
    public DamageSource explosion(
            @Nullable Entity directEntity,
            @Nullable Entity causingEntity,
            ResourceKey<DamageType> resourceKey
    ) {
        return this.source(resourceKey, directEntity, causingEntity);
    }

    /**
     * Creates a bad respawn point explosion damage source (from a respawn anchor
     * or bed exploding in the wrong dimension) with an attached block state.
     *
     * <p>The block state is used by {@link io.izzel.arclight.common.mixin.core.world.level.ExplosionMixin}
     * to populate the {@link org.bukkit.event.block.BlockExplodeEvent} with
     * the block that caused the explosion.</p>
     *
     * @param vec3       the explosion position
     * @param blockState the Bukkit block state of the exploding block
     * @return a new explosion damage source with the block state attached
     */
    public DamageSource badRespawnPointExplosion(
            Vec3 vec3,
            org.bukkit.block.BlockState blockState
    ) {
        return ((DamageSourceBridge) this.badRespawnPointExplosion(vec3))
            .bridge$directBlockState(blockState);
    }
}