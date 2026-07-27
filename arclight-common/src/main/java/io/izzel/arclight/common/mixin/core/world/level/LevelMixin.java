package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.level.WorldBridge;
import io.izzel.arclight.common.bridge.core.world.level.border.WorldBorderBridge;
import io.izzel.arclight.common.mod.ArclightConstants;
import io.izzel.arclight.common.mod.mixins.annotation.CreateConstructor;
import io.izzel.arclight.common.mod.mixins.annotation.ShadowConstructor;
import io.izzel.arclight.common.mod.mixins.annotation.TransformAccess;
import io.izzel.arclight.common.mod.server.world.ArclightWorldConfig;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import io.izzel.arclight.common.mod.util.DistValidate;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelWriter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.bukkit.craftbukkit.v.block.CapturedBlockState;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.craftbukkit.v.block.data.CraftBlockData;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.craftbukkit.v.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.objectweb.asm.Opcodes;
import org.spigotmc.SpigotWorldConfig;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Core mixin for {@link Level} that integrates Bukkit world state into the NMS world.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Injects Bukkit world fields (pvp, populating, spawn ticks, etc.)</li>
 *   <li>Bridges Bukkit {@link CraftWorld} and {@link SpigotWorldConfig} access</li>
 *   <li>Hooks block state changes for entity-driven change events</li>
 *   <li>Overrides explosion interaction mode via the STANDARD enum constant</li>
 *   <li>Provides per-world block physics event firing</li>
 * </ul>
 */
@Mixin(value = Level.class, priority = 1100)
public abstract class LevelMixin implements WorldBridge, LevelAccessor, LevelWriter {

