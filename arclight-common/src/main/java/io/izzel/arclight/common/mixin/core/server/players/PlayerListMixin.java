package io.izzel.arclight.common.mixin.core.server.players;

import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.network.ConnectionBridge;
import io.izzel.arclight.common.bridge.core.network.syncher.SynchedEntityDataBridge;
import io.izzel.arclight.common.bridge.core.server.network.ServerGamePacketListenerImplBridge;
import io.izzel.arclight.common.bridge.core.server.players.PlayerListBridge;
import io.izzel.arclight.common.bridge.core.world.level.WorldBridge;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.mod.server.world.border.ArclightBorderChangeListener;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import io.izzel.arclight.common.mod.util.Blackhole;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import io.izzel.arclight.mixin.Eject;
import io.izzel.arclight.mixin.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.IpBanList;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.bukkit.craftbukkit.v.entity.CraftPlayer;
import org.bukkit.craftbukkit.v.util.CraftChatMessage;
import org.bukkit.craftbukkit.v.util.CraftLocation;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSpawnChangeEvent;
import org.spigotmc.SpigotConfig;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core mixin for {@link PlayerList} that integrates Bukkit's player login,
 * join, quit, and respawn event pipelines into the NMS player management system.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Server initialization via {@link ArclightServer#createOrLoad}</li>
 *   <li>Spawn location customization via {@link PlayerSpawnLocationEvent}</li>
 *   <li>Login validation and Bukkit event dispatch ({@link PlayerLoginEvent})</li>
 *   <li>Join message customization via {@link PlayerJoinEvent}</li>
 *   <li>Quit message customization via {@link PlayerQuitEvent}</li>
 *   <li>Respawn logic with full Bukkit event integration</li>
 *   <li>Per-world view and simulation distance from Spigot config</li>
 *   <li>World border listener replacement with Arclight's custom listener</li>
 * </ul>
 */
@Mixin(value = PlayerList.class, priority = 1100)
public abstract class PlayerListMixin implements PlayerListBridge {

    // @formatter:off
    @Override @Accessor("players") @Mutable
    public abstract void bridge$setPlayers(List<ServerPlayer> players);

    @Override @Accessor("players")
    public abstract List<ServerPlayer> bridge$getPlayers();

    @Shadow @Final public PlayerDataStorage playerIo;
    @Shadow @Final private UserBanList bans;
    @Shadow @Final private static SimpleDateFormat BAN_DATE_FORMAT;
    @Shadow public abstract boolean isWhiteListed(GameProfile profile);
    @Shadow @Final private IpBanList ipBans;
    @Shadow @Final public List<ServerPlayer> players;
    @Shadow public int maxPlayers;
    @Shadow public abstract boolean canBypassPlayerLimit(GameProfile profile);
    @Shadow protected abstract void save(ServerPlayer player);
    @Shadow @Final private MinecraftServer server;
    @Shadow public abstract UserBanList getBans();
    @Shadow public abstract IpBanList getIpBans();
    @Shadow public abstract void sendLevelInfo(ServerPlayer player, ServerLevel world);
    @Shadow public abstract void sendPlayerPermissionLevel(ServerPlayer player);
    @Shadow @Final private Map<UUID, ServerPlayer> playersByUUID;
    @Shadow public abstract void sendAllPlayerInfo(ServerPlayer player);
    @Shadow @Nullable public abstract ServerPlayer getPlayer(UUID uuid);
    @Shadow public abstract void broadcastSystemMessage(Component component, boolean flag);
    @Shadow public abstract void sendActivePlayerEffects(ServerPlayer player);
    @Shadow public abstract ServerPlayer respawn(
            ServerPlayer player, boolean flag, Entity.RemovalReason reason);
    // @formatter:on

    // ── Bukkit state ──────────────────────────────────────────────────────────

    private CraftServer cserver;

    @Override
    public CraftServer bridge$getCraftServer() {
        return cserver;
    }

    // ── Transient respawn state ────────────────────────────────────────────────

    /** Custom respawn location set before calling the vanilla respawn method. */
    @Unique private transient Location arclight$loc;

    /**
     * Pending respawn reason, consumed by {@link #arclight$respawnPoint}.
     * Reset to {@code null} after use in {@link #arclight$postRespawn}.
     */
    @Unique private transient PlayerRespawnEvent.RespawnReason arclight$respawnReason;

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Initializes the Bukkit server layer on the first player list construction.
     * This is the entry point for {@link ArclightServer#createOrLoad}.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$loadServer(
            MinecraftServer minecraftServer,
            LayeredRegistryAccess<RegistryLayer> registryAccess,
            PlayerDataStorage playerDataStorage,
            int maxPlayers,
            CallbackInfo ci
    ) {
        cserver = ArclightServer.createOrLoad(
            (DedicatedServer) minecraftServer, (PlayerList) (Object) this
        );
    }

    // ── Spawn location ────────────────────────────────────────────────────────

    /**
     * Fires {@link PlayerSpawnLocationEvent} to allow plugins to override
     * the world and position where a new player first spawns.
     * Also applies the chosen location to the player before returning the world.
     */
    @Redirect(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getLevel(" +
                     "Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"
        )
    )
    private ServerLevel arclight$spawnLocationEvent(
            MinecraftServer server, ResourceKey<Level> dimension,
            Connection connection, ServerPlayer player) {
        CraftPlayer craftPlayer = ((ServerPlayerBridge) player).bridge$getBukkitEntity();
        PlayerSpawnLocationEvent event = new PlayerSpawnLocationEvent(
            craftPlayer, craftPlayer.getLocation()
        );
        cserver.getPluginManager().callEvent(event);
        Location loc = event.getSpawnLocation();
        ServerLevel world = ((CraftWorld) loc.getWorld()).getHandle();
        player.setServerLevel(world);
        player.absMoveTo(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        return world;
    }

    // ── Per-world view distance ────────────────────────────────────────────────

    /** Uses the Spigot per-world view distance instead of the global field. */
    @Redirect(
        method = "placeNewPlayer",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/players/PlayerList;viewDistance:I"
        )
    )
    private int arclight$spigotViewDistance(PlayerList playerList, Connection connection, ServerPlayer player) {
        return ((WorldBridge) player.serverLevel()).bridge$spigotConfig().viewDistance;
    }

    /** Uses the Spigot per-world simulation distance instead of the global field. */
    @Redirect(
        method = "placeNewPlayer",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/players/PlayerList;simulationDistance:I"
        )
    )
    private int arclight$spigotSimDistance(PlayerList playerList, Connection connection, ServerPlayer player) {
        return ((WorldBridge) player.serverLevel()).bridge$spigotConfig().simulationDistance;
    }

    // ── Join event ────────────────────────────────────────────────────────────

    /**
     * Fires {@link PlayerJoinEvent} and handles the join message broadcast.
     *
     * <p>The player is temporarily added to the player list before the event
     * so that plugins can interact with them, then removed and re-added only
     * if the connection is still active after the event.</p>
     */
    @Eject(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(" +
                     "Lnet/minecraft/network/chat/Component;Z)V"
        )
    )
    private void arclight$playerJoin(
            PlayerList playerList,
            Component component,
            boolean flag,
            CallbackInfo ci,
            Connection connection,
            ServerPlayer player
    ) {
        CraftPlayer craftPlayer = ((ServerPlayerBridge) player).bridge$getBukkitEntity();
        PlayerJoinEvent joinEvent = new PlayerJoinEvent(
            craftPlayer, CraftChatMessage.fromComponent(component)
        );

        // Temporarily add to list so plugins can query online players during the event
        this.players.add(player);
        this.playersByUUID.put(player.getUUID(), player);
        this.cserver.getPluginManager().callEvent(joinEvent);
        this.players.remove(player);

        // If the connection was closed during event handling, abort
        if (!player.connection.isAcceptingMessages()) {
            ci.cancel();
            return;
        }

        String joinMessage = joinEvent.getJoinMessage();
        if (joinMessage != null && !joinMessage.isEmpty()) {
            for (Component line : CraftChatMessage.fromString(joinMessage)) {
                this.server.getPlayerList().broadcastSystemMessage(line, flag);
            }
        }
    }

    /**
     * Guards {@code addNewPlayer} to prevent adding a player to a level they
     * are not actually in, or adding them twice.
     */
    @Redirect(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;addNewPlayer(Lnet/minecraft/server/level/ServerPlayer;)V"
        )
    )
    private void arclight$addNewPlayer(ServerLevel level, ServerPlayer player) {
        if (player.level() == level && !level.players().contains(player)) {
            level.addNewPlayer(player);
        }
    }

    /**
     * Re-reads the player's current server level after {@code addNewPlayer}
     * in case a plugin event changed the world during join.
     */
    @ModifyVariable(
        method = "placeNewPlayer",
        ordinal = 1,
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/server/level/ServerLevel;addNewPlayer(Lnet/minecraft/server/level/ServerPlayer;)V"
        )
    )
    private ServerLevel arclight$handleWorldChanges(
            ServerLevel value, Connection connection, ServerPlayer player) {
        return player.serverLevel();
    }

    // ── World border ──────────────────────────────────────────────────────────

    /**
     * Replaces the vanilla border change listener with Arclight's custom
     * {@link ArclightBorderChangeListener} which supports per-world borders.
     */
    @Decorate(
        method = "addWorldborderListener",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/border/WorldBorder;addListener(" +
                     "Lnet/minecraft/world/level/border/BorderChangeListener;)V"
        )
    )
    private void arclight$useCustomListener(WorldBorder border, BorderChangeListener listener) throws Throwable {
        DecorationOps.callsite().invoke(border, ArclightBorderChangeListener.typed());
    }

    // ── Player save guard ─────────────────────────────────────────────────────

    /**
     * Skips saving player data for non-persistent players (e.g., NPC entities
     * or temporary players created for testing purposes).
     */
    @Inject(method = "save", cancellable = true, at = @At("HEAD"))
    private void arclight$returnIfNotPersist(ServerPlayer player, CallbackInfo ci) {
        if (!((ServerPlayerBridge) player).bridge$isPersist()) {
            ci.cancel();
        }
    }

    // ── Quit event ────────────────────────────────────────────────────────────

    /**
     * Fires {@link PlayerQuitEvent} before the player is saved and removed.
     * Also closes any open inventory and removes the player from the scoreboard.
     */
    @Inject(
        method = "remove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;save(Lnet/minecraft/server/level/ServerPlayer;)V"
        )
    )
    private void arclight$playerQuitPre(ServerPlayer player, CallbackInfo ci) {
        // Close any open inventory to prevent item duplication
        if (player.inventoryMenu != player.containerMenu) {
            ((ServerPlayerBridge) player).bridge$getBukkitEntity().closeInventory();
        }

        String capturedQuit = ArclightCaptures.getQuitMessage();
        String defaultQuit = "\u00A7e" + player.getScoreboardName() + " left the game";
        PlayerQuitEvent quitEvent = new PlayerQuitEvent(
            ((ServerPlayerBridge) player).bridge$getBukkitEntity(),
            capturedQuit != null ? capturedQuit : defaultQuit
        );

        cserver.getPluginManager().callEvent(quitEvent);
        ((ServerPlayerBridge) player).bridge$getBukkitEntity().disconnect(
            quitEvent.getQuitMessage()
        );

        ArclightCaptures.captureQuitMessage(quitEvent.getQuitMessage());
        cserver.getScoreboardManager().removePlayer(
            ((ServerPlayerBridge) player).bridge$getBukkitEntity()
        );
    }

    // ── World border per-level ────────────────────────────────────────────────

    /**
     * Returns the world border of the destination level rather than always
     * returning the Overworld border. This allows custom dimensions to have
     * their own independent world borders.
     */
    @Decorate(
        method = "sendLevelInfo",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getWorldBorder()" +
                     "Lnet/minecraft/world/level/border/WorldBorder;"
        )
    )
    private WorldBorder arclight$useRespectiveWorldBorder(
            ServerLevel overworld, ServerPlayer player, ServerLevel destination) throws Throwable {
        return (WorldBorder) DecorationOps.callsite().invoke(destination);
    }

    // ── Login validation ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Creates a new {@link ServerPlayer} entity for the logging-in player,
     * validates their login against bans, whitelist, IP bans, and server capacity,
     * then fires {@link PlayerLoginEvent} to allow plugins to allow/deny the login.</p>
     *
     * <p>Returns {@code null} if login should be denied (the handler will be
     * disconnected before returning).</p>
     */
    @Override
    public ServerPlayer bridge$canPlayerLogin(
            SocketAddress socketAddress,
            GameProfile gameProfile,
            ServerLoginPacketListenerImpl handler
    ) {
        UUID uuid = gameProfile.getId();

        // Kick any existing players with the same UUID (duplicate login)
        List<ServerPlayer> duplicates = Lists.newArrayList();
        for (ServerPlayer existing : this.players) {
            if (existing.getUUID().equals(uuid)) {
                duplicates.add(existing);
            }
        }
        for (ServerPlayer duplicate : duplicates) {
            this.save(duplicate);
            duplicate.connection.disconnect(
                Component.translatable("multiplayer.disconnect.duplicate_login")
            );
        }

        // Create the new player entity in the Overworld
        ServerPlayer entity = new ServerPlayer(
            this.server,
            this.server.getLevel(Level.OVERWORLD),
            gameProfile,
            ClientInformation.createDefault()
        );
        ((ServerPlayerBridge) entity).bridge$setTransferCookieConnection(
            (CraftPlayer.TransferCookieConnection) handler
        );

        Player player = ((ServerPlayerBridge) entity).bridge$getBukkitEntity();

        // Resolve the hostname and real IP address from the handler
        String hostname = (handler != null)
            ? ((ConnectionBridge) handler.connection).bridge$getHostname()
            : "";
        InetAddress realAddress = (handler != null)
            ? ((InetSocketAddress) handler.connection.channel.remoteAddress()).getAddress()
            : ((InetSocketAddress) socketAddress).getAddress();

        PlayerLoginEvent event = new PlayerLoginEvent(
            player, hostname,
            ((InetSocketAddress) socketAddress).getAddress(),
            realAddress
        );

        // Check ban list
        if (this.getBans().isBanned(gameProfile)) {
    UserBanListEntry entry = this.bans.get(gameProfile);
    if (entry != null && !entry.hasExpired()) {
        // MutableComponent — у него есть метод append()
        MutableComponent message = Component.translatable(
            "multiplayer.disconnect.banned.reason", entry.getReason()
        );
        if (entry.getExpires() != null) {
            message.append(Component.translatable(
                "multiplayer.disconnect.banned.expiration",
                BAN_DATE_FORMAT.format(entry.getExpires())
            ));
        }
        event.disallow(
            PlayerLoginEvent.Result.KICK_BANNED,
            CraftChatMessage.fromComponent(message)
        );
    }
}


        // Check whitelist
        if (!event.getResult().equals(PlayerLoginEvent.Result.ALLOWED)
                || !this.isWhiteListed(gameProfile)) {
            if (event.getResult().equals(PlayerLoginEvent.Result.ALLOWED)) {
                event.disallow(
                    PlayerLoginEvent.Result.KICK_WHITELIST, SpigotConfig.whitelistMessage
                );
            }
        }

        // Check IP ban list
      if (!event.getResult().equals(PlayerLoginEvent.Result.ALLOWED)
        && this.getIpBans().isBanned(socketAddress)) {
    IpBanListEntry entry = this.ipBans.get(socketAddress);
    if (entry != null && !entry.hasExpired()) {
        // MutableComponent — у него есть метод append()
        MutableComponent message = Component.translatable(
            "multiplayer.disconnect.banned_ip.reason", entry.getReason()
        );
        if (entry.getExpires() != null) {
            message.append(Component.translatable(
                "multiplayer.disconnect.banned_ip.expiration",
                BAN_DATE_FORMAT.format(entry.getExpires())
            ));
        }
        event.disallow(
            PlayerLoginEvent.Result.KICK_BANNED,
            CraftChatMessage.fromComponent(message)
        );
    }
}

        // Check server capacity
        if (!event.getResult().equals(PlayerLoginEvent.Result.ALLOWED)
                && this.players.size() >= this.maxPlayers
                && !this.canBypassPlayerLimit(gameProfile)) {
            event.disallow(
                PlayerLoginEvent.Result.KICK_FULL, SpigotConfig.serverFullMessage
            );
        }

        this.cserver.getPluginManager().callEvent(event);

        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            if (handler != null) {
                handler.disconnect(
                    CraftChatMessage.fromStringOrNull(event.getKickMessage())
                );
            }
            return null;
        }

        return entity;
    }

    // ── Respawn overloads ─────────────────────────────────────────────────────

    /**
     * Respawns a player with the given reason and no custom location.
     */
    public ServerPlayer respawn(
            ServerPlayer player,
            boolean flag,
            Entity.RemovalReason reason,
            PlayerRespawnEvent.RespawnReason respawnReason
    ) {
        return this.respawn(player, flag, reason, respawnReason, null);
    }

    /**
     * Respawns a player with an optional custom location and respawn reason.
     *
     * <p>If {@code location} is provided, the player is placed at that location.
     * Otherwise, the vanilla respawn position logic is used (bed, spawn anchor,
     * or world spawn).</p>
     *
     * @param player        the player to respawn
     * @param flag          whether this is an end-portal respawn (keeps inventory in hardcore)
     * @param removalReason the reason the player entity was removed
     * @param respawnReason the Bukkit reason for the respawn event
     * @param location      a custom respawn location, or {@code null} for vanilla logic
     * @return the respawned player entity
     */
    public ServerPlayer respawn(
            ServerPlayer player,
            boolean flag,
            Entity.RemovalReason removalReason,
            PlayerRespawnEvent.RespawnReason respawnReason,
            @Nullable Location location
    ) {
        if (respawnReason == null && location != null) {
            if (bridge$platform$onTravelToDimension(
                    player, ((CraftWorld) location.getWorld()).getHandle().dimension)) {
                return null;
            }
        }

        player.stopRiding();
        this.players.remove(player);
        player.serverLevel().removePlayerImmediately(player, removalReason);
        ((EntityBridge) player).bridge$revive();

        org.bukkit.World fromWorld = ((ServerPlayerBridge) player).bridge$getBukkitEntity().getWorld();
        player.wonGame = false;

        DimensionTransition dimensionTransition;
        if (location == null) {
            ((ServerPlayerBridge) player).bridge$pushRespawnReason(respawnReason);
            dimensionTransition = player.findRespawnPositionAndUseSpawnBlock(
                flag, DimensionTransition.DO_NOTHING
            );
            if (!flag) {
                ((ServerPlayerBridge) player).bridge$reset(); // SPIGOT-4785
            }
        } else {
            dimensionTransition = new DimensionTransition(
                ((CraftWorld) location.getWorld()).getHandle(),
                CraftLocation.toVec3D(location),
                Vec3.ZERO,
                location.getYaw(), location.getPitch(),
                DimensionTransition.DO_NOTHING
            );
        }

        if (dimensionTransition == null) {
            return player;
        }

        ServerLevel targetWorld = ((CraftWorld) location.getWorld()).getHandle();
        player.setServerLevel(targetWorld);
        player.unsetRemoved();
        player.setShiftKeyDown(false);
        player.moveTo(
            location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch()
        );
        player.connection.resetPosition();

        if (dimensionTransition.missingRespawnBlock()) {
            player.connection.send(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F
            ));
            ((ServerPlayerBridge) player).bridge$pushChangeSpawnCause(
                PlayerSpawnChangeEvent.Cause.RESET
            );
            // SPIGOT-5988: Clear respawn location when obstructed
            player.setRespawnPosition(null, null, 0f, false, false);
        }

        LevelData worldData = targetWorld.getLevelData();
        player.connection.send(new ClientboundRespawnPacket(
            player.createCommonSpawnInfo(targetWorld), (byte) (flag ? 1 : 0)
        ));
        player.connection.send(new ClientboundSetChunkCacheRadiusPacket(
            ((WorldBridge) targetWorld).bridge$spigotConfig().viewDistance
        ));
        player.connection.send(new ClientboundSetSimulationDistancePacket(
            ((WorldBridge) targetWorld).bridge$spigotConfig().simulationDistance
        ));
        ((ServerGamePacketListenerImplBridge) player.connection).bridge$teleport(new Location(
            ((WorldBridge) targetWorld).bridge$getWorld(),
            player.getX(), player.getY(), player.getZ(),
            player.getYRot(), player.getXRot()
        ));
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(
            targetWorld.getSharedSpawnPos(), targetWorld.getSharedSpawnAngle()
        ));
        player.connection.send(new ClientboundChangeDifficultyPacket(
            worldData.getDifficulty(), worldData.isDifficultyLocked()
        ));
        player.connection.send(new ClientboundSetExperiencePacket(
            player.experienceProgress, player.totalExperience, player.experienceLevel
        ));

        this.sendActivePlayerEffects(player);
        this.sendLevelInfo(player, targetWorld);
        this.sendPlayerPermissionLevel(player);

        if (!((ServerGamePacketListenerImplBridge) player.connection).bridge$isDisconnected()) {
            targetWorld.addRespawnedPlayer(player);
            this.players.add(player);
            this.playersByUUID.put(player.getUUID(), player);
        }

        player.setHealth(player.getHealth());
        bridge$platform$onPlayerChangedDimension(
            player,
            ((CraftWorld) fromWorld).getHandle().dimension,
            targetWorld.dimension
        );

        if (!flag) {
            BlockPos blockPos = BlockPos.containing(dimensionTransition.pos());
            BlockState blockState = targetWorld.getBlockState(blockPos);
            if (blockState.is(Blocks.RESPAWN_ANCHOR)) {
                player.connection.send(new ClientboundSoundPacket(
                    SoundEvents.RESPAWN_ANCHOR_DEPLETE, SoundSource.BLOCKS,
                    blockPos.getX(), blockPos.getY(), blockPos.getZ(),
                    1.0F, 1.0F, targetWorld.getRandom().nextLong()
                ));
            }
        }

        this.sendAllPlayerInfo(player);
        player.onUpdateAbilities();
        player.triggerDimensionChangeTriggers(((CraftWorld) fromWorld).getHandle());

        if (fromWorld != location.getWorld()) {
            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(
                ((ServerPlayerBridge) player).bridge$getBukkitEntity(), fromWorld
            );
            Bukkit.getPluginManager().callEvent(event);
        }

        if (((ServerGamePacketListenerImplBridge) player.connection).bridge$isDisconnected()) {
            this.save(player);
        }

        return player;
    }

    // ── Respawn bridge ────────────────────────────────────────────────────────

    @Override
    public void bridge$pushRespawnCause(PlayerRespawnEvent.RespawnReason respawnReason) {
        if (respawnReason != null) {
            arclight$respawnReason = respawnReason;
        }
    }

    @Inject(method = "respawn", at = @At("HEAD"))
    private void arclight$stopRiding(
            ServerPlayer player, boolean flag, Entity.RemovalReason reason,
            CallbackInfoReturnable<ServerPlayer> cir) {
        player.stopRiding();
    }

    @Decorate(
        method = "respawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;findRespawnPositionAndUseSpawnBlock(" +
                     "ZLnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)" +
                     "Lnet/minecraft/world/level/portal/DimensionTransition;"
        )
    )
    private DimensionTransition arclight$respawnPoint(
            ServerPlayer player, boolean flag,
            DimensionTransition.PostDimensionTransition postTransition) throws Throwable {
        Location loc = arclight$loc;
        PlayerRespawnEvent.RespawnReason reason = (arclight$respawnReason != null)
            ? arclight$respawnReason
            : PlayerRespawnEvent.RespawnReason.DEATH;

        DimensionTransition transition;
        if (loc == null) {
            ((ServerPlayerBridge) player).bridge$pushRespawnReason(reason);
            transition = (DimensionTransition) DecorationOps.callsite()
                .invoke(player, flag, postTransition);
        } else {
            transition = new DimensionTransition(
                ((CraftWorld) loc.getWorld()).getHandle(),
                CraftLocation.toVec3D(loc),
                Vec3.ZERO,
                loc.getYaw(), loc.getPitch(),
                DimensionTransition.DO_NOTHING
            );
        }

        if (transition == null) {
            return (DimensionTransition) DecorationOps.cancel().invoke(player);
        }
        return transition;
    }

    @Decorate(
        method = "respawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"
        )
    )
    private void arclight$respawnPackets(
            ServerGamePacketListenerImpl listener,
            double x, double y, double z, float yaw, float pitch,
            @Local(ordinal = -1) ServerPlayer player
    ) throws Throwable {
        player.connection.send(new ClientboundSetChunkCacheRadiusPacket(
            ((WorldBridge) player.serverLevel()).bridge$spigotConfig().viewDistance
        ));
        player.connection.send(new ClientboundSetSimulationDistancePacket(
            ((WorldBridge) player.serverLevel()).bridge$spigotConfig().simulationDistance
        ));
        ((ServerGamePacketListenerImplBridge) player.connection).bridge$teleport(new Location(
            player.serverLevel().bridge$getWorld(),
            player.getX(), player.getY(), player.getZ(),
            player.getYRot(), player.getXRot()
        ));
        if (Blackhole.actuallyFalse()) {
            DecorationOps.callsite().invoke(listener, x, y, z, yaw, pitch);
        }
    }

    @Inject(method = "respawn", at = @At("RETURN"))
    private void arclight$postRespawn(
            ServerPlayer original, boolean flag, Entity.RemovalReason reason,
            CallbackInfoReturnable<ServerPlayer> cir) {
        arclight$loc = null;
        arclight$respawnReason = null;

        ServerLevel fromLevel = original.serverLevel();
        ServerPlayer newPlayer = cir.getReturnValue();

        this.sendAllPlayerInfo(newPlayer);
        newPlayer.onUpdateAbilities();
        newPlayer.triggerDimensionChangeTriggers(fromLevel);

        if (fromLevel != newPlayer.serverLevel()) {
            PlayerChangedWorldEvent event = new PlayerChangedWorldEvent(
                ((ServerPlayerBridge) newPlayer).bridge$getBukkitEntity(),
                fromLevel.bridge$getWorld()
            );
            Bukkit.getPluginManager().callEvent(event);
        }

        if (((ServerGamePacketListenerImplBridge) newPlayer.connection).bridge$isDisconnected()) {
            this.save(newPlayer);
        }
    }

    // ── Broadcast utilities ───────────────────────────────────────────────────

    /**
     * Broadcasts a packet to all players who can see the given entity.
     * Used for entity-related packets that respect Bukkit's player visibility API.
     *
     * @param packet the packet to broadcast
     * @param source the entity whose visibility determines recipients
     */
    public void broadcastAll(Packet<?> packet, net.minecraft.world.entity.player.Player source) {
        for (ServerPlayer recipient : this.players) {
            if (!(source instanceof ServerPlayer sourceSp)
                    || ((ServerPlayerBridge) recipient).bridge$getBukkitEntity()
                        .canSee(((ServerPlayerBridge) sourceSp).bridge$getBukkitEntity())) {
                recipient.connection.send(packet);
            }
        }
    }

    /**
     * Broadcasts a packet to all players in the given level.
     *
     * @param packet the packet to broadcast
     * @param level  the level whose players should receive the packet
     */
    public void broadcastAll(Packet<?> packet, Level level) {
        for (int i = 0; i < level.players().size(); i++) {
            ((ServerPlayer) level.players().get(i)).connection.send(packet);
        }
    }

    // ── Permission recalculation ──────────────────────────────────────────────

    /**
     * Recalculates Bukkit permissions after the vanilla operator permission
     * level is updated. Ensures plugins relying on {@code hasPermission()} see
     * the updated op status immediately.
     */
    @Inject(
        method = "sendPlayerPermissionLevel(Lnet/minecraft/server/level/ServerPlayer;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getCommands()Lnet/minecraft/commands/Commands;"
        )
    )
    private void arclight$calculatePerms(ServerPlayer player, int permLevel, CallbackInfo ci) {
        ((ServerPlayerBridge) player).bridge$getBukkitEntity().recalculatePermissions();
    }

    // ── Scaled health sync ────────────────────────────────────────────────────

    /**
     * Replaces {@code resetSentInfo()} with Arclight's full player info sync that:
     * <ul>
     *   <li>Updates the client with the correct scaled health (e.g., from health scale API)</li>
     *   <li>Refreshes all entity data to the player</li>
     *   <li>Sends the correct debug info level packet</li>
     *   <li>Sends the correct immediate respawn flag</li>
     * </ul>
     */
    @Redirect(
        method = "sendAllPlayerInfo",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;resetSentInfo()V"
        )
    )
    private void arclight$useScaledHealth(ServerPlayer player) {
        ((ServerPlayerBridge) player).bridge$getBukkitEntity().updateScaledHealth();
        ((SynchedEntityDataBridge) player.getEntityData()).bridge$refresh(player);

        // Send the correct debug info packet based on the gamerule
        int debugEventId = player.level().getGameRules()
            .getBoolean(GameRules.RULE_REDUCEDDEBUGINFO) ? 22 : 23;
        player.connection.send(new ClientboundEntityEventPacket(player, (byte) debugEventId));

        // Send the immediate respawn flag
        float immediateRespawn = player.level().getGameRules()
            .getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN) ? 1.0F : 0.0F;
        player.connection.send(new ClientboundGameEventPacket(
            ClientboundGameEventPacket.IMMEDIATE_RESPAWN, immediateRespawn
        ));
    }

    // ── Message broadcast ─────────────────────────────────────────────────────

    /**
     * Broadcasts multiple chat components to all players on the server.
     *
     * @param components the components to broadcast
     */
    public void broadcastMessage(Component[] components) {
        for (Component component : components) {
            broadcastSystemMessage(component, false);
        }
    }

    @Override
    public void bridge$sendMessage(Component[] components) {
        this.broadcastMessage(components);
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    /**
     * Returns the statistics counter for the given player, loading it from
     * disk if the player is not currently online.
     *
     * @param player the online player
     * @return the player's statistics counter
     */
    public ServerStatsCounter getPlayerStats(ServerPlayer player) {
        ServerStatsCounter stats = player.getStats();
        return (stats != null) ? stats : getPlayerStats(player.getUUID(), player.getName().getString());
    }

    /**
     * Loads the statistics counter for a player by UUID, with fallback to
     * the legacy name-based file if the UUID file does not exist.
     *
     * @param uuid        the player's UUID
     * @param displayName the player's display name (for legacy file migration)
     * @return the loaded or created statistics counter
     */
    public ServerStatsCounter getPlayerStats(UUID uuid, String displayName) {
        ServerPlayer online = this.getPlayer(uuid);
        if (online != null && online.getStats() != null) {
            return online.getStats();
        }

        File statsDir = this.server.getWorldPath(LevelResource.PLAYER_STATS_DIR).toFile();
        File uuidFile = new File(statsDir, uuid + ".json");
        File legacyFile = new File(statsDir, displayName + ".json");

        // Migrate legacy name-based stats file to UUID-based
        if (!uuidFile.exists() && legacyFile.exists() && legacyFile.isFile()) {
            legacyFile.renameTo(uuidFile);
        }

        return new ServerStatsCounter(this.server, uuidFile);
    }

    // ── Removal cause tracking ────────────────────────────────────────────────

    /**
     * Marks all entities in the player's vehicle hierarchy with the
     * {@link EntityRemoveEvent.Cause#PLAYER_QUIT} cause before dismounting.
     */
    @Inject(
        method = "remove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;stopRiding()V"
        )
    )
    private void arclight$removeMount(ServerPlayer player, CallbackInfo ci) {
        player.getRootVehicle().getPassengersAndSelf().forEach(entity ->
            ((EntityBridge) entity).bridge$pushEntityRemoveCause(
                EntityRemoveEvent.Cause.PLAYER_QUIT
            )
        );
    }

    // ── Advancement flush ─────────────────────────────────────────────────────

    /**
     * Flushes all dirty advancements to players after a resource reload.
     * Without this, advancement progress updated during the reload might not
     * be synced to clients until the next natural flush cycle.
     */
    @Inject(
        method = "reloadResources",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void arclight$flushAdvancements(CallbackInfo ci) {
        for (ServerPlayer player : this.players) {
            player.getAdvancements().flushDirty(player);
        }
    }
}