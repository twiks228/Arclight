package io.izzel.arclight.common.mod.server;

import com.google.common.graph.Graph;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.izzel.arclight.common.bridge.bukkit.CraftServerBridge;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.mixin.bukkit.plugin.SimplePluginManagerAccessor;
import io.izzel.arclight.common.mod.ArclightCommon;
import io.izzel.arclight.common.mod.util.VelocitySupport;
import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.dimension.LevelStem;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.command.ColouredConsoleSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.SimplePluginManager;
import org.jetbrains.annotations.NotNull;
import org.spigotmc.SpigotConfig;

import java.io.File;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Central singleton for the Arclight J2K server layer.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Initializing and holding the {@link CraftServer} instance</li>
 *   <li>Providing access to the main thread executor and async chat executor</li>
 *   <li>Registering J2K built-in commands (/j2k)</li>
 *   <li>Bridging Bukkit plugin dependency graphs with NeoForge mods</li>
 * </ul>
 */
public final class ArclightServer {

    // Utility class — prevent instantiation
    private ArclightServer() {}

    public static final Logger LOGGER = ArclightI18nLogger.getLogger("Arclight");

    // ── Executor interfaces ───────────────────────────────────────────────────

    /**
     * Combined interface for an executor that also exposes the thread it runs on.
     * Used to allow callers to check {@code thread == executor.get()} for
     * same-thread detection without an extra field.
     */
    private interface ExecutorWithThread extends Executor, Supplier<Thread> {}

    // ── Main thread executor ──────────────────────────────────────────────────

    private static final ExecutorWithThread MAIN_THREAD_EXECUTOR = new ExecutorWithThread() {
        @Override
        public void execute(@NotNull Runnable command) {
            executeOnMainThread(command);
        }

        @Override
        public Thread get() {
            return getMinecraftServer().getRunningThread();
        }
    };

    // ── Async chat executor ───────────────────────────────────────────────────

