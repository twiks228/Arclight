package io.izzel.arclight.common.mixin.core.world.damagesource;

import io.izzel.arclight.common.bridge.core.world.damagesource.DamageSourceBridge;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

/**
 * Mixin for {@link DamageSource} that extends vanilla damage attribution with
 * Bukkit-specific metadata: sweep attacks, poison, melting, direct block/block
 * state references, and custom entity damager overrides.
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li><b>Immutability preservation:</b> Vanilla {@link DamageSource} uses
 *       shared constants (e.g., {@code DamageSources#IN_FIRE}). To avoid
 *       mutating shared instances, all "setter" methods that change Bukkit
 *       metadata first call {@link #cloneInstance()} and mutate the clone.</li>
 *   <li><b>Custom entity damager:</b> Vanilla uses {@code causingEntity} for
 *       the logical attacker and {@code directEntity} for the entity that
 *       physically dealt the damage. Arclight's {@code customEntityDamager}
 *       allows overriding the direct entity attribution without changing the
 *       vanilla fields (which might be accessed by mods).</li>
 * </ul>
 */
@Mixin(value = DamageSource.class, priority = 1100)
public abstract class DamageSourceMixin implements DamageSourceBridge {

    // @formatter:off
    @Shadow @Nullable public abstract Entity getEntity();
    @Shadow @Final @org.jetbrains.annotations.Nullable private Entity causingEntity;
    @Shadow @Final private Holder<DamageType> type;
    @Shadow @Final @org.jetbrains.annotations.Nullable private Entity directEntity;
    @Shadow @Final @org.jetbrains.annotations.Nullable private Vec3 damageSourcePosition;
    // @formatter:on

    // ── Bukkit-specific damage metadata ───────────────────────────────────────

    /** The block that directly caused this damage (e.g., cactus, magma block). */
    @Unique @Nullable private Block directBlock;

    /** The block state at the time of damage (for explosion source tracking). */
    @Unique @Nullable private BlockState directBlockState;

    /** Whether this damage was a sweep attack (AoE sword swing). */
    @Unique private boolean withSweep;

    /** Whether this damage is melting (fire damage from ambient heat, not flame). */
    @Unique private boolean melting;

    /** Whether this damage is poison (magic damage classified as poison). */
    @Unique private boolean poison;

    /**
     * Overrides the direct entity damager for Bukkit attribution.
     * Takes precedence over {@link #directEntity} when non-null.
     * Only set via {@link #bridge$setCustomCausingEntity(Entity)}.
     */
    @Unique @Nullable private Entity customEntityDamager;

    /**
     * Overrides the causing entity damager for Bukkit attribution.
     * Takes precedence over {@link #causingEntity} when non-null.
     * Only set via {@link #bridge$setCustomCausingEntityDamager(Entity)}.
     */
    @Unique @Nullable private Entity customCausingEntityDamager;

    // ── Sweep ────────────────────────────────────────────────────────────────

    public boolean isSweep() {
        return withSweep;
    }

    @Override
    public boolean bridge$isSweep() {
        return isSweep();
    }

    public DamageSource sweep() {
        withSweep = true;
        return (DamageSource) (Object) this;
    }

    @Override
    public DamageSource bridge$sweep() {
        return sweep();
    }

    // ── Melting ───────────────────────────────────────────────────────────────

    public boolean isMelting() {
        return melting;
    }

    public DamageSource melting() {
        this.melting = true;
        return (DamageSource) (Object) this;
    }

    @Override
    public DamageSource bridge$melting() {
        return melting();
    }

    // ── Poison ────────────────────────────────────────────────────────────────

    public boolean isPoison() {
        return poison;
    }

    public DamageSource poison() {
        this.poison = true;
        return (DamageSource) (Object) this;
    }

    @Override
    public DamageSource bridge$poison() {
        return poison();
    }

    // ── Direct entity damager ─────────────────────────────────────────────────

    /**
     * Returns the entity considered to have "directly" dealt this damage for
     * Bukkit attribution purposes.
     *
     * <p>Note: Arclight historically had a bug where {@code causingEntity} was
     * used instead of {@code directEntity}, causing incorrect attribution for
     * projectiles and splash potions. The {@link #customEntityDamager} override
     * mechanism exists to fix that without breaking existing behaviour.</p>
     */
    public Entity getDamager() {
        return (customEntityDamager != null) ? customEntityDamager : this.directEntity;
    }

    @Override
    public Entity bridge$getCausingEntity() {
        return this.getDamager();
    }

