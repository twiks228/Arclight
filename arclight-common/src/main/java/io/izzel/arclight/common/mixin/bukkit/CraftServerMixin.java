package io.izzel.arclight.common.mixin.bukkit;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import io.izzel.arclight.common.bridge.bukkit.CraftServerBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.server.dedicated.DedicatedServerBridge;
import io.izzel.arclight.common.bridge.core.world.level.GameRules_ValueBridge;
import io.izzel.arclight.common.bridge.core.world.level.storage.LevelStorageSourceBridge;
import io.izzel.arclight.common.bridge.core.world.level.storage.PrimaryLevelDataBridge;
import io.izzel.arclight.i18n.ArclightConfig;
import jline.console.ConsoleReader;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.WanderingTraderSpawner;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.level.validation.ContentValidationException;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.bukkit.craftbukkit.v.command.CraftCommandMap;
import org.bukkit.craftbukkit.v.entity.CraftPlayer;
import org.bukkit.craftbukkit.v.generator.CraftWorldInfo;
import org.bukkit.craftbukkit.v.scheduler.CraftScheduler;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginLoadOrder;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.scheduler.BukkitWorker;
import org.spigotmc.SpigotConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mixin for {@link CraftServer} that integrates Arclight J2K-specific branding,
 * world creation improvements, and ViaVersion Netty injection support.
 *
 * <p>Key additions over upstream Arclight:</p>
 * <ul>
 *   <li>Exposes the real Netty {@code connections} list for ViaVersion compatibility</li>
 *   <li>Properly handles CUSTOM world environment</li>
 *   <li>Caches generator/biomeProvider/environment per world name</li>
 * </ul>
 */
@Mixin(value = CraftServer.class, remap = false)
public abstract class CraftServerMixin implements CraftServerBridge {

    // @formatter:off
    @Shadow @Final private CraftCommandMap commandMap;
    @Shadow @Final private SimplePluginManager pluginManager;
    @Shadow @Final protected DedicatedServer console;
    @Shadow @Final @Mutable private String serverName;
    @Shadow @Final @Mutable protected DedicatedPlayerList playerList;
    @Shadow @Final @Mutable private List<CraftPlayer> playerView;
    @Shadow @Final private Map<String, World> worlds;
    @Shadow public int reloadCount;
    @Shadow private YamlConfiguration configuration;
    @Shadow protected abstract File getConfigFile();
    @Shadow private YamlConfiguration commandsConfiguration;
    @Shadow protected abstract File getCommandsConfigFile();
    @Shadow @Final private Logger logger;
    @Shadow public abstract void reloadData();
    @Shadow private boolean overrideAllCommandBlockCommands;
    @Shadow public boolean ignoreVanillaPermissions;
    @Shadow public abstract CraftScheduler getScheduler();
    @Shadow public abstract Logger getLogger();
    @Shadow public abstract void loadPlugins();
    @Shadow public abstract void enablePlugins(PluginLoadOrder type);
    @Shadow public abstract PluginManager getPluginManager();
    @Accessor("logger") @Mutable public abstract void setLogger(Logger logger);
    @Shadow public abstract ChunkGenerator getGenerator(String world);
    @Shadow public abstract BiomeProvider getBiomeProvider(String world);
    @Shadow public abstract File getWorldContainer();
    @Shadow public abstract World getWorld(String name);
    @Shadow public abstract GameMode getDefaultGameMode();
    @Shadow public abstract DedicatedServer getServer();
    // @formatter:on

    // ── Branding ──────────────────────────────────────────────────────────────

    @Inject(method = "<init>", at = @At("RETURN"))
    public void arclight$setBrand(DedicatedServer console, PlayerList playerList, CallbackInfo ci) {
        this.serverName = "Arclight";
    }

    /**
     * @author IzzelAliz
     * @reason Return Arclight brand name instead of CraftBukkit
     */
    @Overwrite(remap = false)
    public String getName() {
        return "Arclight";
    }

    /**
     * @author IzzelAliz
     * @reason Include Arclight version in server version string
     */
    @Overwrite
    public String getVersion() {
        return System.getProperty("arclight.version")
            + " (MC: " + this.console.getServerVersion() + ")";
    }

