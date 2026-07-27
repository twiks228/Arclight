package io.izzel.arclight.common.mixin.core.world.level;

import com.mojang.datafixers.util.Pair;
import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.bridge.core.world.damagesource.DamageSourceBridge;
import io.izzel.arclight.common.bridge.core.world.level.ExplosionBridge;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import io.izzel.arclight.mixin.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Mixin for {@link Explosion} that integrates Bukkit explosion events and
 * patches several edge cases specific to hybrid NeoForge+Bukkit servers.
 *
 * <p>Key behaviours:</p>
 * <ul>
 *   <li>Fires {@link EntityExplodeEvent} and {@link BlockExplodeEvent}</li>
 *   <li>Fires {@link TNTPrimeEvent} for TNT blocks lit by explosions</li>
 *   <li>Fires {@link BlockIgniteEvent} for fire placed by explosions</li>
 *   <li>Applies explosion knockback via {@link EntityKnockbackEvent}</li>
 *   <li>Handles multi-part entities (ender dragon) correctly for damage</li>
 *   <li>Guards against negative explosion radii causing instant cancellation</li>
 *   <li>Prevents empty {@link ItemStack}s from being added to drop lists</li>
 * </ul>
 */
@Mixin(value = Explosion.class, priority = 1100)
public abstract class ExplosionMixin implements ExplosionBridge {

    // @formatter:off
    @Shadow @Final private Level level;
    @Shadow @Final private Explosion.BlockInteraction blockInteraction;
    @Shadow @Mutable @Final private float radius;
    @Shadow @Final private ObjectArrayList<BlockPos> toBlow;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Shadow @Final public Entity source;
    @Accessor("source")       public abstract Entity bridge$getExploder();
    @Accessor("radius")       public abstract float bridge$getSize();
    @Accessor("radius")       public abstract void bridge$setSize(float size);
    @Accessor("blockInteraction") public abstract Explosion.BlockInteraction bridge$getMode();
    @Shadow @Final @Mutable   private DamageSource damageSource;
    @Shadow public abstract Explosion.BlockInteraction getBlockInteraction();
    // @formatter:on

    /** Drop yield multiplier (0.0–1.0). Set from explosion block interaction mode. */
    public float yield;

    /** Whether this explosion was cancelled by a Bukkit event. */
    public boolean wasCanceled = false;

    @Override
    public float bridge$getYield() {
        return this.yield;
    }

    // ── Constructor injection ─────────────────────────────────────────────────

    /**
     * Clamps the explosion radius to ≥ 0 (negative radii would cause
     * math errors in ray-casting), computes the initial drop yield,
     * and ensures the damage source is linked to the causing entity.
     */
    @Inject(
        method = "<init>(" +
                 "Lnet/minecraft/world/level/Level;" +
                 "Lnet/minecraft/world/entity/Entity;" +
                 "Lnet/minecraft/world/damagesource/DamageSource;" +
                 "Lnet/minecraft/world/level/ExplosionDamageCalculator;" +
                 "DDDFZLnet/minecraft/world/level/Explosion$BlockInteraction;" +
                 "Lnet/minecraft/core/particles/ParticleOptions;" +
                 "Lnet/minecraft/core/particles/ParticleOptions;" +
                 "Lnet/minecraft/core/Holder;)V",
        at = @At("RETURN")
    )
    public void arclight$adjustSize(
            Level level, Entity entity, DamageSource damageSource,
            ExplosionDamageCalculator calculator,
            double x, double y, double z, float radius, boolean fire,
            Explosion.BlockInteraction blockInteraction,
            ParticleOptions particle1, ParticleOptions particle2,
            Holder<SoundEvent> sound,
            CallbackInfo ci
    ) {
        // Guard against negative radius from mod APIs
        this.radius = Math.max(radius, 0F);

        // DESTROY_WITH_DECAY uses a yield < 1.0; all other modes keep full yield
        this.yield = (this.blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY)
            ? 1.0F / this.radius
            : 1.0F;

        // Ensure the damage source tracks the causing entity for attribution
        this.damageSource = ((DamageSourceBridge) (
            this.damageSource == null
                ? level.damageSources().explosion((Explosion) (Object) this)
                : this.damageSource
        )).bridge$customCausingEntity(entity);
    }

    // ── Radius guard ──────────────────────────────────────────────────────────

    /**
     * Cancels the explosion early if the radius is below the minimum threshold.
     * This prevents degenerate explosions from causing unnecessary computation.
     */
    @Inject(
        method = "explode",
        cancellable = true,
        at = @At("HEAD")
    )
    private void arclight$returnRadius(CallbackInfo ci) {
        if (this.radius < 0.1F) {
            ci.cancel();
        }
    }

    // ── Multi-part entity damage ──────────────────────────────────────────────

