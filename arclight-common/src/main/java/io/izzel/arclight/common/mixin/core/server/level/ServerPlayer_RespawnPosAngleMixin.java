package io.izzel.arclight.common.mixin.core.server.level;

import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin for the inner {@link ServerPlayer.RespawnPosAngle} record that adds
 * Bukkit respawn metadata: bed spawn and respawn anchor spawn flags.
 *
 * <p>These flags are used by Arclight to correctly populate
 * {@link org.bukkit.event.player.PlayerRespawnEvent#isBedSpawn()} and
 * {@link org.bukkit.event.player.PlayerRespawnEvent#isAnchorSpawn()} without
 * needing to analyze the respawn position's block type at event time.</p>
 */
@Mixin(value = ServerPlayer.RespawnPosAngle.class, priority = 1100)
public class ServerPlayer_RespawnPosAngleMixin
        implements ServerPlayerBridge.RespawnPosAngleBridge {

    /** Whether the respawn position is a bed or respawn point. */
    @Unique private boolean arclight$isBedSpawn;

    /** Whether the respawn position is a respawn anchor. */
    @Unique private boolean arclight$isAnchorSpawn;

    @Override
    public boolean bridge$isBedSpawn() {
        return arclight$isBedSpawn;
    }

    @Override
    public boolean bridge$isAnchorSpawn() {
        return arclight$isAnchorSpawn;
    }

    @Override
    public void bridge$setBedSpawn(boolean bedSpawn) {
        this.arclight$isBedSpawn = bedSpawn;
    }

    @Override
    public void bridge$setAnchorSpawn(boolean anchorSpawn) {
        this.arclight$isAnchorSpawn = anchorSpawn;
    }
}