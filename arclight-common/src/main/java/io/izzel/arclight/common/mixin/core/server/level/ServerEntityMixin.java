package io.izzel.arclight.common.mixin.core.server.level;

import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerEntityBridge;
import io.izzel.arclight.common.mod.ArclightConstants;
import io.izzel.arclight.common.mod.mixins.annotation.CreateConstructor;
import io.izzel.arclight.common.mod.mixins.annotation.ShadowConstructor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Mixin for {@link ServerEntity} that integrates Bukkit's velocity event pipeline
 * and entity tracking with wall-time awareness.
 *
 * <p>Key changes over vanilla:</p>
 * <ul>
 *   <li>Velocity changes fire {@link PlayerVelocityEvent} for server players</li>
 *   <li>Tick-based counters use elapsed real ticks for lag compensation</li>
 *   <li>Removed passengers trigger teleport corrections on the client</li>
 *   <li>Scaled health injection into attribute send packets</li>
 * </ul>
 */
@Mixin(value = ServerEntity.class, priority = 1100)
public abstract class ServerEntityMixin implements ServerEntityBridge {

    // @formatter:off
    @Shadow @Final private Entity entity;
    @Shadow private List<Entity> lastPassengers;
    @Shadow @Final private Consumer<Packet<?>> broadcast;
    @Shadow private int tickCount;
    @Shadow @Final private ServerLevel level;
    @Shadow protected abstract void sendDirtyEntityData();
    @Shadow @Final private int updateInterval;
    @Shadow @Final private VecDeltaCodec positionCodec;
    @Shadow private boolean wasRiding;
    @Shadow private int teleportDelay;
    @Shadow private boolean wasOnGround;
    @Shadow @Final private boolean trackDelta;
    @Shadow protected abstract void broadcastAndSend(Packet<?> packet);
    @Shadow @Nullable private List<SynchedEntityData.DataValue<?>> trackedDataValues;
    @Shadow private static Stream<Entity> removedPassengers(List<Entity> current, List<Entity> previous) { return null; }
    @Shadow private int lastSentYRot;
    @Shadow private int lastSentXRot;
    @Shadow private Vec3 lastSentMovement;
    @Shadow private int lastSentYHeadRot;
    // @formatter:on

    /** The set of connections currently tracking this entity. */
    private Set<ServerPlayerConnection> trackedPlayers;

    /**
     * Wall-clock tick counter for lag compensation.
     * Initialized to {@code currentTick - 1} so the first update produces
     * an elapsed time of 1 tick.
     */
    @Unique private int arclight$lastTick;

    /**
     * Last {@code tickCount / updateInterval} value used to throttle updates.
     * Initialized to -1 so the first tick always sends a full update.
     */
    @Unique private int arclight$lastUpdate;

    /** Last {@code tickCount / 60} for position update throttling. */
    @Unique private int arclight$lastPosUpdate;

    /** Last {@code tickCount / 10} for map item update throttling. */
    @Unique private int arclight$lastMapUpdate;

    // ── Initialization ────────────────────────────────────────────────────────

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$init(
            ServerLevel serverWorld,
            Entity entity,
            int updateFrequency,
            boolean sendVelocityUpdates,
            Consumer<Packet<?>> packetConsumer,
            CallbackInfo ci
    ) {
        this.trackedPlayers = new HashSet<>();
        this.arclight$lastTick = ArclightConstants.currentTick - 1;
        this.arclight$lastUpdate = -1;
        this.arclight$lastPosUpdate = -1;
        this.arclight$lastMapUpdate = -1;
    }

    // ── Constructor bridge ────────────────────────────────────────────────────

    @ShadowConstructor
    public void arclight$constructor(
            ServerLevel serverWorld,
            Entity entity,
            int updateFrequency,
            boolean sendVelocityUpdates,
            Consumer<Packet<?>> packetConsumer) {
        throw new NullPointerException("Shadow constructor stub");
    }

