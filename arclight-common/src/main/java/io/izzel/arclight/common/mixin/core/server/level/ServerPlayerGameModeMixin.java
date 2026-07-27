package io.izzel.arclight.common.mixin.core.server.level;

import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerGameModeBridge;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import io.izzel.arclight.mixin.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin for {@link ServerPlayerGameMode} that integrates Bukkit block interaction,
 * breaking, and game mode change events into the player game mode pipeline.
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Fires {@link PlayerGameModeChangeEvent} on game mode changes</li>
 *   <li>Fires {@link PlayerInteractEvent} for left/right click block interactions</li>
 *   <li>Fires {@link BlockDamageEvent} when a player starts breaking a block</li>
 *   <li>Fires {@link BlockBreakEvent} and handles item drops via captures</li>
 *   <li>Tracks the last interact position/hand/item for anti-event-spam logic</li>
 * </ul>
 */
@Mixin(value = ServerPlayerGameMode.class, priority = 1100)
public abstract class ServerPlayerGameModeMixin implements ServerPlayerGameModeBridge {

    // @formatter:off
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;
    @Shadow private GameType gameModeForPlayer;
    // @formatter:on

    // ── Interact state tracking ───────────────────────────────────────────────

    /**
     * Whether the last interact event used the item in hand.
     * Used to suppress the default item interaction when cancelled by a plugin.
     */
    @Unique public boolean arclight$interactResult = false;

    /** Whether the interact event has already been fired for this interaction. */
    @Unique public boolean arclight$firedInteract = false;

    /** The block position of the last right-click-block interaction. */
    @Unique public BlockPos arclight$interactPosition;

    /** The hand used in the last interaction. */
    @Unique public InteractionHand arclight$interactHand;

    /** A copy of the item stack in hand at the time of the last interaction. */
    @Unique public ItemStack arclight$interactItemStack;

    // ── Game mode change event ────────────────────────────────────────────────

