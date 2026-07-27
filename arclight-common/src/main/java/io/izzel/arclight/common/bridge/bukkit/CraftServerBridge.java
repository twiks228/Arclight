package io.izzel.arclight.common.bridge.bukkit;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * Bridge interface for {@link org.bukkit.craftbukkit.v.CraftServer} that exposes
 * internal server state to Arclight's mixin and compatibility layer.
 */
public interface CraftServerBridge {

    // ── Player list ───────────────────────────────────────────────────────────

    void bridge$setPlayerList(PlayerList playerList);

    // ── World management ──────────────────────────────────────────────────────

    void bridge$removeWorld(ServerLevel world);

    // ── Chunk generator cache ─────────────────────────────────────────────────

    /**
     * Returns and removes the cached {@link ChunkGenerator} for a newly created world.
     * Falls back to {@code bukkit.yml} config if no entry is cached.
     */
    ChunkGenerator bridge$consumeGeneratorCache(String name);

    /** Stores a {@link ChunkGenerator} for use during world creation. */
    void bridge$offerGeneratorCache(String name, ChunkGenerator generator);

    // ── Biome provider cache ──────────────────────────────────────────────────

    /**
     * Returns and removes the cached {@link BiomeProvider} for a newly created world.
     * Falls back to {@code bukkit.yml} config if no entry is cached.
     */
    BiomeProvider bridge$consumeBiomeProviderCache(String name);

    /** Stores a {@link BiomeProvider} for use during world creation. */
    void bridge$offerBiomeProviderCache(String name, BiomeProvider provider);

    // ── Environment cache ─────────────────────────────────────────────────────

    /**
     * Returns and removes the cached {@link World.Environment} for a newly created world.
     */
    World.Environment bridge$consumeEnvironmentCache(String name);

    /** Stores a {@link World.Environment} for use during world creation. */
    void bridge$offerEnvironmentCache(String name, World.Environment environment);

    // ── ViaVersion compatibility ───────────────────────────────────────────────

    /**
     * Returns the real underlying Netty connections list from the NMS server.
     *
     * <p>ViaVersion's {@code LegacyViaInjector} uses reflection to locate the
     * connection list in {@code ServerConnectionListener}. In Arclight/NeoForge,
     * the pipeline structure differs from vanilla Bukkit, causing ViaVersion to
     * receive {@code null} when accessing the field via its legacy reflection path.</p>
     *
     * <p>This method provides a direct reference to the connection list so
     * ViaVersion can correctly inject its {@code ChannelInitializer} into the
     * server's Netty pipeline.</p>
     *
     * @return the active Netty connection list, or {@code null} if unavailable
     */
    List<?> bridge$getConnections();
}