    /**
     * Returns a new {@link DamageSource} instance with the custom entity damager set.
     * If the override is already set, or the given entity is already the direct/causing
     * entity, returns {@code this} unchanged to avoid unnecessary cloning.
     *
     * @param entity the entity to use as the direct damager
     * @return this source or a cloned source with the override applied
     */
    public DamageSource customEntityDamager(Entity entity) {
        if (this.customEntityDamager != null
                || this.directEntity == entity
                || this.causingEntity == entity) {
            return (DamageSource) (Object) this;
        }
        DamageSource clone = cloneInstance();
        return ((DamageSourceBridge) clone).bridge$setCustomCausingEntity(entity);
    }

    // ── Causing entity damager ────────────────────────────────────────────────

    public Entity getCausingDamager() {
        return (customCausingEntityDamager != null)
            ? customCausingEntityDamager
            : this.causingEntity;
    }

    @Override
    public Entity bridge$getCausingEntityDamager() {
        return this.getCausingDamager();
    }

    @Override
    public DamageSource bridge$customCausingEntity(Entity entity) {
        return customEntityDamager(entity);
    }

    @Override
    public DamageSource bridge$setCustomCausingEntity(Entity entity) {
        this.customEntityDamager = entity;
        return (DamageSource) (Object) this;
    }

    /**
     * Returns a new {@link DamageSource} with the custom causing entity damager set.
     * Same guard logic as {@link #customEntityDamager(Entity)}.
     */
    public DamageSource customCausingEntityDamager(Entity entity) {
        if (this.customCausingEntityDamager != null
                || this.directEntity == entity
                || this.causingEntity == entity) {
            return (DamageSource) (Object) this;
        }
        DamageSource clone = cloneInstance();
        return ((DamageSourceBridge) clone).bridge$setCustomCausingEntityDamager(entity);
    }

    @Override
    public DamageSource bridge$customCausingEntityDamager(Entity entity) {
        return customCausingEntityDamager(entity);
    }

    @Override
    public DamageSource bridge$setCustomCausingEntityDamager(Entity entity) {
        this.customCausingEntityDamager = entity;
        return (DamageSource) (Object) this;
    }

    // ── Direct block ──────────────────────────────────────────────────────────

    @Nullable
    public Block getDirectBlock() {
        return this.directBlock;
    }

    @Override
    @Nullable
    public Block bridge$directBlock() {
        return this.getDirectBlock();
    }

    @Override
    public DamageSource bridge$directBlock(Block block) {
        return ((DamageSourceBridge) cloneInstance()).bridge$setDirectBlock(block);
    }

    @Override
    public DamageSource bridge$setDirectBlock(Block block) {
        this.directBlock = block;
        return (DamageSource) (Object) this;
    }

    // ── Direct block state ────────────────────────────────────────────────────

    @Nullable
    public BlockState getDirectBlockState() {
        return this.directBlockState;
    }

    /**
     * Returns a new {@link DamageSource} with the direct block state set.
     * If {@code blockState} is {@code null}, returns {@code this} unchanged
     * to avoid a pointless clone allocation.
     *
     * @param blockState the block state to attach, or {@code null} to skip
     * @return this source or a cloned source with the block state applied
     */
    public DamageSource directBlockState(@Nullable BlockState blockState) {
        if (blockState == null) {
            return (DamageSource) (Object) this;
        }
        DamageSource clone = cloneInstance();
        ((DamageSourceBridge) clone).bridge$setDirectBlockState(blockState);
        return clone;
    }

    @Override
    @Nullable
    public BlockState bridge$directBlockState() {
        return this.directBlockState;
    }

    @Override
    public DamageSource bridge$directBlockState(BlockState block) {
        return directBlockState(block);
    }

    @Override
    public DamageSource bridge$setDirectBlockState(BlockState block) {
        this.directBlockState = block;
        return (DamageSource) (Object) this;
    }

    // ── Clone utility ─────────────────────────────────────────────────────────

    /**
     * Creates a shallow clone of this {@link DamageSource} with all Arclight
     * metadata copied. Used to avoid mutating shared vanilla constant instances.
     *
     * <p>Only the Arclight-specific fields are copied — the vanilla fields
     * ({@code type}, {@code directEntity}, {@code causingEntity},
     * {@code damageSourcePosition}) are passed to the constructor.</p>
     *
     * @return a new {@link DamageSource} instance with the same metadata
     */
    @Unique
    private DamageSource cloneInstance() {
        DamageSource clone = new DamageSource(
            this.type,
            this.directEntity,
            this.causingEntity,
            this.damageSourcePosition
        );
        DamageSourceBridge bridge = (DamageSourceBridge) clone;
        bridge.bridge$setDirectBlock(this.directBlock);
        bridge.bridge$setDirectBlockState(this.directBlockState);
        bridge.bridge$setCustomCausingEntity(this.customEntityDamager);
        if (this.withSweep)  bridge.bridge$sweep();
        if (this.poison)     bridge.bridge$poison();
        if (this.melting)    bridge.bridge$melting();
        return clone;
    }
}