    /**
     * Special-cases multi-part entities (e.g., the Ender Dragon) during explosion damage.
     *
     * <p>The Ender Dragon consists of a root entity and several {@code ComplexEntityPart}
     * hitboxes. Vanilla would try to damage the part directly, but velocity changes on parts
     * are ignored. This mixin instead damages all parts that are in the blast radius and
     * skips the loop iteration if any damage is cancelled.</p>
     */
    @Decorate(
        method = "explode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        )
    )
    private boolean arclight$handleMultiPart(
            Entity entity,
            DamageSource damageSource,
            float amount,
            @Local(ordinal = -1) List<Entity> entityList
    ) throws Throwable {
        // Parts themselves receive no velocity — skip them, handled via root entity
        if (((EntityBridge) entity).bridge$forge$isPartEntity()) {
            throw DecorationOps.jumpToLoopStart();
        }

        ((EntityBridge) entity).bridge$setLastDamageCancelled(false);

        boolean result = false;
        var parts = ((EntityBridge) entity).bridge$forge$getParts();

        if (parts != null) {
            // Damage each part that is present in the blast list
            for (var part : parts) {
                if (entityList.contains(part)) {
                    result |= part.hurt(damageSource, amount);
                }
            }
        } else {
            result = (boolean) DecorationOps.callsite().invoke(entity, damageSource, amount);
        }

        // Skip knockback if damage was cancelled by a Bukkit event
        if (((EntityBridge) entity).bridge$isLastDamageCancelled()) {
            throw DecorationOps.jumpToLoopStart();
        }

        return result;
    }

    // ── Explosion knockback event ─────────────────────────────────────────────

