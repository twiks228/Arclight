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
import org.bukkit.command.CommandSender;

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

public class ArclightServer {

    public static final Logger LOGGER = ArclightI18nLogger.getLogger("Arclight");

    private interface ExecutorWithThread extends Executor, Supplier<Thread> {
    }

    private static final ExecutorWithThread mainThreadExecutor = new ExecutorWithThread() {
        @Override
        public void execute(@NotNull Runnable command) {
            executeOnMainThread(command);
        }

        @Override
        public Thread get() {
            return getMinecraftServer().getRunningThread();
        }
    };
    private static final ExecutorService chatExecutor = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("Async Chat Thread - #%d")
            .setThreadFactory(chatFactory()).build());

    private static ThreadFactory chatFactory() {
        var group = Thread.currentThread().getThreadGroup();
        var classLoader = Thread.currentThread().getContextClassLoader();
        return r -> {
            var thread = new Thread(group, r);
            thread.setContextClassLoader(classLoader);
            return thread;
        };
    }

    private static CraftServer server;

    public static Set<String> iterateDepends(PluginDescriptionFile desc) {
        SimplePluginManager manager = (SimplePluginManager) Bukkit.getPluginManager();
        Graph<String> dependencyGraph = ((SimplePluginManagerAccessor)(Object) manager).arclight$dependencyGraph();
        if (dependencyGraph.nodes().contains(desc.getName())) {
            return ArclightCommon.api().guavaReachableNodes(dependencyGraph, desc.getName());
        } else {
            return Collections.emptySet();
        }
    }

    @SuppressWarnings("ConstantConditions")
    public static CraftServer createOrLoad(DedicatedServer console, PlayerList playerList) {
        if (server == null) {
            try {
                server = new CraftServer(console, playerList);
                ((MinecraftServerBridge) console).bridge$setServer(server);
                ((MinecraftServerBridge) console).bridge$setConsole(ColouredConsoleSender.getInstance());

                Class.forName("org.sqlite.JDBC");
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (Throwable t) {
                throw new RuntimeException("Error initializing Arclight", t);
            }
            try {
                LOGGER.info("registry.begin");
                BukkitRegistry.registerAll(console);
                org.spigotmc.SpigotConfig.init(new File("./spigot.yml"));
                org.spigotmc.SpigotConfig.registerCommands();
                if (VelocitySupport.isEnabled()) {
                    SpigotConfig.bungee = true;
                }
                // Register Arclight J2K commands
                registerJ2KCommands();
            } catch (Throwable t) {
                LOGGER.error("registry.error", t);
                throw t;
            }
        } else {
            ((CraftServerBridge) (Object) server).bridge$setPlayerList(playerList);
        }
        return server;
    }

    public static boolean isInitialized() { return server != null; }

    public static CraftServer get() {
        return Objects.requireNonNull(server);
    }

    public static boolean isPrimaryThread() {
        if (server == null) {
            return Thread.currentThread().equals(getMinecraftServer().getRunningThread());
        } else {
            return server.isPrimaryThread();
        }
    }

    private static MinecraftServer vanillaServer;

    public static void setMinecraftServer(MinecraftServer server) {
        vanillaServer = server;
    }

    public static MinecraftServer getMinecraftServer() {
        return Objects.requireNonNull(vanillaServer, "vanillaServer");
    }

    public static void executeOnMainThread(Runnable runnable) {
        ((MinecraftServerBridge) getMinecraftServer()).bridge$queuedProcess(runnable);
        if (LockSupport.getBlocker(getMinecraftServer().getRunningThread()) == "waiting for tasks") {
            LockSupport.unpark(getMinecraftServer().getRunningThread());
        }
    }

    public static Executor getMainThreadExecutor() {
        return mainThreadExecutor;
    }

    public static ExecutorService getChatExecutor() {
        return chatExecutor;
    }

    public static World.Environment getEnvironment(ResourceKey<LevelStem> key) {
        return BukkitRegistry.DIM_MAP.getOrDefault(key, World.Environment.CUSTOM);
    }
    // Registers Arclight J2K built-in commands
private static void registerJ2KCommands() {
    try {
        var j2kCommand = new io.izzel.arclight.common.mod.command.J2KCommand();
        var craftServer = (org.bukkit.craftbukkit.v.CraftServer) server;
        var commandMap = craftServer.getCommandMap();

        // Use a simple FallbackCommand wrapper
        commandMap.register("j2k", "arclight", new org.bukkit.command.Command("j2k") {
            {
                setDescription("Arclight J2K diagnostics and status command");
                setUsage("/j2k <status|version|scan|memory|help>");
                setPermission("arclight.command.j2k");
            }

            @Override
            public boolean execute(org.bukkit.command.CommandSender sender,
                                   String commandLabel, String[] args) {
                if (!sender.hasPermission("arclight.command.j2k")
                        && !sender.isOp()) {
                    sender.sendMessage("\u00a7cYou don't have permission to use this command.");
                    return true;
                }
                return j2kCommand.onCommand(sender, this, commandLabel, args);
            }

            @Override
            public java.util.List<String> tabComplete(
                    org.bukkit.command.CommandSender sender,
                    String alias, String[] args) {
                var result = j2kCommand.onTabComplete(sender, this, alias, args);
                return result != null ? result : java.util.List.of();
            }
        });

        LOGGER.info("[Arclight-J2K] Registered /j2k command");
    } catch (Exception e) {
        LOGGER.warn("[Arclight-J2K] Failed to register /j2k command: {}", e.getMessage());
    }
}
}