    /**
     * Fires {@link PlayerGameModeChangeEvent} before the game mode is actually changed.
     * Cancels the change if the event is cancelled.
     */
    @Inject(
        method = "changeGameModeForPlayer",
        cancellable = true,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;setGameModeForPlayer(" +
                     "Lnet/minecraft/world/level/GameType;Lnet/minecraft/world/level/GameType;)V"
        )
    )
    private void arclight$gameModeEvent(GameType newGameType, CallbackInfoReturnable<Boolean> cir) {
        PlayerGameModeChangeEvent event = new PlayerGameModeChangeEvent(
            ((ServerPlayerBridge) player).bridge$getBukkitEntity(),
            GameMode.getByValue(newGameType.getId())
        );
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            cir.setReturnValue(false);
        }
    }

    // ── Block break interactions ──────────────────────────────────────────────

    /**
     * Fires a PlayerInteractEvent when the player cannot interact with a block
     * due to {@code mayInteract} returning false.
     *
     * <p>Also sends a block update to the client and refreshes the block entity
     * data so the client doesn't show a broken state for a block it can't break.</p>
     *
     * <p><b>Note:</b> This series of interact events is controlled by the mod loader.
     * The cancellation behaviour differs from standard Bukkit events —
     * see {@link io.izzel.arclight.common.mixin.core.server.network.ServerGamePacketListenerImpl_HandlerMixin}
     * for the PSI-side cancel handling.</p>
     */
    @Decorate(
        method = "handleBlockBreakAction",
        at = @At(
            value = "INVOKE",
            ordinal = 0,
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"
        ),
        slice = @Slice(
            from = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/server/level/ServerLevel;mayInteract(" +
                         "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;)Z"
            )
        )
    )
    private void arclight$mayNotInteractEvent(
            ServerGamePacketListenerImpl listener,
            Packet<?> packet,
            BlockPos blockPos,
            ServerboundPlayerActionPacket.Action action,
            Direction direction
    ) throws Throwable {
        CraftEventFactory.callPlayerInteractEvent(
            this.player, Action.LEFT_CLICK_BLOCK, blockPos, direction,
            this.player.getInventory().getSelected(), InteractionHand.MAIN_HAND
        );
        DecorationOps.callsite().invoke(listener, packet);
        // Also refresh block entity data if present
        BlockEntity be = this.level.getBlockEntity(blockPos);
        if (be != null) {
            this.player.connection.send(be.getUpdatePacket());
        }
    }

    /**
     * Fires the main PlayerInteractEvent for left-click-block before the block
     * break process begins. Cancels the break if the event is cancelled.
     */
    @Decorate(
        method = "handleBlockBreakAction",
        inject = true,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;isCreative()Z"
        )
    )
    private void arclight$interactEvent(
            BlockPos blockPos,
            ServerboundPlayerActionPacket.Action action,
            Direction direction,
            @Local(allocate = "playerInteractEvent") PlayerInteractEvent event
    ) throws Throwable {
        event = CraftEventFactory.callPlayerInteractEvent(
            this.player, Action.LEFT_CLICK_BLOCK, blockPos, direction,
            this.player.getInventory().getSelected(), InteractionHand.MAIN_HAND
        );

        if (event.isCancelled()) {
            // Restore the block visually for the client
            this.player.connection.send(new ClientboundBlockUpdatePacket(this.level, blockPos));
            BlockEntity be = this.level.getBlockEntity(blockPos);
            if (be != null) {
                this.player.connection.send(be.getUpdatePacket());
            }
            DecorationOps.cancel().invoke();
            return;
        }
        DecorationOps.blackhole().invoke();
    }

    /**
     * Handles the case where the interact event denied block interaction
     * (e.g., right-click on a door that the plugin wants to keep closed).
     * Sends corrective block updates for double-height blocks.
     */
    @Decorate(
        method = "handleBlockBreakAction",
        at = @At(
            value = "INVOKE",
            ordinal = 0,
            target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"
        )
    )
    private boolean arclight$playerInteractCancelled(
            BlockState blockState,
            BlockPos blockPos,
            ServerboundPlayerActionPacket.Action action,
            Direction direction,
            @Local(allocate = "playerInteractEvent") PlayerInteractEvent event
    ) throws Throwable {
        if (event.useInteractedBlock() == Event.Result.DENY) {
            BlockState currentState = this.level.getBlockState(blockPos);
            if (currentState.getBlock() instanceof DoorBlock) {
                boolean isBottom = currentState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
                this.player.connection.send(new ClientboundBlockUpdatePacket(this.level, blockPos));
                this.player.connection.send(new ClientboundBlockUpdatePacket(
                    this.level, isBottom ? blockPos.above() : blockPos.below()
                ));
            } else if (currentState.getBlock() instanceof TrapDoorBlock) {
                this.player.connection.send(new ClientboundBlockUpdatePacket(this.level, blockPos));
            }
            return true; // Treat as air to cancel vanilla interaction
        }
        return (boolean) DecorationOps.callsite().invoke(blockState);
    }

    /**
     * Fires {@link BlockDamageEvent} when the player starts breaking a block
     * (i.e., the break progress is between 0 and 1 exclusive).
     * Cancels the break or enables insta-break based on the event result.
     */
    @Decorate(
        method = "handleBlockBreakAction",
        inject = true,
        at = @At(
            value = "INVOKE",
            ordinal = 1,
            target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"
        )
    )
    private void arclight$blockDamageEvent(
            BlockPos blockPos,
            ServerboundPlayerActionPacket.Action action,
            Direction direction,
            @Local(ordinal = -1) float breakProgress,
            @Local(allocate = "playerInteractEvent") PlayerInteractEvent event
    ) throws Throwable {
        if (event.useItemInHand() == Event.Result.DENY) {
            if (breakProgress > 1.0F) {
                this.player.connection.send(new ClientboundBlockUpdatePacket(this.level, blockPos));
            }
            return;
        }

        BlockDamageEvent damageEvent = CraftEventFactory.callBlockDamageEvent(
            this.player, blockPos, this.player.getInventory().getSelected(),
            breakProgress >= 1.0F
        );

        if (damageEvent.isCancelled()) {
            this.player.connection.send(new ClientboundBlockUpdatePacket(this.level, blockPos));
            return;
        }

        if (damageEvent.getInstaBreak()) {
            breakProgress = 2.0F;
        }
        DecorationOps.blackhole().invoke(breakProgress);
    }

    /**
     * Fires {@link org.bukkit.event.block.BlockDamageAbortEvent} when block breaking
     * is aborted (e.g., player cancels mid-break).
     */
    @Inject(
        method = "handleBlockBreakAction",
        at = @At(
            value = "CONSTANT",
            args = "stringValue=aborted destroying"
        )
    )
    private void arclight$abortBlockBreak(
            BlockPos blockPos,
            ServerboundPlayerActionPacket.Action action,
            Direction direction,
            int i, int j,
            CallbackInfo ci
    ) {
        CraftEventFactory.callBlockDamageAbortEvent(
            this.player, blockPos, this.player.getInventory().getSelected()
        );
    }

    // ── Block break drop handling ─────────────────────────────────────────────

    /**
     * Processes block drop captures after a block is destroyed.
     * Fires the BlockDropItemEvent with the collected drops.
     */
    @Inject(
        method = "destroyBlock",
        at = @At("RETURN")
    )
    public void arclight$resetBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ArclightCaptures.BlockBreakEventContext ctx = ArclightCaptures.popPrimaryBlockBreakEvent();
        if (ctx != null) {
            bridge$handleBlockDrop(ctx, pos);
        }
    }

    /**
     * Clears stale block break event captures before starting a new break session.
     * Prevents leftover events from a previous interrupted break from being processed.
     */
    @Inject(
        method = {"tick", "destroyAndAck"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    public void arclight$clearCaptures(CallbackInfo ci) {
        ArclightCaptures.clearBlockBreakEventContexts();
    }

    @Override
    public void bridge$handleBlockDrop(
            ArclightCaptures.BlockBreakEventContext ctx, BlockPos pos) {
        BlockBreakEvent breakEvent = ctx.getEvent();
        List<ItemEntity> drops = ctx.getBlockDrops();
        org.bukkit.block.BlockState state = ctx.getBlockBreakPlayerState();

        if (drops != null && (breakEvent == null || breakEvent.isDropItems())) {
            CraftBlock craftBlock = CraftBlock.at(this.level, pos);
            CraftEventFactory.handleBlockDropItemEvent(craftBlock, state, this.player, drops);
        }
    }

    // ── Interact state bridge ─────────────────────────────────────────────────

    @Override
    public boolean bridge$isFiredInteract() {
        return arclight$firedInteract;
    }

    @Override
    public void bridge$setFiredInteract(boolean b) {
        this.arclight$firedInteract = b;
    }

    @Override
    public boolean bridge$getInteractResult() {
        return arclight$interactResult;
    }

    @Override
    public void bridge$setInteractResult(boolean b) {
        this.arclight$interactResult = b;
    }

    @Override
    public BlockPos bridge$getInteractPosition() {
        return arclight$interactPosition;
    }

    @Override
    public InteractionHand bridge$getInteractHand() {
        return arclight$interactHand;
    }

    @Override
    public ItemStack bridge$getInteractItemStack() {
        return arclight$interactItemStack;
    }

    // ── Right click block handling ────────────────────────────────────────────

    /**
     * Fires {@link PlayerInteractEvent} for right-click-block interactions.
     * Handles UI and inventory sync for cancelled interactions on special blocks
     * (doors, cakes, double-height blocks).
     */
    @Inject(
        method = "useItemOn",
        cancellable = true,
        at = @At(
            value = "FIELD",
            opcode = Opcodes.GETFIELD,
            ordinal = 0,
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;gameModeForPlayer:Lnet/minecraft/world/level/GameType;"
        )
    )
    private void arclight$rightClickBlock(
            ServerPlayer player,
            Level world,
            ItemStack stack,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        BlockPos pos = hitResult.getBlockPos();
        BlockState blockState = world.getBlockState(pos);
        boolean cancelledBlock = false;

        // Spectators can only open inventories via MenuProvider blocks
        if (this.gameModeForPlayer == GameType.SPECTATOR) {
            MenuProvider provider = blockState.getMenuProvider(world, pos);
            cancelledBlock = !(provider instanceof MenuProvider);
        }

        // Items on cooldown cannot be used
        if (player.getCooldowns().isOnCooldown(stack.getItem())) {
            cancelledBlock = true;
        }

        PlayerInteractEvent event = CraftEventFactory.callPlayerInteractEvent(
            player, Action.RIGHT_CLICK_BLOCK, pos, hitResult.getDirection(),
            stack, cancelledBlock, hand, hitResult.getLocation()
        );

        bridge$setFiredInteract(true);
        bridge$setInteractResult(event.useItemInHand() == Event.Result.DENY);
        arclight$interactPosition = pos.immutable();
        arclight$interactHand = hand;
        arclight$interactItemStack = stack.copy();

        if (event.useInteractedBlock() == Event.Result.DENY) {
            // Send corrective block updates for special double-height/interactive blocks
            if (blockState.getBlock() instanceof DoorBlock) {
                boolean isBottom = blockState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
                player.connection.send(new ClientboundBlockUpdatePacket(
                    this.level, isBottom ? pos.above() : pos.below()
                ));
            } else if (blockState.getBlock() instanceof CakeBlock) {
                // Eating from a cake changes health — sync it back
                ((ServerPlayerBridge) player).bridge$getBukkitEntity().sendHealthUpdate();
            } else if (stack.getItem() instanceof DoubleHighBlockItem) {
                // Fix visual artifact for double-height block placement
                player.connection.send(new ClientboundBlockUpdatePacket(
                    this.level, pos.relative(hitResult.getDirection()).above()
                ));
                player.connection.send(new ClientboundBlockUpdatePacket(this.level, pos.above()));
            }
            ((ServerPlayerBridge) player).bridge$getBukkitEntity().updateInventory();
            cir.setReturnValue(
                (event.useItemInHand() != Event.Result.ALLOW)
                    ? InteractionResult.SUCCESS
                    : InteractionResult.PASS
            );
        }
    }

    /**
     * Replaces the item cooldown check result with the Arclight interact result
     * (from the previously fired interact event) to ensure consistent cancellation.
     */
    @Decorate(
        method = "useItemOn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemCooldowns;isOnCooldown(Lnet/minecraft/world/item/Item;)Z"
        )
    )
    private boolean arclight$useInteractResult(ItemCooldowns cooldowns, Item item) throws Throwable {
        var result = (boolean) DecorationOps.callsite().invoke(cooldowns, item);
        DecorationOps.blackhole().invoke(result);
        return arclight$interactResult;
    }
}