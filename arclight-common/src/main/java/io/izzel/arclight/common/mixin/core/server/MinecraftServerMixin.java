package io.izzel.arclight.common.mixin.core.server;

import com.mojang.datafixers.DataFixer;
import io.izzel.arclight.api.ArclightVersion;
import io.izzel.arclight.common.bridge.bukkit.CraftServerBridge;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.bridge.core.world.level.WorldBridge;
import io.izzel.arclight.common.mod.ArclightConstants;
import io.izzel.arclight.common.mod.mixins.annotation.TransformAccess;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.mod.server.BukkitRegistry;
import io.izzel.arclight.common.mod.server.world.border.ArclightBorderChangeListener;
import io.izzel.arclight.common.mod.server.world.border.ArclightDelegatedBorderListener;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import io.izzel.arclight.common.mod.util.BukkitOptionParser;
import io.izzel.arclight.common.util.IteratorUtil;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import io.izzel.arclight.mixin.Local;
import it.unimi.dsi.fastutil.longs.LongIterator;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.Services;
import net.minecraft.server.TickTask;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Mth;
import net.minecraft.util.TimeSource;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.craftbukkit.v.CraftRegistry;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.scoreboard.CraftScoreboardManager;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.PluginLoadOrder;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.spigotmc.WatchdogThread;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.lang.management.ManagementFactory;
import java.net.Proxy;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Core mixin for {@link MinecraftServer} that bridges the NMS server with
 * the Bukkit layer and adds J2K server lifecycle management.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin
        extends ReentrantBlockableEventLoop<TickTask>
        implements MinecraftServerBridge, CommandSourceBridge {

    // @formatter:off
    @Shadow private int tickCount;
    @Shadow protected long nextTickTimeNanos;
    @Shadow @Final static Logger LOGGER;
    @Shadow public abstract Commands getCommands();
    @Shadow protected abstract void updateMobSpawningFlags();
    @Shadow public abstract ServerLevel overworld();
    @Shadow private Map<ResourceKey<Level>, ServerLevel> levels;
    @Shadow protected abstract void setupDebugLevel(WorldData p_240778_1_);
    @Shadow protected WorldData worldData;
    @Shadow private static void setInitialSpawn(ServerLevel p_177897_, ServerLevelData p_177898_, boolean p_177899_, boolean p_177900_) {}
    @Shadow public abstract boolean isSpawningMonsters();
    @Shadow public abstract boolean isSpawningAnimals();
    @Shadow @Final public Executor executor;
    @Shadow public abstract RegistryAccess.Frozen registryAccess();
    @Shadow public MinecraftServer.ReloadableResources resources;
    @Shadow public abstract LayeredRegistryAccess<RegistryLayer> registries();
    @Shadow public abstract Iterable<ServerLevel> getAllLevels();
    @Shadow private PlayerList playerList;
    // @formatter:on

    public MinecraftServerMixin(String name) {
        super(name);
    }

    // ── Server state fields ───────────────────────────────────────────────────

    public WorldLoader.DataLoadContext worldLoader;
    private boolean forceTicks;
    public CraftServer server;
    public OptionSet options;
    public ConsoleCommandSender console;
    public RemoteConsoleCommandSender remoteConsole;
    public java.util.Queue<Runnable> processQueue =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    public int autosavePeriod;
    public Commands vanillaCommandDispatcher;
    private boolean hasStopped = false;
    private final Object stopLock = new Object();

    // ── TPS tracking ──────────────────────────────────────────────────────────

    private static final int TPS             = 20;
    private static final int TICK_TIME       = 1_000_000_000 / TPS;
    private static final int SAMPLE_INTERVAL = 100;

    @TransformAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
    private static int currentTick = (int) (System.currentTimeMillis() / 50);

    public final double[] recentTps = new double[3];

    // ── Server stopped flag ───────────────────────────────────────────────────

    public boolean hasStopped() {
        synchronized (stopLock) {
            return hasStopped;
        }
    }

    @Override
    public boolean bridge$hasStopped() {
        return hasStopped();
    }

    // ── Initialization ────────────────────────────────────────────────────────

    @Inject(method = "<init>", at = @At("RETURN"))
    public void arclight$loadOptions(
            Thread thread,
            LevelStorageSource.LevelStorageAccess levelSave,
            PackRepository packRepository,
            WorldStem worldStem,
            Proxy proxy,
            DataFixer dataFixer,
            Services services,
            ChunkProgressListenerFactory listenerFactory,
            CallbackInfo ci
    ) {
        String[] args = ManagementFactory.getRuntimeMXBean()
            .getInputArguments().toArray(new String[0]);
        OptionParser parser = new BukkitOptionParser();
        try {
            options = parser.parse(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.vanillaCommandDispatcher = worldStem.dataPackResources().getCommands();
        this.worldLoader = ArclightCaptures.getDataLoadContext();
        ArclightServer.setMinecraftServer((MinecraftServer) (Object) this);
    }

    // ── TPS / tick timing ────────────────────────────────────────────────────

    @Decorate(
        method = "runServer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;buildServerStatus()" +
                     "Lnet/minecraft/network/protocol/status/ServerStatus;"
        )
    )
    private ServerStatus arclight$initTickParam(
            MinecraftServer instance,
            @Local(allocate = "tickSection") long tickSection,
            @Local(allocate = "tickCount") long tickCount
    ) throws Throwable {
        ServerStatus status = (ServerStatus) DecorationOps.callsite().invoke(instance);
        Arrays.fill(recentTps, 20);
        tickSection = Util.getMillis();
        tickCount   = 1;
        DecorationOps.blackhole().invoke(tickSection, tickCount);
        return status;
    }

    @Decorate(
        method = "runServer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;startMetricsRecordingTick()V"
        )
    )
    private void arclight$updateTickParam(
            MinecraftServer instance,
            @Local(allocate = "tickSection") long tickSection,
            @Local(allocate = "tickCount") long tickCount
    ) throws Throwable {
        if (tickCount++ % SAMPLE_INTERVAL == 0) {
            long now = Util.getMillis();
            double tps = 1E3 / (now - tickSection) * SAMPLE_INTERVAL;
            recentTps[0] = calcTps(recentTps[0], 0.92,   tps);
            recentTps[1] = calcTps(recentTps[1], 0.9835, tps);
            recentTps[2] = calcTps(recentTps[2], 0.9945, tps);
            tickSection  = now;
        }
        DecorationOps.blackhole().invoke(tickSection, tickCount);
        currentTick = (int) (System.currentTimeMillis() / 50);
        DecorationOps.callsite().invoke(instance);
    }

    @Decorate(
        method = "runServer",
        at = @At(
            value = "INVOKE",
            remap = false,
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
        )
    )
    private void arclight$warnOnLoad(
            Logger logger, String msg, Object a, Object b) throws Throwable {
        if (server.getWarnOnOverload()) {
            DecorationOps.callsite().invoke(logger, msg, a, b);
        }
    }

    @Inject(
        method = "runServer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;onServerExit()V"
        )
    )
    private void arclight$watchdogExit(CallbackInfo ci) {
        WatchdogThread.doStop();
    }

    private static double calcTps(double avg, double exp, double tps) {
        return (avg * exp) + (tps * (1.0 - exp));
    }

    // ── Stop / shutdown ───────────────────────────────────────────────────────

    @Inject(method = "stopServer", cancellable = true, at = @At("HEAD"))
    public void arclight$setStopped(CallbackInfo ci) {
        synchronized (stopLock) {
            if (hasStopped) {
                ci.cancel();
                return;
            }
            hasStopped = true;
        }
    }

    @Inject(
        method = "stopServer",
        at = @At(
            value = "INVOKE",
            remap = false,
            ordinal = 0,
            shift = At.Shift.AFTER,
            target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V"
        )
    )
    public void arclight$unloadPlugins(CallbackInfo ci) {
        if (this.server != null) {
            this.server.disablePlugins();
        }
    }

    // ── World creation ────────────────────────────────────────────────────────

    @Decorate(
        method = "createLevels",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Set;iterator()Ljava/util/Iterator;"
        )
    )
    private Iterator<Map.Entry<ResourceKey<LevelStem>, LevelStem>> arclight$skipBukkitLevels(
            Set<Map.Entry<ResourceKey<LevelStem>, LevelStem>> instance
    ) throws Throwable {
        @SuppressWarnings("unchecked")
        Iterator<Map.Entry<ResourceKey<LevelStem>, LevelStem>> raw =
            (Iterator<Map.Entry<ResourceKey<LevelStem>, LevelStem>>)
            DecorationOps.callsite().invoke(instance);

        if (ArclightConfig.spec().getExperimental().canOverrideWorldgen()) {
            return IteratorUtil.filter(raw, entry -> {
                var loc = entry.getKey().location();
                if (loc.getNamespace().equals("bukkit")) {
                    ArclightServer.LOGGER.info(
                        "Deferred {} custom dimension creation", loc
                    );
                    return false;
                }
                return true;
            });
        }
        return raw;
    }

    @Inject(method = "createLevels", at = @At("RETURN"))
    public void arclight$enablePlugins(ChunkProgressListener listener, CallbackInfo ci) {
        this.bridge$forge$unlockRegistries();
        this.server.enablePlugins(PluginLoadOrder.POSTWORLD);
        this.bridge$forge$lockRegistries();
        this.server.getPluginManager().callEvent(
            new ServerLoadEvent(ServerLoadEvent.LoadType.STARTUP)
        );
    }

    @Inject(
        method = "createLevels",
        at = @At(
            value = "NEW",
            ordinal = 0,
            target = "(Lnet/minecraft/server/MinecraftServer;" +
                     "Ljava/util/concurrent/Executor;" +
                     "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;" +
                     "Lnet/minecraft/world/level/storage/ServerLevelData;" +
                     "Lnet/minecraft/resources/ResourceKey;" +
                     "Lnet/minecraft/world/level/dimension/LevelStem;" +
                     "Lnet/minecraft/server/level/progress/ChunkProgressListener;" +
                     "ZJLjava/util/List;Z" +
                     "Lnet/minecraft/world/RandomSequences;)" +
                     "Lnet/minecraft/server/level/ServerLevel;"
        )
    )
    private void arclight$registerEnv(ChunkProgressListener listener, CallbackInfo ci) {
        BukkitRegistry.registerEnvironments(
            this.registryAccess().registryOrThrow(Registries.LEVEL_STEM)
        );
    }

    @Decorate(
        method = "createLevels",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/level/border/WorldBorder;)" +
                     "Lnet/minecraft/world/level/border/BorderChangeListener$DelegateBorderChangeListener;"
        )
    )
    private BorderChangeListener.DelegateBorderChangeListener arclight$configurableDelegatedListener(
            WorldBorder border
    ) throws Throwable {
        return new ArclightDelegatedBorderListener(
            border,
            (BorderChangeListener.DelegateBorderChangeListener)
                DecorationOps.callsite().invoke(border)
        );
    }

    @Decorate(
        method = "createLevels",
        at = @At(
            value = "INVOKE",
            remap = false,
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        )
    )
    private Object arclight$worldInit(
            Map<Object, Object> instance, Object k, Object v,
            ChunkProgressListener listener
    ) throws Throwable {
        if (v instanceof ServerLevel level) {
            if (((CraftServer) Bukkit.getServer()).scoreboardManager == null) {
                ((CraftServer) Bukkit.getServer()).scoreboardManager =
                    new CraftScoreboardManager(
                        (MinecraftServer) (Object) this,
                        level.getScoreboard()
                    );
            }
            if (((WorldBridge) level).bridge$getGenerator() != null) {
                level.bridge$getWorld().getPopulators().addAll(
                    ((WorldBridge) level).bridge$getGenerator()
                        .getDefaultPopulators(level.bridge$getWorld())
                );
            }
            Bukkit.getPluginManager().callEvent(new WorldInitEvent(level.bridge$getWorld()));
            level.getWorldBorder().addListener(ArclightBorderChangeListener.typed());
        }
        return DecorationOps.callsite().invoke(instance, k, v);
    }

    // ── Tick management ───────────────────────────────────────────────────────

    @Inject(method = "haveTime", cancellable = true, at = @At("HEAD"))
    private void arclight$forceAheadOfTime(CallbackInfoReturnable<Boolean> cir) {
        if (this.forceTicks) cir.setReturnValue(true);
    }

    private void executeModerately() {
        this.runAllTasks();
        this.bridge$drainQueuedTasks();
        java.util.concurrent.locks.LockSupport.parkNanos("executing tasks", 1000L);
    }

    @Override
    public void bridge$drainQueuedTasks() {
        Runnable task;
        while ((task = processQueue.poll()) != null) {
            task.run();
        }
    }

    @Inject(method = "tickChildren", at = @At("HEAD"))
    public void arclight$runScheduler(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ArclightConstants.currentTick = (int) (System.currentTimeMillis() / 50);
        this.server.getScheduler().mainThreadHeartbeat(this.tickCount);
        this.bridge$drainQueuedTasks();
    }

    @Inject(
        method = "stopServer",
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z"
        )
    )
    private void arclight$unloadLevel(CallbackInfo ci) {
        for (ServerLevel level : this.getAllLevels()) {
            ((CraftServerBridge) Bukkit.getServer()).bridge$removeWorld(level);
        }
    }

    @Inject(
        method = "saveAllChunks",
        cancellable = true,
        locals = LocalCapture.CAPTURE_FAILHARD,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;overworld()Lnet/minecraft/server/level/ServerLevel;"
        )
    )
    private void arclight$skipSave(
            boolean suppressLog, boolean flush, boolean forced,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(!this.levels.isEmpty());
    }

    @Inject(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/WorldData;setDataConfiguration(" +
                     "Lnet/minecraft/world/level/WorldDataConfiguration;)V"
        )
    )
    private void arclight$syncCommand(CallbackInfo ci) {
        this.server.syncCommands();
    }

    @Inject(
        method = "getServerModName",
        remap = false,
        cancellable = true,
        at = @At("RETURN")
    )
    private void arclight$brand(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(
            cir.getReturnValue() + " arclight/" + ArclightVersion.current().getReleaseName()
        );
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private boolean arclight$skipWatchdogSetTime = false;

    @Override
    public void arclight$extendNextTickTimeTo(TimeSource.NanoTimeSource timeSource) {
        if (!arclight$skipWatchdogSetTime) {
            this.nextTickTimeNanos = timeSource.getAsLong();
        }
    }

    protected void arclight$tickSpigotWatchdogInternal() {
        try {
            arclight$skipWatchdogSetTime = true;
            WatchdogThread.tick();
        } finally {
            arclight$skipWatchdogSetTime = false;
        }
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void arclight$tickWatchdog(CallbackInfo ci) {
        arclight$tickSpigotWatchdogInternal();
    }

    // ── World lifecycle ───────────────────────────────────────────────────────

    /**
     * @author IzzelAliz
     * @reason Full world preparation with Bukkit event dispatch and forced chunks
     */
    @Overwrite
    public final void prepareLevels(ChunkProgressListener listener) {
        ServerLevel overworld = this.overworld();
        this.forceTicks = true;

        LOGGER.info("Preparing start region for dimension {}",
            overworld.dimension().location());
        BlockPos spawnPos = overworld.getSharedSpawnPos();
        listener.updateSpawnPos(new ChunkPos(spawnPos));

        ServerChunkCache chunkCache = overworld.getChunkSource();
        this.nextTickTimeNanos = Util.getNanos();
        overworld.setDefaultSpawnPos(spawnPos, overworld.getSharedSpawnAngle());

        int spawnRadius = overworld.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        int targetChunks = spawnRadius > 0
            ? Mth.square(ChunkProgressListener.calculateDiameter(spawnRadius))
            : 0;

        while (chunkCache.getTickingGenerated() < targetChunks) {
            this.executeModerately();
        }
        this.executeModerately();

        // Force-load chunks marked as forced in each world
        for (ServerLevel level : this.levels.values()) {
            if (level.bridge$getWorld().getKeepSpawnInMemory()) {
                ForcedChunksSavedData forced = level.getDataStorage()
                    .get(ForcedChunksSavedData.factory(), "chunks");
                if (forced != null) {
                    LongIterator it = forced.getChunks().iterator();
                    while (it.hasNext()) {
                        long key = it.nextLong();
                        level.getChunkSource().updateChunkForced(new ChunkPos(key), true);
                    }
                    this.bridge$forge$reinstatePersistentChunks(level, forced);
                }
            }
            Bukkit.getPluginManager().callEvent(new WorldLoadEvent(level.bridge$getWorld()));
        }

        this.executeModerately();
        listener.stop();
        this.updateMobSpawningFlags();
        this.forceTicks = false;
    }

    public void initWorld(
            ServerLevel serverWorld,
            ServerLevelData worldInfo,
            WorldData saveData,
            WorldOptions worldOptions
    ) {
        if (((WorldBridge) serverWorld).bridge$getGenerator() != null) {
            serverWorld.bridge$getWorld().getPopulators().addAll(
                ((WorldBridge) serverWorld).bridge$getGenerator()
                    .getDefaultPopulators(serverWorld.bridge$getWorld())
            );
        }

        WorldBorder border = serverWorld.getWorldBorder();
        border.applySettings(worldInfo.getWorldBorder());
        playerList.addWorldborderListener(serverWorld);

        this.server.getPluginManager().callEvent(
            new WorldInitEvent(serverWorld.bridge$getWorld())
        );

        if (!worldInfo.isInitialized()) {
            try {
                setInitialSpawn(
                    serverWorld, worldInfo,
                    worldOptions.generateBonusChest(),
                    saveData.isDebugWorld()
                );
                worldInfo.setInitialized(true);
                if (saveData.isDebugWorld()) {
                    this.setupDebugLevel(this.worldData);
                }
            } catch (Throwable t) {
                CrashReport crash = CrashReport.forThrowable(t, "Exception initializing level");
                try { serverWorld.fillReportDetails(crash); } catch (Throwable ignored) {}
                throw new ReportedException(crash);
            }
            worldInfo.setInitialized(true);
        }
    }

    public void prepareLevels(ChunkProgressListener listener, ServerLevel serverWorld) {
        this.bridge$forge$markLevelsDirty();
        if (!serverWorld.bridge$getWorld().getKeepSpawnInMemory()) return;

        this.forceTicks = true;
        LOGGER.info("Preparing start region for dimension {}",
            serverWorld.dimension().location());

        BlockPos spawnPos = serverWorld.getSharedSpawnPos();
        listener.updateSpawnPos(new ChunkPos(spawnPos));

        ServerChunkCache chunkCache = serverWorld.getChunkSource();
        this.nextTickTimeNanos = Util.getNanos();
        serverWorld.setDefaultSpawnPos(spawnPos, serverWorld.getSharedSpawnAngle());

        int spawnRadius = serverWorld.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        int targetChunks = spawnRadius > 0
            ? Mth.square(ChunkProgressListener.calculateDiameter(spawnRadius))
            : 0;

        while (chunkCache.getTickingGenerated() < targetChunks) {
            this.executeModerately();
        }
        this.executeModerately();

        ForcedChunksSavedData forced = serverWorld.getDataStorage()
            .get(ForcedChunksSavedData.factory(), "chunks");
        if (forced != null) {
            LongIterator it = forced.getChunks().iterator();
            while (it.hasNext()) {
                serverWorld.getChunkSource().updateChunkForced(
                    new ChunkPos(it.nextLong()), true
                );
            }
            this.bridge$forge$reinstatePersistentChunks(serverWorld, forced);
        }

        this.executeModerately();
        listener.stop();
        serverWorld.setSpawnSettings(this.isSpawningMonsters(), this.isSpawningAnimals());
        this.forceTicks = false;
    }

    public void addLevel(ServerLevel level) {
        this.levels.put(level.dimension(), level);
        this.arclight$onServerLoad(level);
        this.bridge$forge$markLevelsDirty();
    }

    public void removeLevel(ServerLevel level) {
        this.levels.remove(level.dimension());
        this.arclight$onServerUnload(level);
        this.bridge$forge$markLevelsDirty();
        ((CraftServerBridge) Bukkit.getServer()).bridge$removeWorld(level);
    }

    // ── Bridge implementation ─────────────────────────────────────────────────

    @Override
    public void bridge$setConsole(ConsoleCommandSender console) {
        this.console = console;
    }

    @Override
    public void bridge$setServer(CraftServer server) {
        this.server = server;
    }

    @Override
    public CraftServer bridge$getServer() {
        if (this.server == null) {
            throw new IllegalStateException("CraftServer has not been initialized yet");
        }
        return this.server;
    }

    @Override
    public RemoteConsoleCommandSender bridge$getRemoteConsole() {
        return remoteConsole;
    }

    @Override
    public void bridge$queuedProcess(Runnable runnable) {
        processQueue.add(runnable);
    }

    public CommandSender getBukkitSender(CommandSourceStack wrapper) {
        return console;
    }

    @Override
    public CommandSender bridge$getBukkitSender(CommandSourceStack wrapper) {
        return getBukkitSender(wrapper);
    }

    @Override
    public Commands bridge$getVanillaCommands() {
        return this.vanillaCommandDispatcher;
    }

    public boolean isDebugging() {
        return false;
    }

    @TransformAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
    private static MinecraftServer getServer() {
        return Bukkit.getServer() instanceof CraftServer cs ? cs.getServer() : null;
    }

    @TransformAccess(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
    @Deprecated
    private static RegistryAccess getDefaultRegistryAccess() {
        return CraftRegistry.getMinecraftRegistry();
    }
}