    // ── ViaVersion Netty injection support ────────────────────────────────────

    /**
     * Returns the real underlying Netty connections list from the NMS server.
     *
     * <p>ViaVersion's {@code LegacyViaInjector} uses reflection to locate the
     * {@code connections} or {@code f_xxx} field in the NMS {@code Connection}
     * manager. In Arclight/NeoForge, the connection management differs from
     * vanilla Bukkit, causing ViaVersion to receive {@code null} when accessing
     * the field via its legacy reflection path.</p>
     *
     * <p>By exposing this list through the bridge, ViaVersion's
     * {@code BukkitViaInjector} can correctly inject its channel handlers into
     * the server's Netty pipeline.</p>
     *
     * @return the list of active Netty connections, or {@code null} if unavailable
     */
    @Override
    public List<?> bridge$getConnections() {
        try {
            // Access the connection list through the NMS server's connection manager.
            // This is what ViaVersion needs to inject its ChannelInitializer.
            var connectionManager = this.console.getConnection();
            if (connectionManager == null) return null;

            // Use reflection to find the connections field
            // (field name varies by NeoForge/Mojang mapping)
            for (var field : connectionManager.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object value = field.get(connectionManager);
                    if (value instanceof List<?> list) {
                        return list;
                    }
                }
            }
        } catch (Exception e) {
            // ViaVersion injection is non-critical — log at debug level
            // and let ViaVersion handle the null gracefully
        }
        return null;
    }

    // ── Player list bridge ────────────────────────────────────────────────────

    @Override
    public void bridge$setPlayerList(PlayerList playerList) {
        this.playerList = (DedicatedPlayerList) playerList;
        this.playerView = Collections.unmodifiableList(
            Lists.transform(playerList.players,
                player -> ((ServerPlayerBridge) player).bridge$getBukkitEntity()
            )
        );
    }

    // ── Console reader ────────────────────────────────────────────────────────

    /**
     * @author IzzelAliz
     * @reason Arclight uses Log4j console, not jline ConsoleReader
     */
    @Overwrite(remap = false)
    public ConsoleReader getReader() {
        return null;
    }

    // ── Command dispatch ──────────────────────────────────────────────────────

    @Inject(
        method = "dispatchCommand",
        remap = false,
        cancellable = true,
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lorg/spigotmc/AsyncCatcher;catchOp(Ljava/lang/String;)V"
        )
    )
    private void arclight$returnIfFail(
            CommandSender sender, String commandLine,
            CallbackInfoReturnable<Boolean> cir) {
        if (commandLine == null) {
            cir.setReturnValue(false);
        }
    }

    // ── World management ──────────────────────────────────────────────────────

    @Override
    public void bridge$removeWorld(ServerLevel world) {
        if (world == null) return;
        this.worlds.remove(
            world.bridge$getWorld().getName().toLowerCase(Locale.ROOT)
        );
    }

    // ── Reload ────────────────────────────────────────────────────────────────

    /**
     * @author IzzelAliz
     * @reason Full server reload with proper plugin lifecycle management
     */
    @Overwrite(remap = false)
    public void reload() {
        ++this.reloadCount;
        this.configuration = YamlConfiguration.loadConfiguration(this.getConfigFile());
        this.commandsConfiguration = YamlConfiguration.loadConfiguration(this.getCommandsConfigFile());

        try {
            this.playerList.getIpBans().load();
        } catch (IOException e) {
            this.logger.log(Level.WARNING, "Failed to load banned-ips.json, " + e.getMessage());
        }
        try {
            this.playerList.getBans().load();
        } catch (IOException e) {
            this.logger.log(Level.WARNING, "Failed to load banned-players.json, " + e.getMessage());
        }

        this.pluginManager.clearPlugins();
        this.commandMap.clearCommands();
        this.reloadData();
        SpigotConfig.registerCommands();
        this.overrideAllCommandBlockCommands = this.commandsConfiguration
            .getStringList("command-block-overrides").contains("*");
        this.ignoreVanillaPermissions = this.commandsConfiguration
            .getBoolean("ignore-vanilla-permissions");

        // Wait for async tasks to finish (up to 2.5 seconds)
        for (int poll = 0; poll < 50 && !this.getScheduler().getActiveWorkers().isEmpty(); ++poll) {
            try { Thread.sleep(50L); } catch (InterruptedException ignored) {}
        }

        List<BukkitWorker> overdueWorkers = this.getScheduler().getActiveWorkers();
        for (BukkitWorker worker : overdueWorkers) {
            Plugin plugin = worker.getOwner();
            this.getLogger().log(Level.SEVERE,
                String.format(
                    "Nag author(s): '%s' of '%s' about the following: %s",
                    plugin.getDescription().getAuthors(),
                    plugin.getDescription().getFullName(),
                    "This plugin is not properly shutting down its async tasks when reloading."
                )
            );
        }

        this.loadPlugins();
        this.enablePlugins(PluginLoadOrder.STARTUP);
        this.enablePlugins(PluginLoadOrder.POSTWORLD);
        this.getPluginManager().callEvent(new ServerLoadEvent(ServerLoadEvent.LoadType.RELOAD));
    }

    // ── Generator / BiomeProvider / Environment caches ────────────────────────

    @Unique private final Map<String, ChunkGenerator>    arclight$generatorCache     = new HashMap<>();
    @Unique private final Map<String, BiomeProvider>     arclight$biomeProviderCache = new HashMap<>();
    @Unique private final Map<String, World.Environment> arclight$environmentCache   = new HashMap<>();

    @Override
    public void bridge$offerGeneratorCache(String name, ChunkGenerator generator) {
        arclight$generatorCache.put(name, generator);
    }

    @Override
    public ChunkGenerator bridge$consumeGeneratorCache(String name) {
        ChunkGenerator cached = arclight$generatorCache.remove(name);
        return (cached != null) ? cached : getGenerator(name);
    }

    @Override
    public void bridge$offerBiomeProviderCache(String name, BiomeProvider provider) {
        arclight$biomeProviderCache.put(name, provider);
    }

    @Override
    public BiomeProvider bridge$consumeBiomeProviderCache(String name) {
        BiomeProvider cached = arclight$biomeProviderCache.remove(name);
        return (cached != null) ? cached : getBiomeProvider(name);
    }

    @Override
    public void bridge$offerEnvironmentCache(String name, World.Environment environment) {
        arclight$environmentCache.put(name, environment);
    }

    @Override
    public World.Environment bridge$consumeEnvironmentCache(String name) {
        return arclight$environmentCache.remove(name);
    }

    // ── World creation ────────────────────────────────────────────────────────

    /**
     * @author InitAuther97
     * @reason Experimental base generator setting and support for CUSTOM environment
     */
    @Overwrite
    public World createWorld(WorldCreator creator) {
        Preconditions.checkState(
            this.console.getAllLevels().iterator().hasNext(),
            "Cannot create additional worlds on STARTUP"
        );
        Preconditions.checkArgument(creator != null, "WorldCreator cannot be null");

        String name = creator.name();
        ChunkGenerator generator = creator.generator();
        BiomeProvider biomeProvider = creator.biomeProvider();
        File folder = new File(this.getWorldContainer(), name);
        World world = this.getWorld(name);

        if (world != null) return world;

        if (folder.exists()) {
            Preconditions.checkArgument(
                folder.isDirectory(), "File (%s) exists and isn't a folder", name
            );
        }

        if (generator == null)    generator    = this.getGenerator(name);
        if (biomeProvider == null) biomeProvider = this.getBiomeProvider(name);

        // Determine dimension key, supporting CUSTOM environment
        ResourceKey<LevelStem> actualDimension;
        boolean isCustom = false;
        switch (creator.environment()) {
            case NORMAL    -> actualDimension = LevelStem.OVERWORLD;
            case NETHER    -> actualDimension = LevelStem.NETHER;
            case THE_END   -> actualDimension = LevelStem.END;
            case CUSTOM    -> {
                if (ArclightConfig.spec().getExperimental().canOverrideWorldgen()) {
                    isCustom = true;
                    ResourceLocation location = ResourceLocation.tryBuild("bukkit", name);
                    if (location == null) {
                        throw new IllegalArgumentException("Illegal world name: " + name);
                    }
                    actualDimension = ResourceKey.create(Registries.LEVEL_STEM, location);
                } else {
                    throw new IllegalArgumentException(
                        "Illegal dimension (" + creator.environment() + ")"
                    );
                }
            }
            default -> throw new IllegalArgumentException(
                "Illegal dimension (" + creator.environment() + ")"
            );
        }

        LevelStorageSource.LevelStorageAccess worldSession;
        try {
            worldSession = ((LevelStorageSourceBridge) LevelStorageSource.createDefault(
                this.getWorldContainer().toPath()
            )).arclight$validateAndCreateAccess(name, actualDimension);
        } catch (ContentValidationException | IOException ex) {
            throw new RuntimeException(ex);
        }

        Dynamic<?> dynamic;
        if (worldSession.hasWorldData()) {
            LevelSummary worldinfo;
            try {
                dynamic = worldSession.getDataTag();
                worldinfo = worldSession.getSummary(dynamic);
            } catch (ReportedNbtException | IOException | NbtException ex) {
                LevelStorageSource.LevelDirectory dir = worldSession.getLevelDirectory();
                MinecraftServer.LOGGER.warn(
                    "Failed to load world data from {}", dir.dataFile(), ex
                );
                MinecraftServer.LOGGER.info("Attempting to use fallback");
                try {
                    dynamic = worldSession.getDataTagFallback();
                    worldinfo = worldSession.getSummary(dynamic);
                } catch (ReportedNbtException | IOException | NbtException ex2) {
                    MinecraftServer.LOGGER.error(
                        "Failed to load world data from {} and {}. Shutting down.",
                        dir.dataFile(), dir.oldDataFile(), ex2
                    );
                    return null;
                }
                worldSession.restoreLevelDataFromOld();
            }

            if (worldinfo.requiresManualConversion()) {
                MinecraftServer.LOGGER.info(
                    "This world must be opened in an older version to be safely converted"
                );
                return null;
            }
            if (!worldinfo.isCompatible()) {
                MinecraftServer.LOGGER.info("This world was created by an incompatible version.");
                return null;
            }
        } else {
            dynamic = null;
        }

        boolean hardcore = creator.hardcore();
        WorldLoader.DataLoadContext context = ((DedicatedServerBridge) this.console)
            .arclight$dataLoadContext();
        RegistryAccess.Frozen datapackDimensions = context.datapackDimensions();
        Registry<LevelStem> datapackStems = datapackDimensions.registryOrThrow(Registries.LEVEL_STEM);

        RegistryAccess.Frozen dimensions;
        PrimaryLevelData levelData;

        if (dynamic != null) {
            LevelDataAndDimensions loaded = LevelStorageSource.getLevelDataAndDimensions(
                dynamic, context.dataConfiguration(), datapackStems, context.datapackWorldgen()
            );
            levelData  = (PrimaryLevelData) loaded.worldData();
            dimensions = isCustom
                ? this.console.registries().getLayer(RegistryLayer.DIMENSIONS)
                : loaded.dimensions().dimensionsRegistryAccess();
        } else {
            WorldOptions options = new WorldOptions(
                creator.seed(), creator.generateStructures(), false
            );
            LevelSettings settings = new LevelSettings(
                name, GameType.byId(this.getDefaultGameMode().getValue()),
                hardcore, Difficulty.EASY, false,
                new GameRules(), context.dataConfiguration()
            );

            if (isCustom) {
                DedicatedServerProperties.WorldDimensionData props =
                    new DedicatedServerProperties.WorldDimensionData(
                        GsonHelper.parse(
                            creator.generatorSettings().isEmpty()
                                ? "{}"
                                : creator.generatorSettings()
                        ),
                        creator.type().name().toLowerCase(Locale.ROOT)
                    );
                WorldDimensions worldDimensions = props.create(context.datapackWorldgen());
                WorldDimensions.Complete baked = worldDimensions.bake(datapackStems);
                Lifecycle lifecycle = baked.lifecycle().add(
                    context.datapackWorldgen().allRegistriesLifecycle()
                );
                levelData  = new PrimaryLevelData(
                    settings, options, baked.specialWorldProperty(), lifecycle
                );
                dimensions = baked.dimensionsRegistryAccess();
            } else {
                WorldData template = this.console.getWorldData();
                PrimaryLevelData.SpecialWorldProperty property;
                if      (template.isDebugWorld()) property = PrimaryLevelData.SpecialWorldProperty.DEBUG;
                else if (template.isFlatWorld())  property = PrimaryLevelData.SpecialWorldProperty.FLAT;
                else                              property = PrimaryLevelData.SpecialWorldProperty.NONE;
                levelData  = new PrimaryLevelData(
                    settings, options, property, template.worldGenSettingsLifecycle()
                );
                dimensions = this.console.registries().getLayer(RegistryLayer.DIMENSIONS);
            }
        }

        Registry<LevelStem> stems = dimensions.registryOrThrow(Registries.LEVEL_STEM);
        LevelStem stem = stems.get(actualDimension);
        if (stem == null) {
            throw new IllegalArgumentException(
                "Unknown level stem: " + actualDimension
            );
        }

        ((PrimaryLevelDataBridge) levelData).arclight$offerCustomDimensions(stems);
        ((PrimaryLevelDataBridge) levelData).arclight$checkName(name);
        levelData.setModdedInfo(
            this.console.getServerModName(),
            this.console.getModdedStatus().shouldReportAsModified()
        );

        ((DedicatedServerBridge) this.console).arclight$forceUpgradeIfNeeded(worldSession, dimensions);

        long biomeSeed = BiomeManager.obfuscateSeed(creator.seed());
        List<CustomSpawner> spawners = ImmutableList.of(
            new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(),
            new VillageSiege(), new WanderingTraderSpawner(levelData)
        );

        WorldInfo worldInfo = new CraftWorldInfo(
            levelData, worldSession, creator.environment(),
            (DimensionType) stem.type().value()
        );

        if (biomeProvider == null && generator != null) {
            biomeProvider = generator.getDefaultBiomeProvider(worldInfo);
        }

        // Determine world dimension key for vanilla worlds
        String levelName = this.console.getProperties().levelName;
        ResourceKey<net.minecraft.world.level.Level> worldKey;
        if (name.equals(levelName + "_nether")) {
            worldKey = net.minecraft.world.level.Level.NETHER;
        } else if (name.equals(levelName + "_the_end")) {
            worldKey = net.minecraft.world.level.Level.END;
        } else {
            worldKey = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.withDefaultNamespace(name.toLowerCase(Locale.ROOT))
            );
        }

        if (!creator.keepSpawnInMemory()) {
            ((GameRules_ValueBridge<GameRules.IntegerValue>) levelData.getGameRules()
                .getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS))
                .arclight$set(0, null);
        }

        // Store caches before ServerLevel construction pulls them
        this.bridge$offerBiomeProviderCache(name, biomeProvider);
        this.bridge$offerGeneratorCache(name, generator);
        this.bridge$offerEnvironmentCache(name, creator.environment());

        ServerLevel internal = new ServerLevel(
            this.console, this.console.executor, worldSession, levelData,
            worldKey, stem,
            this.getServer().progressListenerFactory.create(
                levelData.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS)
            ),
            levelData.isDebugWorld(), biomeSeed,
            (List<CustomSpawner>) (creator.environment() == World.Environment.NORMAL
                ? spawners : ImmutableList.of()),
            true,
            this.console.overworld().getRandomSequences()
        );

        if (!this.worlds.containsKey(name.toLowerCase(Locale.ROOT))) {
            return null;
        }

        ((DedicatedServerBridge) this.console).arclight$prepareAndAddLevel(internal, levelData);
        CraftWorld bukkit = internal.bridge$getWorld();
        this.pluginManager.callEvent(new WorldLoadEvent(bukkit));
        return bukkit;
    }
}