    // @formatter:off
    @Shadow @Nullable public abstract BlockEntity getBlockEntity(BlockPos pos);
    @Shadow public abstract BlockState getBlockState(BlockPos pos);
    @Shadow public abstract WorldBorder getWorldBorder();
    @Shadow @Final private WorldBorder worldBorder;
    @Shadow public abstract long getDayTime();
    @Shadow public abstract MinecraftServer getServer();
    @Shadow public abstract LevelData getLevelData();
    @Shadow public abstract ResourceKey<Level> dimension();
    @Shadow public abstract DimensionType dimensionType();
    @Shadow public abstract void setBlocksDirty(BlockPos pos, BlockState oldState, BlockState newState);
    @Shadow @Final public boolean isClientSide;
    @Shadow public abstract void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags);
    @Shadow public abstract void updateNeighbourForOutputSignal(BlockPos pos, Block block);
    @Shadow public abstract void onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState);
    @Shadow public abstract RegistryAccess registryAccess();
    @Accessor("thread") public abstract Thread arclight$getMainThread();
    // @formatter:on

    // ── Bukkit world state fields ─────────────────────────────────────────────

    protected CraftWorld world;

    /** Whether PvP is enabled on this world. */
    public boolean pvpMode;

    /**
     * Tracks the number of game ticks between each spawn attempt per category.
     * Populated from {@link CraftServer#getTicksPerSpawns(SpawnCategory)} on init.
     */
    public final Object2LongOpenHashMap<SpawnCategory> ticksPerSpawnCategory =
        new Object2LongOpenHashMap<>();

    /** Whether the world is currently generating structures (suppresses certain events). */
    public boolean populating;

    /** The custom Bukkit chunk generator for this world, or {@code null} for vanilla. */
    public ChunkGenerator generator;

    /** The Bukkit environment (dimension type) of this world. */
    protected org.bukkit.World.Environment environment;

    /** The custom biome provider for this world, or {@code null} for vanilla. */
    protected org.bukkit.generator.BiomeProvider biomeProvider;

    /** Per-world Spigot configuration. */
    public SpigotWorldConfig spigotConfig;

    /**
     * Stores the last block position that caused a physics {@link StackOverflowError}.
     * Made public+static via {@link TransformAccess} to allow bridge implementations
     * to set it without reflection.
     */
    @TransformAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
    private static BlockPos lastPhysicsProblem; // Spigot

    /**
     * When {@code true}, skips POI (Point of Interest) update notifications.
     * Used to prevent double-notifications during multi-block place operations.
     * See SPIGOT-5710.
     */
    public boolean preventPoiUpdated = false;

    /**
     * Captured block states from the current block place/interaction operation.
     * Uses {@link LinkedHashMap} to preserve insertion order for multi-block events.
     */
    public Map<BlockPos, CapturedBlockState> capturedBlockStates = new LinkedHashMap<>();

    /**
     * Captured block entities from the current block place/interaction operation.
     * Applied to the world after event confirmation.
     */
    public Map<BlockPos, BlockEntity> capturedTileEntities = new HashMap<>();

    /**
     * Whether this level is a "real" server-side level that participates in
     * Bukkit event dispatch. {@code false} for structure template levels,
     * client-side levels, etc.
     */
    @Unique
    private boolean arclight$isActual;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Shadow of the original NMS {@link Level} constructor.
     */
    @ShadowConstructor
    public void arclight$constructor(
            WritableLevelData worldInfo,
            ResourceKey<Level> dimension,
            RegistryAccess registryAccess,
            Holder<DimensionType> dimensionType,
            Supplier<ProfilerFiller> profiler,
            boolean isRemote,
            boolean isDebug,
            long seed,
            int maxNeighborUpdate) {
        throw new RuntimeException("Shadow constructor stub; should not be called directly.");
    }

    /**
     * Extended constructor that also sets Bukkit-specific fields:
     * the chunk generator, environment, and biome provider.
     * Called by {@link io.izzel.arclight.common.mixin.core.server.level.ServerLevelMixin}.
     */
    @CreateConstructor
    public void arclight$constructor(
            WritableLevelData worldInfo,
            ResourceKey<Level> dimension,
            RegistryAccess registryAccess,
            Holder<DimensionType> dimensionType,
            Supplier<ProfilerFiller> profiler,
            boolean isRemote,
            boolean isDebug,
            long seed,
            int maxNeighborUpdate,
            ChunkGenerator gen,
            org.bukkit.generator.BiomeProvider biomeProvider,
            org.bukkit.World.Environment env) {
        arclight$constructor(worldInfo, dimension, registryAccess, dimensionType,
            profiler, isRemote, isDebug, seed, maxNeighborUpdate);
        this.generator = gen;
        this.environment = env;
        this.biomeProvider = biomeProvider;
    }

    // ── Initialization injections ─────────────────────────────────────────────

    /**
     * Captures the {@link #arclight$isActual} flag as early as possible during
     * construction (before any fields are initialized), so we can skip Bukkit
     * dispatch for non-real levels even in early constructor calls.
     */
    @SuppressWarnings({"DefaultAnnotationParam", "UnnecessaryUnsafe"})
    @Inject(
        method = "<init>",
        at = @At(value = "CTOR_HEAD", unsafe = true)
    )
    private void arclight$preInit(
            WritableLevelData writableLevelData,
            ResourceKey resourceKey,
            RegistryAccess registryAccess,
            Holder holder,
            Supplier supplier,
            boolean bl,
            boolean bl2,
            long l,
            int i,
            CallbackInfo ci
    ) {
        this.arclight$isActual = DistValidate.isValid((LevelAccessor) (Object) this);
    }

    /**
     * Post-init injection that links the {@link WorldBorder} to this level and
     * populates the spawn-ticks-per-category map from the Bukkit server config.
     * Runs at order 1001 to execute after other standard post-init mixins.
     */
    @Inject(
        method = "<init>",
        at = @At("RETURN"),
        order = 1001
    )
    private void arclight$init(
            WritableLevelData info,
            ResourceKey<Level> dimension,
            RegistryAccess registryAccess,
            Holder<DimensionType> dimType,
            Supplier<ProfilerFiller> profiler,
            boolean isRemote,
            boolean isDebug,
            long seed,
            int maxNeighborUpdates,
            CallbackInfo ci
    ) {
        // Link the world border to this level so dimension scale is applied correctly
        ((WorldBorderBridge) this.worldBorder).bridge$setWorld((Level) (Object) this);

        // Populate spawn tick rates from the Bukkit server configuration
        for (SpawnCategory spawnCategory : SpawnCategory.values()) {
            if (CraftSpawnCategory.isValidForLimits(spawnCategory)) {
                this.ticksPerSpawnCategory.put(
                    spawnCategory,
                    this.getCraftServer().getTicksPerSpawns(spawnCategory)
                );
            }
        }
    }

    // ── WorldBridge implementation ────────────────────────────────────────────

    @Override
    public Map<BlockPos, CapturedBlockState> bridge$getCapturedBlockState() {
        return this.capturedBlockStates;
    }

    @Override
    public boolean arclight$isActual() {
        return arclight$isActual;
    }

    @Override
    public Map<BlockPos, BlockEntity> bridge$getCapturedBlockEntity() {
        return this.capturedTileEntities;
    }

    @Override
    public void bridge$setLastPhysicsProblem(BlockPos pos) {
        lastPhysicsProblem = pos;
    }

    @Override
    public Object2LongOpenHashMap<SpawnCategory> bridge$ticksPerSpawnCategory() {
        return this.ticksPerSpawnCategory;
    }

    /** Returns the NMS dimension stem key for this level. */
    public abstract ResourceKey<LevelStem> getTypeKey();

    @Override
    public ResourceKey<LevelStem> bridge$getTypeKey() {
        return getTypeKey();
    }

    @Override
    public SpigotWorldConfig bridge$spigotConfig() {
        // Fall back to the global default config if per-world config is not yet set
        return (spigotConfig != null) ? this.spigotConfig : ArclightWorldConfig.DEFAULT;
    }

    // ── Block state change hooks ──────────────────────────────────────────────

    /**
     * Intercepts {@code setBlock} calls to fire entity-change-block events.
     * If the event is cancelled (returns {@code false}), the block change is aborted.
     *
     * @param pos      the position being changed
     * @param newState the new block state
     * @param flags    block update flags
     * @param i        recursive update depth limit
     * @param cir      callback info for cancellation + return value injection
     */
    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void arclight$hooks(
            BlockPos pos,
            BlockState newState,
            int flags,
            int i,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!ArclightCaptures.setLastEntityChangeBlockResult(processCaptures(pos, newState, flags))) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Processes entity-driven block change captures.
     * Returns {@code true} if the change should proceed, {@code false} to cancel.
     */
    private boolean processCaptures(BlockPos pos, BlockState newState, int flags) {
        Entity entityChangeBlock = ArclightCaptures.getEntityChangeBlock();
        if (entityChangeBlock != null) {
            return CraftEventFactory.callEntityChangeBlockEvent(entityChangeBlock, pos, newState);
        }
        return true;
    }

    // ── Physics notification ──────────────────────────────────────────────────

    /**
     * Public entry point for notifying block physics and sending updates.
     * Delegates to {@link #bridge$forge$notifyAndUpdatePhysics}.
     */
    public void notifyAndUpdatePhysics(
            BlockPos pos,
            LevelChunk chunk,
            BlockState oldBlock,
            BlockState newBlock,
            BlockState actualBlock,
            int flags,
            int recursionLeft
    ) {
        this.bridge$forge$notifyAndUpdatePhysics(pos, chunk, oldBlock, newBlock, flags, recursionLeft);
    }

    /**
     * Handles the full block physics pipeline for a changed block position:
     * <ol>
     *   <li>Marks the block dirty for rendering</li>
     *   <li>Sends network block updates to clients</li>
     *   <li>Notifies neighboring blocks of the change</li>
     *   <li>Fires {@link BlockPhysicsEvent} for plugin hooks</li>
     *   <li>Updates POI data unless suppressed</li>
     * </ol>
     *
     * @param pos          the changed block position
     * @param levelchunk   the chunk containing the block (may be {@code null})
     * @param oldBlock     the previous block state
     * @param newBlock     the new block state being applied
     * @param flags        update flags (see {@link Block} constants)
     * @param recursionLeft remaining recursive update budget
     */
    @Override
    public void bridge$forge$notifyAndUpdatePhysics(
            BlockPos pos,
            @Nullable LevelChunk levelchunk,
            BlockState oldBlock,
            BlockState newBlock,
            int flags,
            int recursionLeft
    ) {
        BlockState currentState = this.getBlockState(pos);
        if (currentState != newBlock) return;

        // Mark dirty for render update
        if (oldBlock != currentState) {
            this.setBlocksDirty(pos, oldBlock, currentState);
        }

        // Send block update to clients if chunk is ready
        if ((flags & 2) != 0
                && (!this.isClientSide || (flags & 4) == 0)
                && (this.isClientSide || levelchunk == null
                    || (levelchunk.getFullStatus() != null
                        && levelchunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)))) {
            this.sendBlockUpdated(pos, oldBlock, newBlock, flags);
        }

        // Notify neighbors
        if ((flags & 1) != 0) {
            this.blockUpdated(pos, oldBlock.getBlock());
            if (!this.isClientSide && newBlock.hasAnalogOutputSignal()) {
                this.updateNeighbourForOutputSignal(pos, newBlock.getBlock());
            }
        }

        // Physics update — includes Bukkit BlockPhysicsEvent
        if ((flags & 16) == 0 && recursionLeft > 0) {
            int reducedFlags = flags & -34;
            oldBlock.updateIndirectNeighbourShapes(this, pos, reducedFlags, recursionLeft - 1);

            // Fire BlockPhysicsEvent for server-side worlds only
            if (this.world != null) {
                try {
                    BlockPhysicsEvent event = new BlockPhysicsEvent(
                        CraftBlock.at(this, pos),
                        CraftBlockData.fromData(newBlock)
                    );
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) {
                        return;
                    }
                } catch (StackOverflowError e) {
                    // Record the problematic position for debugging and abort physics
                    lastPhysicsProblem = pos;
                    return;
                }
            }

            newBlock.updateNeighbourShapes(this, pos, reducedFlags, recursionLeft - 1);
            newBlock.updateIndirectNeighbourShapes(this, pos, reducedFlags, recursionLeft - 1);
        }

        // Update POI data unless suppressed (SPIGOT-5710)
        if (!this.preventPoiUpdated) {
            this.onBlockStateChange(pos, oldBlock, currentState);
        }
    }

    // ── Bukkit world accessors ────────────────────────────────────────────────

    public CraftServer getCraftServer() {
        return (CraftServer) Bukkit.getServer();
    }

    /**
     * Returns the {@link CraftWorld} for this level.
     *
     * @throws UnsupportedOperationException if no CraftWorld is associated
     *         (e.g., for structure template levels or client-side levels)
     */
    public CraftWorld getWorld() {
        if (this.world == null) {
            throw new UnsupportedOperationException(String.format(
                "Level '%s' does not have an associated CraftWorld. " +
                "This method must not be called on non-server levels.",
                dimension().location()
            ));
        }
        return this.world;
    }

    /**
     * Returns the block entity at the given position.
     * The {@code validate} parameter is kept for API compatibility but ignored here;
     * override in subclasses to support validation.
     */
    public BlockEntity getBlockEntity(BlockPos pos, boolean validate) {
        return getBlockEntity(pos);
    }

    @Override
    public BlockEntity bridge$getTileEntity(BlockPos pos, boolean validate) {
        return getBlockEntity(pos, validate);
    }

    @Override
    public CraftServer bridge$getServer() {
        return (CraftServer) Bukkit.getServer();
    }

    @Override
    public CraftWorld bridge$getWorld() {
        return this.getWorld();
    }

    @Override
    public boolean bridge$isPvpMode() {
        return this.pvpMode;
    }

    @Override
    public boolean bridge$isPopulating() {
        return this.populating;
    }

    @Override
    public void bridge$setPopulating(boolean populating) {
        this.populating = populating;
    }

    @Override
    public ChunkGenerator bridge$getGenerator() {
        return generator;
    }

    @Override
    public boolean bridge$addEntity(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        // Delegate to the CraftWorld's underlying level to avoid entity duplication
        if (getWorld().getHandle() != (Object) this) {
            return ((WorldBridge) getWorld().getHandle()).bridge$addEntity(entity, reason);
        }
        this.bridge$pushAddEntityReason(reason);
        return this.addFreshEntity(entity);
    }

    @Override
    public void bridge$pushAddEntityReason(CreatureSpawnEvent.SpawnReason reason) {
        if (getWorld().getHandle() != (Object) this) {
            ((WorldBridge) getWorld().getHandle()).bridge$pushAddEntityReason(reason);
        }
    }

    @Override
    public CreatureSpawnEvent.SpawnReason bridge$getAddEntityReason() {
        if (getWorld().getHandle() != (Object) this) {
            return ((WorldBridge) getWorld().getHandle()).bridge$getAddEntityReason();
        }
        return null;
    }

    @Override
    public boolean bridge$preventPoiUpdated() {
        return this.preventPoiUpdated;
    }

    @Override
    public void bridge$preventPoiUpdated(boolean b) {
        this.preventPoiUpdated = b;
    }

    // ── Explosion interaction override ────────────────────────────────────────

    /**
     * Thread-local override for the explosion block interaction mode.
     * Set by {@link #arclight$standardExplodePre} and consumed by
     * {@link #arclight$standardExplodePost}.
     *
     * <p>Uses {@code transient} to clearly signal this is ephemeral state
     * that must not persist across explosion calls.</p>
     */
    @Unique
    private transient Explosion.BlockInteraction arclight$blockInteractionOverride;

    /**
     * If the explosion uses the custom {@code STANDARD} interaction mode,
     * replaces it with {@code BLOCK} (so vanilla logic runs) and stashes
     * the real desired interaction ({@link Explosion.BlockInteraction#DESTROY})
     * for application during the local variable load phase.
     */
    @ModifyVariable(
        method = "explode(" +
                 "Lnet/minecraft/world/entity/Entity;" +
                 "Lnet/minecraft/world/damagesource/DamageSource;" +
                 "Lnet/minecraft/world/level/ExplosionDamageCalculator;" +
                 "DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;" +
                 "ZLnet/minecraft/core/particles/ParticleOptions;" +
                 "Lnet/minecraft/core/particles/ParticleOptions;" +
                 "Lnet/minecraft/core/Holder;" +
                 ")Lnet/minecraft/world/level/Explosion;",
        ordinal = 0,
        at = @At("HEAD"),
        argsOnly = true
    )
    private Level.ExplosionInteraction arclight$standardExplodePre(
            Level.ExplosionInteraction interaction) {
        if (interaction == ArclightConstants.STANDARD) {
            arclight$blockInteractionOverride = Explosion.BlockInteraction.DESTROY;
            return Level.ExplosionInteraction.BLOCK;
        }
        return interaction;
    }

    /**
     * Replaces the local {@link Explosion.BlockInteraction} variable with the
     * override value stashed in {@link #arclight$blockInteractionOverride}, then
     * clears the override to prevent state leakage between consecutive explosions.
     */
    @ModifyVariable(
        method = "explode(" +
                 "Lnet/minecraft/world/entity/Entity;" +
                 "Lnet/minecraft/world/damagesource/DamageSource;" +
                 "Lnet/minecraft/world/level/ExplosionDamageCalculator;" +
                 "DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;" +
                 "ZLnet/minecraft/core/particles/ParticleOptions;" +
                 "Lnet/minecraft/core/particles/ParticleOptions;" +
                 "Lnet/minecraft/core/Holder;" +
                 ")Lnet/minecraft/world/level/Explosion;",
        at = @At(value = "LOAD", ordinal = 0)
    )
    private Explosion.BlockInteraction arclight$standardExplodePost(
            Explosion.BlockInteraction interaction) {
        try {
            return (arclight$blockInteractionOverride != null)
                ? arclight$blockInteractionOverride
                : interaction;
        } finally {
            // Always reset — even if an exception is thrown during explosion processing
            arclight$blockInteractionOverride = null;
        }
    }
}