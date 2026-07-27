package io.izzel.arclight.common.mixin.core.server.network;

import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.server.network.ServerCommonPacketListenerImplBridge;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.mod.mixins.annotation.CreateConstructor;
import io.izzel.arclight.common.mod.mixins.annotation.ShadowConstructor;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.entity.CraftPlayer;
import org.bukkit.craftbukkit.v.util.CraftChatMessage;
import org.bukkit.craftbukkit.v.util.Waitable;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ExecutionException;

/**
 * Mixin for {@link ServerCommonPacketListenerImpl} that integrates Bukkit
 * event handling into the common packet listener layer.
 *
 * <p>This is the base mixin shared by both configuration and game phases.
 * Responsibilities include:</p>
 * <ul>
 *   <li>Bukkit kick event dispatch with async-to-main-thread safety</li>
 *   <li>Compass target tracking via spawn position packets</li>
 *   <li>Resource pack status event forwarding</li>
 *   <li>Cookie response handling via CraftPlayer</li>
 *   <li>Keepalive timeout extension (15s → 25s) for hybrid servers</li>
 *   <li>Transfer cookie connection bridging</li>
 * </ul>
 */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 1100)
public abstract class ServerCommonPacketListenerImplMixin
        implements ServerCommonPacketListenerImplBridge,
                   PacketListener,
                   CraftPlayer.TransferCookieConnection {

    // @formatter:off
    @Shadow @Final public Connection connection;
    @Shadow @Final protected MinecraftServer server;
    @Shadow public abstract void send(Packet<?> packet);
    @Shadow protected abstract boolean isSingleplayerOwner();
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private boolean transferred;
    @Shadow public abstract void disconnect(DisconnectionDetails disconnectionDetails);
    @Shadow public abstract void onDisconnect(DisconnectionDetails disconnectionDetails);
    @Shadow public abstract void disconnect(Component component);
    // @formatter:on

    // ── Bukkit state ──────────────────────────────────────────────────────────

    /** The NMS player associated with this connection. */
    protected ServerPlayer player;

    /** Cached CraftServer reference. */
    protected CraftServer cserver;

    /**
     * Flag to prevent double-processing of disconnect logic.
     * Set to {@code true} after the first disconnect is fully handled.
     */
    public boolean processedDisconnect;

    // ── Bukkit player accessor ────────────────────────────────────────────────

    /**
     * Returns the Bukkit {@link CraftPlayer} for this connection,
     * or {@code null} if the NMS player has not yet been assigned.
     */
    public CraftPlayer getCraftPlayer() {
        return (this.player == null)
            ? null
            : ((ServerPlayerBridge) this.player).bridge$getBukkitEntity();
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    @ShadowConstructor
    public abstract void arclight$this(
            MinecraftServer server, Connection connection, CommonListenerCookie cookie);

    /**
     * Extended constructor that also sets the player reference and links
     * the transfer cookie connection. Used by configuration → game transitions.
     *
     * @param server     the NMS server
     * @param connection the network connection
     * @param cookie     the common listener cookie (game profile, client info)
     * @param player     the NMS player entity
     */
    @CreateConstructor
    public void arclight$constructor(
            MinecraftServer server,
            Connection connection,
            CommonListenerCookie cookie,
            ServerPlayer player) {
        arclight$this(server, connection, cookie);
        this.player = player;
        ((ServerPlayerBridge) player).bridge$setTransferCookieConnection(this);
        this.cserver = (CraftServer) Bukkit.getServer();
    }

    // ── Bridge implementation ─────────────────────────────────────────────────

    @Override
    public CraftServer bridge$getCraftServer() {
        return cserver;
    }

    @Override
    public CraftPlayer bridge$getCraftPlayer() {
        return getCraftPlayer();
    }

    @Override
    public ServerPlayer bridge$getPlayer() {
        return player;
    }

    @Override
    public void bridge$setPlayer(ServerPlayer player) {
        this.player = player;
        ((ServerPlayerBridge) this.player).bridge$setTransferCookieConnection(this);
    }

    // ── Initialization ────────────────────────────────────────────────────────

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$init(
            MinecraftServer server,
            Connection connection,
            CommonListenerCookie cookie,
            CallbackInfo ci) {
        this.cserver = (CraftServer) Bukkit.getServer();
    }

    // ── Keepalive timeout ─────────────────────────────────────────────────────

    /**
     * Increases the keepalive timeout from 15 seconds to 25 seconds.
     * Hybrid NeoForge+Bukkit servers process more data per tick, and
     * heavy mod loading can cause keepalive responses to be delayed.
     * {@code require = 0} prevents crashes if another mod modifies this constant.
     */
    @ModifyConstant(
        method = "keepConnectionAlive",
        constant = @Constant(longValue = 15000L),
        require = 0
    )
    private long arclight$incrKeepaliveTimeout(long original) {
        return 25000L;
    }

    // ── Disconnect state ──────────────────────────────────────────────────────

    @Override
    public boolean bridge$processedDisconnect() {
        return this.processedDisconnect;
    }

    /**
     * Returns {@code true} if the player has fully disconnected:
     * not in the join phase AND the network channel is closed.
     */
    public final boolean isDisconnected() {
        return !((ServerPlayerBridge) this.player).bridge$isJoining()
            && !this.connection.isConnected();
    }

    @Override
    public boolean bridge$isDisconnected() {
        return this.isDisconnected();
    }

    // ── Disconnect convenience ────────────────────────────────────────────────

    public void disconnect(String s) {
        this.disconnect(Component.literal(s));
    }

    @Override
    public void bridge$disconnect(String s) {
        disconnect(s);
    }

    // ── Kick event ────────────────────────────────────────────────────────────

    /**
     * Intercepts the disconnect packet send to fire {@link PlayerKickEvent}.
     *
     * <p>Thread safety: If called from a non-main thread (e.g., Netty IO thread),
     * the disconnect is re-dispatched to the main thread via a {@link Waitable}
     * to prevent race conditions with player state.</p>
     *
     * <p>The kick event allows plugins to:</p>
     * <ul>
     *   <li>Cancel the kick entirely</li>
     *   <li>Modify the kick reason shown to the player</li>
     *   <li>Modify the leave message broadcast to other players</li>
     * </ul>
     */
    @Decorate(
        method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V"
        )
    )
    private void arclight$kickEvent(
            Connection instance,
            Packet<?> packet,
            PacketSendListener packetSendListener,
            DisconnectionDetails disconnectionDetails
    ) throws Throwable {
        // Already processed — skip to prevent double-kick
        if (this.processedDisconnect) {
            DecorationOps.cancel().invoke();
            return;
        }

        // Re-dispatch to main thread if called from async context
        if (!this.cserver.isPrimaryThread()) {
            Waitable<?> waitable = new Waitable<>() {
                @Override
                protected Object evaluate() {
                    disconnect(disconnectionDetails);
                    return null;
                }
            };
            ((MinecraftServerBridge) this.server).bridge$queuedProcess(waitable);
            try {
                waitable.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            DecorationOps.cancel().invoke();
            return;
        }

        // Fire the Bukkit kick event on the main thread
        String leaveMessage = ChatFormatting.YELLOW + this.player.getScoreboardName() + " left the game.";
        PlayerKickEvent event = new PlayerKickEvent(
            getCraftPlayer(),
            CraftChatMessage.fromComponent(disconnectionDetails.reason()),
            leaveMessage
        );

        if (this.cserver.getServer().isRunning()) {
            this.cserver.getPluginManager().callEvent(event);
        }

        if (event.isCancelled()) {
            DecorationOps.cancel().invoke();
            return;
        }

        // Capture the (potentially modified) leave message for broadcast
        ArclightCaptures.captureQuitMessage(event.getLeaveMessage());

        // Use the (potentially modified) kick reason from the event
        Component kickReason = CraftChatMessage.fromString(event.getReason(), true)[0];
        Packet<?> newPacket = new ClientboundDisconnectPacket(kickReason);

        DecorationOps.callsite().invoke(instance, newPacket, packetSendListener);
        this.onDisconnect(disconnectionDetails);
    }

    // ── Packet send guards ────────────────────────────────────────────────────

    /**
     * Guards all outgoing packets:
     * <ul>
     *   <li>Null packets are silently dropped</li>
     *   <li>Packets after disconnect are silently dropped</li>
     *   <li>Spawn position packets update the Bukkit compass target</li>
     * </ul>
     */
    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        cancellable = true,
        at = @At("HEAD")
    )
    private void arclight$updateCompassTarget(
            Packet<?> packetIn,
            PacketSendListener futureListeners,
            CallbackInfo ci
    ) {
        if (packetIn == null || processedDisconnect) {
            ci.cancel();
            return;
        }
        if (packetIn instanceof ClientboundSetDefaultSpawnPositionPacket spawnPacket) {
            ((ServerPlayerBridge) this.player).bridge$setCompassTarget(
                new Location(
                    this.getCraftPlayer().getWorld(),
                    spawnPacket.pos.getX(),
                    spawnPacket.pos.getY(),
                    spawnPacket.pos.getZ()
                )
            );
        }
    }

    // ── Resource pack status ──────────────────────────────────────────────────

    /**
     * Fires {@link PlayerResourcePackStatusEvent} after the client responds
     * to a resource pack request.
     */
    @Inject(
        method = "handleResourcePackResponse",
        at = @At("RETURN")
    )
    private void arclight$handleResourcePackStatus(
            ServerboundResourcePackPacket packetIn,
            CallbackInfo ci
    ) {
        this.cserver.getPluginManager().callEvent(new PlayerResourcePackStatusEvent(
            this.getCraftPlayer(),
            packetIn.id(),
            PlayerResourcePackStatusEvent.Status.values()[packetIn.action().ordinal()]
        ));
    }

    // ── Cookie response ───────────────────────────────────────────────────────

    /**
     * Delegates cookie responses to {@link CraftPlayer#handleCookieResponse},
     * which processes transfer cookies for cross-server communication.
     * Cancels the vanilla handler if the cookie was consumed by Bukkit.
     */
    @Inject(
        method = "handleCookieResponse",
        cancellable = true,
        at = @At("HEAD")
    )
    private void arclight$handleCookie(
            ServerboundCookieResponsePacket packet,
            CallbackInfo ci
    ) {
        PacketUtils.ensureRunningOnSameThread(
            packet,
            (ServerCommonPacketListenerImpl) (Object) this,
            this.server
        );
        if (((CraftPlayer) this.player.bridge$getBukkitEntity()).handleCookieResponse(packet)) {
            ci.cancel();
        }
    }

    // ── TransferCookieConnection implementation ───────────────────────────────

    @Override
    public boolean isTransferred() {
        return this.transferred;
    }

    @Override
    public ConnectionProtocol getProtocol() {
        return this.protocol();
    }

    @Override
    public void sendPacket(Packet<?> packet) {
        this.send(packet);
    }

    @Override
    public void kickPlayer(Component component) {
        disconnect(component);
    }
}