    @CreateConstructor
    public void arclight$constructor(
            ServerLevel serverWorld,
            Entity entity,
            int updateFrequency,
            boolean sendVelocityUpdates,
            Consumer<Packet<?>> packetConsumer,
            Set<ServerPlayerConnection> trackedPlayers
    ) {
        arclight$constructor(serverWorld, entity, updateFrequency, sendVelocityUpdates, packetConsumer);
        this.trackedPlayers = trackedPlayers;
    }

    @Override
    public void bridge$setTrackedPlayers(Set<ServerPlayerConnection> trackedPlayers) {
        this.trackedPlayers = trackedPlayers;
    }

    // ── Main tracking update ──────────────────────────────────────────────────

    /**
     * @author IzzelAliz
     * @reason Overwritten to integrate wall-time elapsed tick compensation,
     * Bukkit PlayerVelocityEvent, and per-update counter tracking.
     */
    @Overwrite
    public void sendChanges() {
        // ── Passenger tracking ────────────────────────────────────────────────
        List<Entity> currentPassengers = this.entity.getPassengers();
        if (!currentPassengers.equals(this.lastPassengers)) {
            this.broadcastAndSend(new ClientboundSetPassengersPacket(this.entity));
            // Teleport removed passengers to their current position to fix client desync
            removedPassengers(currentPassengers, this.lastPassengers).forEach(removed -> {
                if (removed instanceof ServerPlayer removedPlayer) {
                    removedPlayer.connection.teleport(
                        removedPlayer.getX(), removedPlayer.getY(), removedPlayer.getZ(),
                        removedPlayer.getYRot(), removedPlayer.getXRot()
                    );
                }
            });
            this.lastPassengers = currentPassengers;
        }

        // ── Wall-time elapsed tick calculation ────────────────────────────────
        int elapsedTicks = ArclightConstants.currentTick - this.arclight$lastTick;
        if (elapsedTicks < 0) {
            elapsedTicks = 0;
        }
        this.arclight$lastTick = ArclightConstants.currentTick;

        // ── Item frame map updates ────────────────────────────────────────────
        if (this.entity instanceof ItemFrame itemFrame) {
            ItemStack mapStack = itemFrame.getItem();
            if (this.tickCount / 10 != this.arclight$lastMapUpdate
                    && mapStack.getItem() instanceof MapItem) {
                MapId mapId = mapStack.get(DataComponents.MAP_ID);
                MapItemSavedData mapData = MapItem.getSavedData(mapId, this.level);
                if (mapData != null) {
                    for (ServerPlayerConnection connection : this.trackedPlayers) {
                        ServerPlayer viewer = connection.getPlayer();
                        mapData.tickCarriedBy(viewer, mapStack);
                        Packet<?> mapPacket = mapData.getUpdatePacket(mapId, viewer);
                        if (mapPacket != null) {
                            viewer.connection.send(mapPacket);
                        }
                    }
                }
            }
            this.sendDirtyEntityData();
        }

        // ── Position and rotation updates ─────────────────────────────────────
        boolean needsUpdate = this.tickCount / this.updateInterval != this.arclight$lastUpdate
            || this.entity.hasImpulse
            || this.entity.getEntityData().isDirty();

        if (needsUpdate) {
            if (this.entity.isPassenger()) {
                // Entity is riding — only send rotation updates
                int encodedYRot = Mth.floor(this.entity.getYRot() * 256.0F / 360.0F);
                int encodedXRot = Mth.floor(this.entity.getXRot() * 256.0F / 360.0F);
                boolean rotChanged = Math.abs(encodedYRot - this.lastSentYRot) >= 1
                    || Math.abs(encodedXRot - this.lastSentXRot) >= 1;

                if (rotChanged) {
                    this.broadcast.accept(new ClientboundMoveEntityPacket.Rot(
                        this.entity.getId(), (byte) encodedYRot, (byte) encodedXRot,
                        this.entity.onGround()
                    ));
                    this.lastSentYRot = encodedYRot;
                    this.lastSentXRot = encodedXRot;
                }
                this.positionCodec.setBase(this.entity.trackingPosition());
                this.sendDirtyEntityData();
                this.wasRiding = true;
            } else {
                // Entity is not riding — send full position/rotation updates
                this.teleportDelay += elapsedTicks;

                int encodedYRot = Mth.floor(this.entity.getYRot() * 256.0F / 360.0F);
                int encodedXRot = Mth.floor(this.entity.getXRot() * 256.0F / 360.0F);
                Vec3 trackingPos = this.entity.trackingPosition();
                boolean posChanged = this.positionCodec.delta(trackingPos).lengthSqr() >= 7.62939453125E-6D;
                boolean needsPeriodicPos = this.tickCount / 60 != this.arclight$lastPosUpdate;
                boolean posNeedsUpdate = posChanged || needsPeriodicPos;
                boolean rotNeedsUpdate = Math.abs(encodedYRot - this.lastSentYRot) >= 1
                    || Math.abs(encodedXRot - this.lastSentXRot) >= 1;

                boolean sendPos = false;
                boolean sendRot = false;
                Packet<?> movePacket = null;

                long encodedX = this.positionCodec.encodeX(trackingPos);
                long encodedY = this.positionCodec.encodeY(trackingPos);
                long encodedZ = this.positionCodec.encodeZ(trackingPos);
                boolean outOfRange = encodedX < -32768L || encodedX > 32767L
                    || encodedY < -32768L || encodedY > 32767L
                    || encodedZ < -32768L || encodedZ > 32767L;

                if (!outOfRange && this.teleportDelay <= 400
                        && !this.wasRiding && this.wasOnGround == this.entity.onGround()) {
                    if ((!posNeedsUpdate || !rotNeedsUpdate) && !(this.entity instanceof AbstractArrow)) {
                        if (posNeedsUpdate) {
                            movePacket = new ClientboundMoveEntityPacket.Pos(
                                this.entity.getId(),
                                (short) ((int) encodedX), (short) ((int) encodedY), (short) ((int) encodedZ),
                                this.entity.onGround()
                            );
                            sendPos = true;
                        } else if (rotNeedsUpdate) {
                            movePacket = new ClientboundMoveEntityPacket.Rot(
                                this.entity.getId(),
                                (byte) encodedYRot, (byte) encodedXRot,
                                this.entity.onGround()
                            );
                            sendRot = true;
                        }
                    } else {
                        movePacket = new ClientboundMoveEntityPacket.PosRot(
                            this.entity.getId(),
                            (short) ((int) encodedX), (short) ((int) encodedY), (short) ((int) encodedZ),
                            (byte) encodedYRot, (byte) encodedXRot,
                            this.entity.onGround()
                        );
                        sendPos = sendRot = true;
                    }
                } else {
                    this.wasOnGround = this.entity.onGround();
                    this.teleportDelay = 0;
                    movePacket = new ClientboundTeleportEntityPacket(this.entity);
                    sendPos = sendRot = true;
                }

                // Send velocity/impulse updates
                boolean isFallFlying = this.entity instanceof LivingEntity living && living.isFallFlying();
                if ((this.trackDelta || this.entity.hasImpulse || isFallFlying) && this.tickCount > 0) {
                    Vec3 currentVelocity = this.entity.getDeltaMovement();
                    double velDistSq = currentVelocity.distanceToSqr(this.lastSentMovement);
                    if (velDistSq > 1.0E-7D
                            || (velDistSq > 0.0D && currentVelocity.lengthSqr() == 0.0D)) {
                        this.lastSentMovement = currentVelocity;
                        if (this.entity instanceof AbstractHurtingProjectile fireball) {
                            this.broadcast.accept(new ClientboundBundlePacket(List.of(
                                new ClientboundSetEntityMotionPacket(this.entity.getId(), this.lastSentMovement),
                                new ClientboundProjectilePowerPacket(fireball.getId(), fireball.accelerationPower)
                            )));
                        } else {
                            this.broadcast.accept(new ClientboundSetEntityMotionPacket(
                                this.entity.getId(), this.lastSentMovement
                            ));
                        }
                    }
                }

                if (movePacket != null) {
                    this.broadcast.accept(movePacket);
                }
                this.sendDirtyEntityData();

                if (sendPos) {
                    this.positionCodec.setBase(trackingPos);
                }
                if (sendRot) {
                    this.lastSentYRot = encodedYRot;
                    this.lastSentXRot = encodedXRot;
                }
                this.wasRiding = false;
            }

            // Head rotation
            int encodedHeadRot = Mth.floor(this.entity.getYHeadRot() * 256.0F / 360.0F);
            if (Math.abs(encodedHeadRot - this.lastSentYHeadRot) >= 1) {
                this.broadcast.accept(new ClientboundRotateHeadPacket(this.entity, (byte) encodedHeadRot));
                this.lastSentYHeadRot = encodedHeadRot;
            }

            this.entity.hasImpulse = false;
        }

        // ── Update throttle counters ───────────────────────────────────────────
        this.arclight$lastUpdate    = this.tickCount / this.updateInterval;
        this.arclight$lastPosUpdate = this.tickCount / 60;
        this.arclight$lastMapUpdate = this.tickCount / 10;
        this.tickCount += elapsedTicks;

        // ── Velocity / hurt marker ────────────────────────────────────────────
        if (this.entity.hurtMarked) {
            boolean cancelled = false;

            if (this.entity instanceof ServerPlayer serverPlayer) {
                Player bukkitPlayer = ((ServerPlayerBridge) serverPlayer).bridge$getBukkitEntity();
                Vector currentVelocity = bukkitPlayer.getVelocity();
                PlayerVelocityEvent event = new PlayerVelocityEvent(
                    bukkitPlayer, currentVelocity.clone()
                );
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    cancelled = true;
                } else if (!currentVelocity.equals(event.getVelocity())) {
                    bukkitPlayer.setVelocity(event.getVelocity());
                }
            }

            if (!cancelled) {
                this.entity.hurtMarked = false;
                this.broadcastAndSend(new ClientboundSetEntityMotionPacket(this.entity));
            }
        }
    }

    // ── Scaled health injection ───────────────────────────────────────────────

    /**
     * Injects scaled max health into attribute update packets for server players.
     * This ensures the client-side health bar reflects Bukkit's health scale setting.
     */
    @Inject(
        method = "sendDirtyEntityData",
        locals = LocalCapture.CAPTURE_FAILHARD,
        at = @At(
            value = "INVOKE",
            ordinal = 1,
            target = "Lnet/minecraft/server/level/ServerEntity;broadcastAndSend(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void arclight$sendScaledHealth(
            CallbackInfo ci,
            SynchedEntityData entityDataManager,
            List<SynchedEntityData.DataValue<?>> dataValues,
            Set<AttributeInstance> attributeSet
    ) {
        if (this.entity instanceof ServerPlayerBridge player) {
            player.bridge$getBukkitEntity().injectScaledMaxHealth(attributeSet, false);
        }
    }

    // ── Pairing guards ────────────────────────────────────────────────────────

    /**
     * Prevents pairing (sending spawn data) for entities that have been removed.
     * Without this, clients can receive spawn packets for entities that no longer exist,
     * causing orphaned ghost entities.
     */
    @Inject(
        method = "addPairing",
        cancellable = true,
        require = 0,
        at = @At("HEAD")
    )
    private void arclight$returnIfRemoved(CallbackInfo ci) {
        if (this.entity.isRemoved()) {
            ci.cancel();
        }
    }

    /**
     * Injects scaled health for the local player when pairing data is sent.
     * This is separate from {@link #arclight$sendScaledHealth} because pairing
     * sends all attributes, not just dirty ones.
     */
    @Redirect(
        method = "sendPairingData",
        require = 0,
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Collection;isEmpty()Z"
        )
    )
    private boolean arclight$injectScaledHealthOnPairing(
            Collection<AttributeInstance> attributes, ServerPlayer targetPlayer) {
        if (this.entity.getId() == targetPlayer.getId()) {
            ((ServerPlayerBridge) this.entity).bridge$getBukkitEntity()
                .injectScaledMaxHealth(attributes, false);
        }
        return attributes.isEmpty();
    }
}