    private static final ExecutorService CHAT_EXECUTOR = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("Async Chat Thread - #%d")
            .setThreadFactory(buildChatFactory())
            .build()
    );

    /**
     * Creates a thread factory that inherits the context class loader from
     * the initializing thread (required for plugin class loading in async chat).
     */
    private static ThreadFactory buildChatFactory() {
        var group = Thread.currentThread().getThreadGroup();
        var classLoader = Thread.currentThread().getContextClassLoader();
        return r -> {
            var thread = new Thread(group, r);
            thread.setContextClassLoader(classLoader);
            return thread;
        };
    }

    // ── Server state ──────────────────────────────────────────────────────────

    private static volatile CraftServer server;
    private static volatile MinecraftServer vanillaServer;

    // ── Plugin dependency graph ───────────────────────────────────────────────

    /**
     * Returns the transitive closure of dependencies for the given plugin.
     * Used to determine which mods/plugins a plugin depends on (directly or transitively).
     *
     * @param desc the plugin description whose dependencies to resolve
     * @return an immutable set of all reachable dependency names
     */
    public static Set<String> iterateDepends(PluginDescriptionFile desc) {
        SimplePluginManager manager =
            (SimplePluginManager) Bukkit.getPluginManager();
        Graph<String> dependencyGraph =
            ((SimplePluginManagerAccessor) (Object) manager).arclight$dependencyGraph();

        if (dependencyGraph.nodes().contains(desc.getName())) {
            return ArclightCommon.api().guavaReachableNodes(dependencyGraph, desc.getName());
        }
        return Collections.emptySet();
    }

    // ── Server initialization ─────────────────────────────────────────────────

    /**
     * Creates or reloads the {@link CraftServer} instance.
     *
     * <p>On first call, initializes the server, loads registries, and registers
     * built-in commands. On subsequent calls (e.g., after /reload), only the
     * player list reference is updated.</p>
     *
     * @param console    the dedicated NMS server instance
     * @param playerList the current player list
     * @return the {@link CraftServer} singleton
     */
    @SuppressWarnings("ConstantConditions")
    public static CraftServer createOrLoad(DedicatedServer console, PlayerList playerList) {
        if (server == null) {
            // ── First-time initialization ──────────────────────────────────────
            try {
                server = new CraftServer(console, playerList);
                ((MinecraftServerBridge) console).bridge$setServer(server);
                ((MinecraftServerBridge) console).bridge$setConsole(
                    ColouredConsoleSender.getInstance()
                );

                // Load JDBC drivers for database plugin support
                Class.forName("org.sqlite.JDBC");
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (Throwable t) {
                throw new RuntimeException("Error initializing Arclight CraftServer", t);
            }

            try {
                LOGGER.info("registry.begin");
                BukkitRegistry.registerAll(console);

                SpigotConfig.init(new File("./spigot.yml"));
                SpigotConfig.registerCommands();

                // When Velocity is enabled, treat the server as BungeeCord-capable
                // so IP forwarding and hostname spoofing are accepted
                if (VelocitySupport.isEnabled()) {
                    SpigotConfig.bungee = true;
                }

                registerJ2KCommands();
            } catch (Throwable t) {
                LOGGER.error("registry.error", t);
                throw t;
            }
        } else {
            // ── Reload: only update the player list ────────────────────────────
            ((CraftServerBridge) (Object) server).bridge$setPlayerList(playerList);
        }

        return server;
    }

    /** Returns {@code true} if the {@link CraftServer} has been initialized. */
    public static boolean isInitialized() {
        return server != null;
    }

    /**
     * Returns the {@link CraftServer} singleton.
     *
     * @throws NullPointerException if called before {@link #createOrLoad}
     */
    public static CraftServer get() {
        return Objects.requireNonNull(server, "CraftServer not yet initialized");
    }

    // ── Thread utilities ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the current thread is the main server thread.
     * Safe to call before {@link CraftServer} is fully initialized.
     */
    public static boolean isPrimaryThread() {
        if (server == null) {
            return Thread.currentThread().equals(getMinecraftServer().getRunningThread());
        }
        return server.isPrimaryThread();
    }

    public static void setMinecraftServer(MinecraftServer server) {
        vanillaServer = server;
    }

    /**
     * Returns the NMS {@link MinecraftServer} instance.
     *
     * @throws NullPointerException if not yet set
     */
    public static MinecraftServer getMinecraftServer() {
        return Objects.requireNonNull(vanillaServer, "vanillaServer not yet set");
    }

    /**
     * Enqueues a runnable for execution on the main server thread.
     * If the main thread is currently parked, it is unparked immediately.
     *
     * @param runnable the task to run on the main thread
     */
    public static void executeOnMainThread(Runnable runnable) {
        ((MinecraftServerBridge) getMinecraftServer()).bridge$queuedProcess(runnable);

        Thread mainThread = getMinecraftServer().getRunningThread();
        // Unpark if the main thread is parked waiting for tasks
        if (LockSupport.getBlocker(mainThread) == "waiting for tasks") {
            LockSupport.unpark(mainThread);
        }
    }

    public static Executor getMainThreadExecutor() {
        return MAIN_THREAD_EXECUTOR;
    }

    public static ExecutorService getChatExecutor() {
        return CHAT_EXECUTOR;
    }

    // ── Dimension environment ─────────────────────────────────────────────────

    /**
     * Returns the Bukkit {@link World.Environment} for the given dimension key.
     * Falls back to {@link World.Environment#CUSTOM} for mod-added dimensions.
     *
     * @param key the NMS dimension resource key
     * @return the corresponding Bukkit environment
     */
    public static World.Environment getEnvironment(ResourceKey<LevelStem> key) {
        return BukkitRegistry.DIM_MAP.getOrDefault(key, World.Environment.CUSTOM);
    }

    // ── J2K command registration ──────────────────────────────────────────────

    /**
     * Registers the built-in {@code /j2k} command with the Bukkit command map.
     *
     * <p>Uses an anonymous {@link org.bukkit.command.Command} wrapper to bridge
     * the {@link io.izzel.arclight.common.mod.command.J2KCommand} executor
     * into the Bukkit command system without requiring a plugin.yml entry.</p>
     */
    private static void registerJ2KCommands() {
        try {
            var j2kCommand = new io.izzel.arclight.common.mod.command.J2KCommand();
            var commandMap = ((org.bukkit.craftbukkit.v.CraftServer) server).getCommandMap();

            commandMap.register("j2k", "arclight", new org.bukkit.command.Command("j2k") {
                {
                    setDescription("Arclight J2K diagnostics and status command");
                    setUsage("/j2k <status|version|scan|memory|help>");
                    setPermission("arclight.command.j2k");
                }

                @Override
                public boolean execute(
                        org.bukkit.command.CommandSender sender,
                        String commandLabel,
                        String[] args
                ) {
                    // Allow OPs without explicit permission node for convenience
                    if (!sender.hasPermission("arclight.command.j2k") && !sender.isOp()) {
                        sender.sendMessage("\u00a7cYou don't have permission to use this command.");
                        return true;
                    }
                    return j2kCommand.onCommand(sender, this, commandLabel, args);
                }

                @Override
                public java.util.List<String> tabComplete(
                        org.bukkit.command.CommandSender sender,
                        String alias,
                        String[] args
                ) {
                    var result = j2kCommand.onTabComplete(sender, this, alias, args);
                    return result != null ? result : java.util.List.of();
                }
            });

            LOGGER.info("[Arclight-J2K] Registered /j2k command successfully");
        } catch (Exception e) {
            LOGGER.warn("[Arclight-J2K] Failed to register /j2k command: {}", e.getMessage());
        }
    }
}