    /**
     * Intercepts the knockback velocity vector for living entities and fires
     * {@link EntityKnockbackEvent}, allowing plugins to modify or cancel it.
     */
    @Decorate(
        method = "explode",
        at = @At(
            value = "NEW",
            ordinal = 0,
            target = "(DDD)Lnet/minecraft/world/phys/Vec3;"
        ),
        slice = @Slice(
            from = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getKnockbackMultiplier(Lnet/minecraft/world/entity/Entity;)F"
            )
        )
    )
    private Vec3 arclight$knockBack(
            double dx, double dy, double dz,
            @Local(ordinal = -1) Entity entity
    ) throws Throwable {
        Vec3 knockback = (Vec3) DecorationOps.callsite().invoke(dx, dy, dz);

        if (entity instanceof LivingEntity) {
            // Calculate distance squared from explosion centre to entity
            double distX = entity.getX() - this.x;
            double distY = entity.getEyeY() - this.y;
            double distZ = entity.getZ() - this.z;
            double forceSq = distX * distX + distY * distY + distZ * distZ;

            Vec3 resultVelocity = entity.getDeltaMovement().add(knockback);
            var event = CraftEventFactory.callEntityKnockbackEvent(
                (CraftLivingEntity) entity.bridge$getBukkitEntity(),
                source,
                EntityKnockbackEvent.KnockbackCause.EXPLOSION,
                forceSq,
                knockback,
                resultVelocity.x,
                resultVelocity.y,
                resultVelocity.z
            );

            if (event.isCancelled()) {
                return Vec3.ZERO;
            }

            // Return the delta between the event's final velocity and the entity's
            // current velocity, since the caller will ADD this to entity velocity
            return new Vec3(
                event.getFinalKnockback().getX(),
                event.getFinalKnockback().getY(),
                event.getFinalKnockback().getZ()
            ).subtract(entity.getDeltaMovement());
        }

        return knockback;
    }

    @Override
    public boolean bridge$wasCancelled() {
        return wasCanceled;
    }

    // ── Block explosion event ─────────────────────────────────────────────────

    /**
     * Fires the {@link EntityExplodeEvent} or {@link BlockExplodeEvent} after the
     * block list has been shuffled (so plugins receive a randomized order).
     * Cancels the explosion if the event is cancelled.
     */
    @Inject(
        method = "finalizeExplosion",
        cancellable = true,
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/Util;shuffle(Ljava/util/List;Lnet/minecraft/util/RandomSource;)V"
        )
    )
    private void arclight$blockExplode(boolean spawnParticles, CallbackInfo ci) {
        if (this.arclight$callBlockExplodeEvent()) {
            this.wasCanceled = true;
            ci.cancel();
        }
    }

    // ── TNT prime event ───────────────────────────────────────────────────────

    /**
     * Fires {@link TNTPrimeEvent} before a TNT block explodes due to being in
     * the blast radius. Cancels priming and sends a block update to undo the
     * client-side visual removal if cancelled.
     */
    @Decorate(
        method = "finalizeExplosion",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;onExplosionHit(" +
                     "Lnet/minecraft/world/level/Level;" +
                     "Lnet/minecraft/core/BlockPos;" +
                     "Lnet/minecraft/world/level/Explosion;" +
                     "Ljava/util/function/BiConsumer;)V"
        )
    )
    private void arclight$tntPrime(
            BlockState blockState,
            Level level,
            BlockPos pos,
            Explosion explosion,
            BiConsumer<?, ?> biConsumer
    ) throws Throwable {
        if (blockState.getBlock() instanceof TntBlock) {
            Entity sourceEntity = this.source;
            BlockPos sourceBlock = (sourceEntity == null)
                ? BlockPos.containing(this.x, this.y, this.z)
                : null;

            if (!CraftEventFactory.callTNTPrimeEvent(
                    this.level, pos, TNTPrimeEvent.PrimeCause.EXPLOSION,
                    sourceEntity, sourceBlock)) {
                // Undo the client-side block removal by sending an update
                this.level.sendBlockUpdated(pos, Blocks.AIR.defaultBlockState(), blockState, 3);
                return;
            }
        }
        DecorationOps.callsite().invoke(blockState, level, pos, explosion, biConsumer);
    }

    // ── Block ignite event ────────────────────────────────────────────────────

    /**
     * Fires {@link BlockIgniteEvent} before fire is placed by an explosion.
     * Returns {@code false} (cancels the block placement) if the event is cancelled.
     */
    @Decorate(
        method = "finalizeExplosion",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(" +
                     "Lnet/minecraft/core/BlockPos;" +
                     "Lnet/minecraft/world/level/block/state/BlockState;)Z"
        )
    )
    private boolean arclight$blockIgnite(
            Level instance,
            BlockPos blockPos,
            BlockState blockState
    ) throws Throwable {
        BlockIgniteEvent event = CraftEventFactory.callBlockIgniteEvent(
            this.level, blockPos, (Explosion) (Object) this
        );
        if (event.isCancelled()) {
            return false;
        }
        return (boolean) DecorationOps.callsite().invoke(instance, blockPos, blockState);
    }

    // ── Empty stack guard ─────────────────────────────────────────────────────

    /**
     * Prevents empty {@link ItemStack}s from being added to the explosion's
     * drop accumulator. This can happen when mod loot tables produce empty stacks,
     * which would cause issues in downstream drop processing.
     */
    @Inject(
        method = "addOrAppendStack",
        cancellable = true,
        at = @At("HEAD")
    )
    private static void arclight$fix(
            List<Pair<ItemStack, BlockPos>> drops,
            ItemStack stack,
            BlockPos pos,
            CallbackInfo ci
    ) {
        if (stack.isEmpty()) {
            ci.cancel();
        }
    }

    // ── Block explode event dispatch ──────────────────────────────────────────

    /**
     * Builds the block list, fires the appropriate Bukkit explosion event,
     * and updates the {@link #toBlow} list and {@link #yield} from the result.
     *
     * @return {@code true} if the explosion should be cancelled entirely
     */
    @Unique
    private boolean arclight$callBlockExplodeEvent() {
        org.bukkit.World world = this.level.bridge$getWorld();
        Location location = new Location(world, this.x, this.y, this.z);

        // Build a list of non-air blocks that will be destroyed
        List<org.bukkit.block.Block> blockList = new ObjectArrayList<>(this.toBlow.size());
        for (int i = this.toBlow.size() - 1; i >= 0; i--) {
            BlockPos pos = this.toBlow.get(i);
            org.bukkit.block.Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
            if (!block.getType().isAir()) {
                blockList.add(block);
            }
        }

        final boolean wasCancelled;
        final List<org.bukkit.block.Block> resultBlocks;

        if (this.source != null) {
            // Entity explosion (creeper, TNT entity, etc.)
            EntityExplodeEvent event = CraftEventFactory.callEntityExplodeEvent(
                this.source, blockList, this.yield, this.getBlockInteraction()
            );
            wasCancelled = event.isCancelled();
            resultBlocks = event.blockList();
            this.yield = event.getYield();
        } else {
            // Block explosion (bed, respawn anchor, etc.)
            org.bukkit.block.Block block = location.getBlock();
            org.bukkit.block.BlockState blockState =
                (((DamageSourceBridge) damageSource).bridge$directBlockState() != null)
                    ? ((DamageSourceBridge) damageSource).bridge$directBlockState()
                    : block.getState();

            BlockExplodeEvent event = CraftEventFactory.callBlockExplodeEvent(
                block, blockState, blockList, this.yield, this.getBlockInteraction()
            );
            wasCancelled = event.isCancelled();
            resultBlocks = event.blockList();
            this.yield = event.getYield();
        }

        // Update the internal block list from the plugin-modified result
        this.toBlow.clear();
        for (org.bukkit.block.Block block : resultBlocks) {
            this.toBlow.add(new BlockPos(block.getX(), block.getY(), block.getZ()));
        }

        return wasCancelled;
    }
}