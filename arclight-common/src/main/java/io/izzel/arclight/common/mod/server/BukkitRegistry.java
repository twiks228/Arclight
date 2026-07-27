package io.izzel.arclight.common.mod.server;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.izzel.arclight.api.EnumHelper;
import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.common.bridge.bukkit.world.entity.EntityTypeBridge;
import io.izzel.arclight.common.bridge.bukkit.MaterialBridge;
import io.izzel.arclight.common.bridge.bukkit.SimpleRegistryBridge;
import io.izzel.arclight.common.mod.server.entity.EntityClassLookup;
import io.izzel.arclight.common.mod.util.ResourceLocationUtil;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.EntityPropertySpec;
import io.izzel.arclight.i18n.conf.MaterialPropertySpec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.LevelStem;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.v.CraftStatistic;
import org.bukkit.craftbukkit.v.inventory.CraftRecipe;
import org.bukkit.craftbukkit.v.util.CraftMagicNumbers;
import org.bukkit.craftbukkit.v.util.CraftNamespacedKey;
import org.bukkit.craftbukkit.v.util.CraftSpawnCategory;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pose;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.player.PlayerRecipeBookSettingsChangeEvent;
import org.bukkit.potion.PotionType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Registers all NeoForge/mod-added content into the Bukkit enum registries
 * using {@link EnumHelper} and {@link Unsafe}.
 *
 * <p>This is the core of Arclight's hybrid server registration system.
 * Each {@code load*()} method scans the NMS registry for entries that are
 * not already present in the corresponding Bukkit enum, creates new enum
 * constants via {@link EnumHelper#makeEnum}, and adds them to the enum.</p>
 *
 * <p><b>Ordering matters:</b> Some registrations depend on others
 * (e.g., entities depend on materials). The order in {@link #registerAll}
 * must be maintained.</p>
 */
@SuppressWarnings({"ConstantConditions", "deprecation"})
public final class BukkitRegistry {

    // Utility class — prevent instantiation
    private BukkitRegistry() {}

    // ── Constructor parameter type lists (reused for enum creation) ───────────

    private static final List<Class<?>> MAT_CTOR    = ImmutableList.of(int.class);
    private static final List<Class<?>> ENTITY_CTOR = ImmutableList.of(String.class, Class.class, int.class);
    private static final List<Class<?>> ENV_CTOR    = ImmutableList.of(int.class);

    // ── Unsafe-accessed Bukkit internal maps ──────────────────────────────────

    private static final Map<String, Material>   BY_NAME        = Unsafe.getStatic(Material.class, "BY_NAME");
    private static final Map<Block, Material>    BLOCK_MATERIAL = Unsafe.getStatic(CraftMagicNumbers.class, "BLOCK_MATERIAL");
    private static final Map<Item, Material>     ITEM_MATERIAL  = Unsafe.getStatic(CraftMagicNumbers.class, "ITEM_MATERIAL");
    private static final Map<Material, Item>     MATERIAL_ITEM  = Unsafe.getStatic(CraftMagicNumbers.class, "MATERIAL_ITEM");
    private static final Map<Material, Block>    MATERIAL_BLOCK = Unsafe.getStatic(CraftMagicNumbers.class, "MATERIAL_BLOCK");
    private static final Map<String, EntityType> ENTITY_NAME_MAP  = Unsafe.getStatic(EntityType.class, "NAME_MAP");
    private static final Map<Integer, World.Environment> ENVIRONMENT_MAP = Unsafe.getStatic(World.Environment.class, "lookup");
    private static final Map<String, Art>        ART_BY_NAME    = Unsafe.getStatic(Art.class, "BY_NAME");
    private static final Map<Integer, Art>       ART_BY_ID      = Unsafe.getStatic(Art.class, "BY_ID");
    private static final BiMap<ResourceLocation, Statistic> STATS =
        HashBiMap.create(Unsafe.getStatic(CraftStatistic.class, "statistics"));

    /**
     * Bidirectional map linking NMS dimension {@link ResourceKey}s to
     * Bukkit {@link World.Environment} values.
     * Pre-populated with the three vanilla dimensions.
     */
    static final BiMap<ResourceKey<LevelStem>, World.Environment> DIM_MAP =
        HashBiMap.create(ImmutableMap.<ResourceKey<LevelStem>, World.Environment>builder()
            .put(LevelStem.OVERWORLD, World.Environment.NORMAL)
            .put(LevelStem.NETHER,    World.Environment.NETHER)
            .put(LevelStem.END,       World.Environment.THE_END)
            .build());

    // ── Registration entry point ──────────────────────────────────────────────

    /**
     * Registers all mod-added content into Bukkit's enum registries.
     * Must be called once during server initialization after NMS registries are frozen.
     *
     * @param console the dedicated server instance providing access to dynamic registries
     */
    public static void registerAll(DedicatedServer console) {
        loadMaterials();
        loadPotions();
        loadEnchantmentTargets();
        loadEntities();
        loadBiomes(console);
        loadArts(console);
        loadStats();
        loadSpawnCategory();
        loadEndDragonPhase();
        loadCookingBookCategory();
        loadCraftingBookCategory();
        loadRecipeBookType();
        loadFluids();
        loadGameRules();
        reloadSimpleRegistries();
    }

    /**
     * Reloads all {@link org.bukkit.Registry.SimpleRegistry} instances declared
     * as static fields in {@link org.bukkit.Registry}. This ensures dynamically
     * added entries are visible through the Bukkit Registry API.
     */
    private static void reloadSimpleRegistries() {
        try {
            for (Field field : org.bukkit.Registry.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && field.get(null) instanceof org.bukkit.Registry.SimpleRegistry<?> registry) {
                    ((SimpleRegistryBridge) (Object) registry).bridge$reload();
                }
            }
        } catch (Throwable ignored) {
            // Non-critical: if reload fails, the registry just won't reflect mod entries
        }
    }

    // ── Individual loaders ────────────────────────────────────────────────────

    /**
     * Registers mod-added {@link GameRule}s into Bukkit's game rule registry.
     * Maps NMS game rule types to Boolean/Integer/String Bukkit equivalents.
     */
    private static void loadGameRules() {
        Map<String, GameRule<?>> gameRules;
        Constructor<GameRule> constructor;
        try {
            Field rules = GameRule.class.getDeclaredField("gameRules");
            rules.setAccessible(true);
            //noinspection unchecked
            gameRules = (Map<String, GameRule<?>>) rules.get(null);
            constructor = GameRule.class.getDeclaredConstructor(String.class, Class.class);
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            ArclightServer.LOGGER.warn("Cannot register custom game rules for Bukkit!", e);
            ArclightServer.LOGGER.warn(
                "This is a bug — commands like /gamerule may not work properly. Please report it!"
            );
            return;
        }

        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(
                    GameRules.Key<T> key, GameRules.Type<T> type) {
                if (gameRules.containsKey(key.getId())) return;

                // Map NMS rule value type to a Bukkit-compatible Java type
                Class<?> clazz;
                var argType = type.createRule();
                if (argType instanceof GameRules.BooleanValue) {
                    clazz = Boolean.class;
                } else if (argType instanceof GameRules.IntegerValue) {
                    clazz = Integer.class;
                } else {
                    clazz = String.class;
                }

                try {
                    GameRule<?> instance = constructor.newInstance(key.getId(), clazz);
                    gameRules.put(key.getId(), instance);
                } catch (ReflectiveOperationException e) {
                    ArclightServer.LOGGER.warn(
                        "Cannot register custom game rule {} for Bukkit!", key.getId(), e
                    );
                }
            }
        });
    }

    /**
     * Registers mod-added fluids into Bukkit's {@link org.bukkit.Fluid} enum.
     */
    private static void loadFluids() {
        int id = org.bukkit.Fluid.values().length;
        List<org.bukkit.Fluid> newTypes = new ArrayList<>();

        Field keyField = Arrays.stream(org.bukkit.Fluid.class.getDeclaredFields())
            .filter(f -> f.getName().equals("key"))
            .findAny().orElse(null);
        long keyOffset = Unsafe.objectFieldOffset(keyField);

        for (var fluidType : BuiltInRegistries.FLUID) {
            var key  = BuiltInRegistries.FLUID.getKey(fluidType);
            var name = ResourceLocationUtil.standardize(key);
            try {
                org.bukkit.Fluid.valueOf(name);
            } catch (IllegalArgumentException e) {
                var bukkit = EnumHelper.makeEnum(
                    org.bukkit.Fluid.class, name, id++,
                    List.of(), List.of()
                );
                Unsafe.putObject(bukkit, keyOffset, CraftNamespacedKey.fromMinecraft(key));
                newTypes.add(bukkit);
                ArclightServer.LOGGER.debug("Registered {} as fluid {}", key, bukkit);
            }
        }

        EnumHelper.addEnums(org.bukkit.Fluid.class, newTypes);
    }

    /**
     * Registers mod-added {@link CraftingBookCategory} values into Bukkit's enum.
     */
    private static void loadCraftingBookCategory() {
        int id = org.bukkit.inventory.recipe.CraftingBookCategory.values().length;
        List<org.bukkit.inventory.recipe.CraftingBookCategory> newTypes = new ArrayList<>();

        for (CraftingBookCategory category : CraftingBookCategory.values()) {
            try {
                CraftRecipe.getCategory(category);
            } catch (Exception e) {
                var name = category.name();
                var bukkit = EnumHelper.makeEnum(
                    org.bukkit.inventory.recipe.CraftingBookCategory.class,
                    name, id++, List.of(), List.of()
                );
                newTypes.add(bukkit);
                ArclightServer.LOGGER.debug(
                    "Registered {} as crafting book category {}", name, bukkit
                );
            }
        }

        EnumHelper.addEnums(org.bukkit.inventory.recipe.CraftingBookCategory.class, newTypes);
    }

    /**
     * Registers mod-added {@link CookingBookCategory} values into Bukkit's enum.
     */
    private static void loadCookingBookCategory() {
        int id = org.bukkit.inventory.recipe.CookingBookCategory.values().length;
        List<org.bukkit.inventory.recipe.CookingBookCategory> newTypes = new ArrayList<>();

        for (CookingBookCategory category : CookingBookCategory.values()) {
            try {
                CraftRecipe.getCategory(category);
            } catch (Exception e) {
                var name = category.name();
                var bukkit = EnumHelper.makeEnum(
                    org.bukkit.inventory.recipe.CookingBookCategory.class,
                    name, id++, List.of(), List.of()
                );
                newTypes.add(bukkit);
                ArclightServer.LOGGER.debug(
                    "Registered {} as cooking book category {}", name, bukkit
                );
            }
        }

        EnumHelper.addEnums(org.bukkit.inventory.recipe.CookingBookCategory.class, newTypes);
    }

    /**
     * Registers mod-added {@link RecipeBookType} values.
     * Also validates that no Bukkit recipe book types are missing from NMS.
     */
    private static void loadRecipeBookType() {
        List<String> knownTypes = new ArrayList<>();
        for (PlayerRecipeBookSettingsChangeEvent.RecipeBookType type :
                PlayerRecipeBookSettingsChangeEvent.RecipeBookType.values()) {
            knownTypes.add(type.name());
        }

        List<PlayerRecipeBookSettingsChangeEvent.RecipeBookType> newTypes = new ArrayList<>();
        int id = PlayerRecipeBookSettingsChangeEvent.RecipeBookType.values().length;

        for (RecipeBookType type : RecipeBookType.values()) {
            if (!knownTypes.remove(type.name())) {
                var bukkit = EnumHelper.makeEnum(
                    PlayerRecipeBookSettingsChangeEvent.RecipeBookType.class,
                    type.name(), id++, List.of(), List.of()
                );
                newTypes.add(bukkit);
                ArclightServer.LOGGER.debug(
                    "Registered {} as recipe book type {}", type.name(), bukkit
                );
            }
        }

        // Any remaining entries in knownTypes are in Bukkit but not in NMS — this is a bug
        if (!knownTypes.isEmpty()) {
            ArclightServer.LOGGER.fatal(
                "Assertion failed: unknown recipe book type(s) in Bukkit but not in NMS: {}",
                knownTypes
            );
            throw new IllegalArgumentException(
                "Assertion failed: unknown recipe book types in Bukkit: " + knownTypes
            );
        }

        EnumHelper.addEnums(
            PlayerRecipeBookSettingsChangeEvent.RecipeBookType.class, newTypes
        );
    }

    /**
     * Registers mod-added ender dragon phases into Bukkit's {@link EnderDragon.Phase} enum.
     */
    private static void loadEndDragonPhase() {
        int max = EnderDragonPhase.getCount();
        List<EnderDragon.Phase> newTypes = new ArrayList<>();

        for (int id = EnderDragon.Phase.values().length; id < max; id++) {
            String name = "MOD_PHASE_" + id;
            EnderDragon.Phase newPhase = EnumHelper.makeEnum(
                EnderDragon.Phase.class, name, id, List.of(), List.of()
            );
            newTypes.add(newPhase);
            ArclightServer.LOGGER.debug("Registered {} as ender dragon phase {}", name, newPhase);
        }

        EnumHelper.addEnums(EnderDragon.Phase.class, newTypes);
    }

    /**
     * Registers mod-added mob categories into Bukkit's {@link SpawnCategory} enum.
     */
    private static void loadSpawnCategory() {
        int id = SpawnCategory.values().length;
        List<SpawnCategory> newTypes = new ArrayList<>();

        for (MobCategory category : MobCategory.values()) {
            try {
                CraftSpawnCategory.toBukkit(category);
            } catch (Exception e) {
                String name = category.name();
                SpawnCategory bukkit = EnumHelper.makeEnum(
                    SpawnCategory.class, name, id++, List.of(), List.of()
                );
                newTypes.add(bukkit);
                ArclightServer.LOGGER.debug(
                    "Registered {} as spawn category {}", name, bukkit
                );
            }
        }

        EnumHelper.addEnums(SpawnCategory.class, newTypes);
    }

    /**
     * Registers mod-added statistics into Bukkit's {@link Statistic} enum.
     */
    private static void loadStats() {
        int i = Statistic.values().length;
        List<Statistic> newTypes = new ArrayList<>();

        Field keyField = Arrays.stream(Statistic.class.getDeclaredFields())
            .filter(f -> f.getName().equals("key"))
            .findAny().orElse(null);
        long keyOffset = Unsafe.objectFieldOffset(keyField);

        // Stat types (block/entity/item categories)
        for (StatType<?> statType : BuiltInRegistries.STAT_TYPE) {
            if (statType == Stats.CUSTOM) continue;

            var location  = BuiltInRegistries.STAT_TYPE.getKey(statType);
            var statistic = STATS.get(location);
            if (statistic == null) {
                String standardName = ResourceLocationUtil.standardize(location);

                Statistic.Type type;
                if (statType.getRegistry() == BuiltInRegistries.ENTITY_TYPE) {
                    type = Statistic.Type.ENTITY;
                } else if (statType.getRegistry() == BuiltInRegistries.BLOCK) {
                    type = Statistic.Type.BLOCK;
                } else if (statType.getRegistry() == BuiltInRegistries.ITEM) {
                    type = Statistic.Type.ITEM;
                } else {
                    type = Statistic.Type.UNTYPED;
                }

                statistic = EnumHelper.makeEnum(
                    Statistic.class, standardName, i++,
                    ImmutableList.of(Statistic.Type.class), ImmutableList.of(type)
                );
                Unsafe.putObject(statistic, keyOffset, location);
                newTypes.add(statistic);
                STATS.put(location, statistic);
                ArclightServer.LOGGER.debug("Registered {} as statistic {}", location, statistic);
            }
        }

        // Custom statistics (e.g., minecraft:play_time)
        for (ResourceLocation location : BuiltInRegistries.CUSTOM_STAT) {
            var statistic = STATS.get(location);
            if (statistic == null) {
                String standardName = ResourceLocationUtil.standardize(location);
                statistic = EnumHelper.makeEnum(
                    Statistic.class, standardName, i++,
                    ImmutableList.of(), ImmutableList.of()
                );
                Unsafe.putObject(statistic, keyOffset, location);
                newTypes.add(statistic);
                STATS.put(location, statistic);
                ArclightServer.LOGGER.debug(
                    "Registered {} as custom statistic {}", location, statistic
                );
            }
        }

        EnumHelper.addEnums(Statistic.class, newTypes);
        putStatic(CraftStatistic.class, "statistics", STATS);
    }

    /**
     * Registers mod-added paintings into Bukkit's {@link Art} enum.
     */
    private static void loadArts(DedicatedServer console) {
        int i = Art.values().length;
        List<Art> newTypes = new ArrayList<>();

        Field keyField = Arrays.stream(Art.class.getDeclaredFields())
            .filter(f -> f.getName().equals("key"))
            .findAny().orElse(null);
        long keyOffset = Unsafe.objectFieldOffset(keyField);

        var registry = console.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT);
        for (var paintingType : registry) {
            var location  = registry.getKey(paintingType);
            String lookup = location.getPath().toLowerCase(Locale.ROOT);

            if (Art.getByName(lookup) == null) {
                String standardName = ResourceLocationUtil.standardize(location);
                Art bukkit = EnumHelper.makeEnum(
                    Art.class, standardName, i++,
                    ImmutableList.of(int.class, int.class, int.class),
                    ImmutableList.of(i, paintingType.width(), paintingType.height())
                );
                newTypes.add(bukkit);
                Unsafe.putObject(bukkit, keyOffset, CraftNamespacedKey.fromMinecraft(location));
                ART_BY_ID.put(i - 1, bukkit);
                ART_BY_NAME.put(lookup, bukkit);
                ArclightServer.LOGGER.debug("Registered {} as painting {}", location, bukkit);
            }
        }

        EnumHelper.addEnums(Art.class, newTypes);
    }

    /**
     * Registers mod-added biomes into Bukkit's {@link Biome} enum.
     */
    private static void loadBiomes(DedicatedServer console) {
        int i = Biome.values().length;
        List<Biome> newTypes = new ArrayList<>();

        Field keyField = Arrays.stream(Biome.class.getDeclaredFields())
            .filter(f -> f.getName().equals("key"))
            .findAny().orElse(null);
        long keyOffset = Unsafe.objectFieldOffset(keyField);

        var registry = console.registryAccess().registryOrThrow(Registries.BIOME);
        for (var biome : registry) {
            var location = registry.getKey(biome);
            String name  = ResourceLocationUtil.standardize(location);

            Biome bukkit;
            try {
                bukkit = Biome.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                bukkit = null;
            }

            if (bukkit == null) {
                bukkit = EnumHelper.makeEnum(
                    Biome.class, name, i++, ImmutableList.of(), ImmutableList.of()
                );
                newTypes.add(bukkit);
                Unsafe.putObject(bukkit, keyOffset, CraftNamespacedKey.fromMinecraft(location));
                ArclightServer.LOGGER.debug("Registered {} as biome {}", location, bukkit);
            }
        }

        EnumHelper.addEnums(Biome.class, newTypes);
        ArclightServer.LOGGER.info("registry.biome", newTypes.size());
    }

    /**
     * Registers mod-added dimensions into Bukkit's {@link World.Environment} enum.
     * Called separately after the dimension registry is populated.
     *
     * @param registry the NMS level stem registry
     */
    public static void registerEnvironments(Registry<LevelStem> registry) {
        int i = World.Environment.values().length;
        List<World.Environment> newTypes = new ArrayList<>();

        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : registry.entrySet()) {
            ResourceKey<LevelStem> key = entry.getKey();

            if (DIM_MAP.containsKey(key)) continue; // Already registered (vanilla)

            String name = ResourceLocationUtil.standardize(key.location());
            World.Environment env = EnumHelper.makeEnum(
                World.Environment.class, name, i, ENV_CTOR, ImmutableList.of(i - 1)
            );
            newTypes.add(env);
            ENVIRONMENT_MAP.put(i - 1, env);
            DIM_MAP.put(key, env);
            ArclightServer.LOGGER.debug(
                "Registered {} as environment {}", key.location(), env
            );
            i++;
        }

        EnumHelper.addEnums(World.Environment.class, newTypes);
        ArclightServer.LOGGER.info("registry.environment", newTypes.size());
    }

    /**
     * Registers mod-added entity types into Bukkit's {@link EntityType} enum.
     */
    private static void loadEntities() {
        int i = EntityType.values().length;
        List<EntityType> newTypes = new ArrayList<>();

        for (net.minecraft.world.entity.EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation location = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            EntityType entityType = null;
            boolean found = false;

            if (location.getNamespace().equals(NamespacedKey.MINECRAFT)) {
                entityType = EntityType.fromName(location.getPath());
                if (entityType != null) {
                    found = true;
                    ((EntityTypeBridge) (Object) entityType).bridge$setHandle(type);
                } else {
                    ArclightServer.LOGGER.warn(
                        "Vanilla entity {} not found in Bukkit EntityType enum", location
                    );
                }
            }

            if (!found) {
                String name = ResourceLocationUtil.standardize(location);
                entityType = EnumHelper.makeEnum(
                    EntityType.class, name, i++,
                    ENTITY_CTOR,
                    ImmutableList.of(location.getPath(), Entity.class, -1)
                );
                ((EntityTypeBridge) (Object) entityType).bridge$setup(
                    location, type, entitySpec(location)
                );
                newTypes.add(entityType);
                ArclightServer.LOGGER.debug("Registered {} as entity type {}", location, entityType);
            }

            ENTITY_NAME_MAP.put(location.toString(), entityType);
        }

        EnumHelper.addEnums(EntityType.class, newTypes);
        EntityClassLookup.init();
        ArclightServer.LOGGER.info("registry.entity-type", newTypes.size());
    }

    /**
     * Placeholder for enchantment target registration.
     * TODO: Implement once the Bukkit EnchantmentTarget API is finalized.
     */
    private static void loadEnchantmentTargets() {
        // int origin = EnchantmentTarget.values().length;
        // TODO: Register mod enchantment targets
    }

    /**
     * Registers mod-added potion types into Bukkit's {@link PotionType} enum.
     */
    private static void loadPotions() {
        int typeId = PotionType.values().length;
        List<PotionType> newTypes = new ArrayList<>();

        for (var potion : BuiltInRegistries.POTION) {
            var location = BuiltInRegistries.POTION.getKey(potion);
            String name  = ResourceLocationUtil.standardize(location);
            try {
                PotionType.valueOf(name);
            } catch (IllegalArgumentException e) {
                PotionType potionType = EnumHelper.makeEnum(
                    PotionType.class, name, typeId++,
                    List.of(String.class), List.of(location.toString())
                );
                newTypes.add(potionType);
                ArclightServer.LOGGER.debug(
                    "Registered {} as potion type {}", location, potionType
                );
            }
        }

        EnumHelper.addEnums(PotionType.class, newTypes);
    }

    /**
     * Registers mod-added blocks and items into Bukkit's {@link Material} enum.
     *
     * <p>Processes blocks first (to ensure block-item pairs share the same
     * Material constant), then handles items that have no corresponding block.</p>
     */
    private static void loadMaterials() {
        int blocks = 0, items = 0;
        int i = Material.values().length;
        List<Material> list = new ArrayList<>();

        // ── Phase 1: Register blocks ───────────────────────────────────────────
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation location = BuiltInRegistries.BLOCK.getKey(block);
            String name = ResourceLocationUtil.standardize(location);
            Material material = BY_NAME.get(name);

            if (material == null) {
                material = EnumHelper.makeEnum(
                    Material.class, name, i, MAT_CTOR, ImmutableList.of(i)
                );
                ((MaterialBridge) (Object) material).bridge$setupBlock(location, matSpec(location));
                BY_NAME.put(name, material);
                i++;
                blocks++;
                ArclightServer.LOGGER.debug("Registered {} as block material {}", location, material);
                list.add(material);
            } else {
                ((MaterialBridge) (Object) material).bridge$setupVanillaBlock(matSpec(location));
            }

            BLOCK_MATERIAL.put(block, material);
            MATERIAL_BLOCK.put(material, block);

            // Associate a matching item if one exists (for block-item pairs)
            Item matchingItem = BuiltInRegistries.ITEM.get(location);
            if (matchingItem != null && matchingItem != Items.AIR) {
                ((MaterialBridge) (Object) material).bridge$setItem();
                ITEM_MATERIAL.put(matchingItem, material);
                MATERIAL_ITEM.put(material, matchingItem);
            }
        }

        // ── Phase 2: Register item-only materials (no block counterpart) ───────
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation location = BuiltInRegistries.ITEM.getKey(item);
            String name = ResourceLocationUtil.standardize(location);
            Material material = BY_NAME.get(name);

            if (material == null) {
                material = EnumHelper.makeEnum(
                    Material.class, name, i, MAT_CTOR, ImmutableList.of(i)
                );
                ((MaterialBridge) (Object) material).bridge$setupItem(location, matSpec(location));
                BY_NAME.put(name, material);
                i++;
                items++;
                ArclightServer.LOGGER.debug("Registered {} as item material {}", location, material);
                list.add(material);
            }

            ITEM_MATERIAL.put(item, material);
            MATERIAL_ITEM.put(material, item);

            // Associate a matching block if one exists
            Block matchingBlock = BuiltInRegistries.BLOCK.get(location);
            if (matchingBlock != null && matchingBlock != Blocks.AIR) {
                ((MaterialBridge) (Object) material).bridge$setBlock();
                BLOCK_MATERIAL.put(matchingBlock, material);
                MATERIAL_BLOCK.put(material, matchingBlock);
            }
        }

        EnumHelper.addEnums(Material.class, list);
        ArclightServer.LOGGER.info("registry.material", list.size(), blocks, items);
    }

    // ── Converters and lookup helpers ─────────────────────────────────────────

    /**
     * Converts an NMS {@link net.minecraft.world.entity.Pose} to a Bukkit {@link Pose}.
     * Dynamically registers new pose enum constants if a mod adds new poses.
     *
     * @param nms the NMS pose
     * @return the corresponding Bukkit Pose
     */
    public static Pose toBukkitPose(net.minecraft.world.entity.Pose nms) {
        if (Pose.values().length <= nms.ordinal()) {
            int forgeCount = net.minecraft.world.entity.Pose.values().length;
            List<Pose> newTypes = new ArrayList<>();

            for (int id = Pose.values().length; id < forgeCount; id++) {
                String name  = net.minecraft.world.entity.Pose.values()[id].name();
                Pose newPose = EnumHelper.makeEnum(Pose.class, name, id, List.of(), List.of());
                newTypes.add(newPose);
                ArclightServer.LOGGER.debug("Registered {} as pose {}", name, newPose);
            }

            EnumHelper.addEnums(Pose.class, newTypes);
        }

        return Pose.values()[nms.ordinal()];
    }

    private static MaterialPropertySpec matSpec(ResourceLocation location) {
        return ArclightConfig.spec().getCompat()
            .getMaterial(location.toString())
            .orElse(MaterialPropertySpec.EMPTY);
    }

    private static EntityPropertySpec entitySpec(ResourceLocation location) {
        return ArclightConfig.spec().getCompat()
            .getEntity(location.toString())
            .orElse(EntityPropertySpec.EMPTY);
    }

    // ── Unsafe field writers ──────────────────────────────────────────────────

    /**
     * Writes an object value to a static field using {@link Unsafe}.
     * Used to replace internal Bukkit maps with our extended versions.
     */
    private static void putStatic(Class<?> cl, String name, Object value) {
        try {
            Unsafe.ensureClassInitialized(cl);
            Field field = cl.getDeclaredField(name);
            Object base = Unsafe.staticFieldBase(field);
            long offset = Unsafe.staticFieldOffset(field);
            Unsafe.putObject(base, offset, value);
        } catch (ReflectiveOperationException e) {
            ArclightServer.LOGGER.error(
                "Failed to write static field {}.{}", cl.getSimpleName(), name, e
            );
        }
    }

    /**
     * Writes a boolean value to a static field using {@link Unsafe}.
     */
    @SuppressWarnings("unused")
    private static void putBool(Class<?> cl, String name, boolean value) {
        try {
            Unsafe.ensureClassInitialized(cl);
            Field field = cl.getDeclaredField(name);
            Object base = Unsafe.staticFieldBase(field);
            long offset = Unsafe.staticFieldOffset(field);
            Unsafe.putBoolean(base, offset, value);
        } catch (ReflectiveOperationException e) {
            ArclightServer.LOGGER.error(
                "Failed to write static boolean field {}.{}", cl.getSimpleName(), name, e
            );
        }
    }
}