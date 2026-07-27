package io.izzel.arclight.common.mod.server.event;

import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.bridge.core.world.entity.LivingEntityBridge;
import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.mod.server.world.item.EntityDropContainer;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Handles Bukkit entity death events for both players and non-player living entities.
 *
 * <p>Responsible for:</p>
 * <ul>
 *   <li>Firing {@link PlayerDeathEvent} / {@link EntityDeathEvent}</li>
 *   <li>Applying keep-inventory overrides from plugin event handlers</li>
 *   <li>Converting NMS item entity drops to Bukkit {@link ItemStack} lists</li>
 * </ul>
 */
public final class EntityEventHandler {

    // Utility class — prevent instantiation
    private EntityEventHandler() {}

    /**
     * Monitors and processes living entity death drops, firing the appropriate
     * Bukkit death event and applying plugin modifications to the drop list.
     *
     * @param living      the entity that died
     * @param source      the damage source that caused death; falls back to
     *                    {@code genericKill} if {@code null}
     * @param drops       mutable list of item entities to be dropped; may be modified
     *                    by event handlers
     * @param isCancelled whether the death drop was pre-cancelled (e.g., by earlier logic)
     * @return {@code true} if the drop list is empty after event processing
     *         (indicating no drops should be spawned)
     */
    public static boolean monitorLivingDrops(
            LivingEntity living,
            DamageSource source,
            List<ItemEntity> drops,
            boolean isCancelled
    ) {
        if (!(living instanceof LivingEntityBridge bridge)) {
            return false;
        }

        // Fallback to a generic kill source if none provided
        if (source == null) {
            source = living.damageSources().genericKill();
        }

        // Pre-cancelled death clears drops entirely (e.g., from mod hooks)
        if (isCancelled) {
            drops.clear();
        }

        if (living instanceof ServerPlayer player) {
            handlePlayerDeath(player, source, drops, bridge);
        } else {
            handleEntityDeath(living, source, drops, bridge);
        }

        return drops.isEmpty();
    }

    // ── Player death handling ─────────────────────────────────────────────────

    private static void handlePlayerDeath(
            ServerPlayer player,
            DamageSource source,
            List<ItemEntity> drops,
            LivingEntityBridge bridge
    ) {
        String deathMessage = player.getCombatTracker().getDeathMessage().getString();
        Inventory beforeDeath = ArclightCaptures.getDeathPlayerInv();
        Inventory restoredInventory = null;

        // If the game rule says "no keep-inventory", capture the inventory before death
        // so plugins can override keepInventory back to true
        if (beforeDeath != null) {
            restoredInventory = new Inventory(player);
            restoredInventory.replaceWith(player.getInventory());
            player.getInventory().replaceWith(beforeDeath);
        }

        int expReward = bridge.bridge$getExpReward(source.getEntity());

        final PlayerDeathEvent event;
        try (final var container = new EntityDropContainer()) {
            List<ItemStack> loot = container.initDecorate(drops);
            event = ArclightEventFactory.callPlayerDeathEvent(
                player, source, loot, expReward,
                deathMessage,
                restoredInventory == null // keepInventory = true if we didn't capture it
            );
            if (event.getKeepInventory()) {
                restoredInventory = null;
            }
            container.convert(loot, drops, bridge::arclight$spawnAtLocationNoAdd);
        }

        if (beforeDeath != null) {
            if (restoredInventory == null) {
                // Plugin overrode keepInventory from false to true
                ArclightServer.LOGGER.debug(
                    "Overriding keepInventory from false to true. " +
                    "Preserving modified inventory before death."
                );
            } else {
                // keepInventory stays false: restore original inventory state
                player.getInventory().replaceWith(restoredInventory);
            }
        } else if (!event.getKeepInventory()) {
            // Plugin tried to override keepInventory from true to false:
            // this is unsupported because inventory contents are already dropped
            ArclightServer.LOGGER.warn(
                "Overriding keepInventory from true to false. This won't take effect."
            );
        }

        ((ServerPlayerBridge) player).arclight$readDeathEvent(event);
    }

    // ── Non-player living entity death handling ───────────────────────────────

    private static void handleEntityDeath(
            LivingEntity living,
            DamageSource source,
            List<ItemEntity> drops,
            LivingEntityBridge bridge
    ) {
        // Add any extra drops captured by mod hooks before the event
        var extraDrops = ArclightCaptures.consumeExtraDrops();
        if (extraDrops != null) {
            drops.addAll(extraDrops);
        }

        final EntityDeathEvent event;
        try (final var container = new EntityDropContainer()) {
            List<ItemStack> itemStackList = container.initDecorate(drops);
            event = ArclightEventFactory.callEntityDeathEvent(living, source, itemStackList);
            container.convert(itemStackList, drops, ((EntityBridge) living)::arclight$spawnAtLocationNoAdd);
        }

        bridge.bridge$setExpToDrop(event.getDroppedExp());
    }
}