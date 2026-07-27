package io.izzel.arclight.common.mixin.core.world.level;

import io.izzel.arclight.common.bridge.core.world.level.GameRulesBridge;
import io.izzel.arclight.common.bridge.core.world.level.GameRules_TypeBridge;
import io.izzel.arclight.common.bridge.core.world.level.GameRules_ValueBridge;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Set;

/**
 * Mixin for {@link GameRules} that adds per-world game rule callbacks and
 * exposes the internal rules map via {@link GameRulesBridge}.
 *
 * <p>Vanilla game rules use a global callback that fires for all worlds.
 * This mixin replaces that with per-world callbacks so that, e.g.,
 * changing {@code reducedDebugInfo} in the Nether only affects Nether players.</p>
 */
@Mixin(value = GameRules.class, priority = 1100)
public abstract class GameRulesMixin implements GameRulesBridge {

    @Shadow @Final
    private Map<GameRules.Key<?>, GameRules.Value<?>> rules;

    @Shadow
    public abstract <T extends GameRules.Value<T>> T getRule(GameRules.Key<T> key);

    // ── Per-world rule assignment ─────────────────────────────────────────────

    /**
     * Copies all game rule values from {@code source} into this {@link GameRules} instance,
     * notifying per-world callbacks for each changed rule.
     *
     * @param source the game rules to copy from
     * @param level  the world this copy is targeted at (for per-world callbacks)
     */
    @Unique
    public void assignFrom(GameRules source, @Nullable ServerLevel level) {
        ((GameRulesBridge) source).arclight$getAllRules()
            .forEach(key -> assignCap(key, source, level));
    }

    /**
     * Type-safe helper that copies a single game rule value and triggers its callback.
     */
    @Unique
    private <T extends GameRules.Value<T>> void assignCap(
            GameRules.Key<T> key,
            GameRules source,
            @Nullable ServerLevel level
    ) {
        T sourceValue = source.getRule(key);
        ((GameRules_ValueBridge<T>) this.getRule(key)).arclight$setFrom(sourceValue, level);
    }

    @Override
    public Set<GameRules.Key<?>> arclight$getAllRules() {
        return rules.keySet();
    }

    // ── Per-world callback registration ──────────────────────────────────────

    /**
     * Injects per-world callbacks for vanilla game rules that need to send
     * packets to players when their value changes.
     *
     * <p>Callbacks are stored on the {@link GameRules.Type} object and invoked
     * by {@link GameRules_ValueMixin#arclight$invokeLocalCallback} when a rule
     * is changed via a command.</p>
     *
     * <p>Supported rules:</p>
     * <ul>
     *   <li>{@code reducedDebugInfo} — sends entity event packet 22/23</li>
     *   <li>{@code doLimitedCrafting} — sends game event for recipe book</li>
     *   <li>{@code doImmediateRespawn} — sends game event for respawn screen</li>
     *   <li>{@code spawnChunkRadius} — updates the default spawn position</li>
     * </ul>
     */
    @Inject(
        method = "register",
        at = @At("HEAD")
    )
    private static <T extends GameRules.Value<T>> void arclight$initPerWorldCallback(
            String name,
            GameRules.Category category,
            GameRules.Type<T> type,
            CallbackInfoReturnable<GameRules.Key<T>> cir
    ) {
        GameRules_TypeBridge<T> bridge = (GameRules_TypeBridge<T>) type;

        switch (name) {
            case "reducedDebugInfo" -> bridge.arclight$setPerWorldCallback((level, rule) -> {
                boolean reduced = ((GameRules.BooleanValue) rule).get();
                // Packet 22 = enable reduced debug info, 23 = disable
                byte eventId = reduced ? (byte) 22 : (byte) 23;
                for (ServerPlayer player : level.players()) {
                    player.connection.send(new ClientboundEntityEventPacket(player, eventId));
                }
            });

            case "doLimitedCrafting" -> bridge.arclight$setPerWorldCallback((level, rule) -> {
                boolean limited = ((GameRules.BooleanValue) rule).get();
                float value = limited ? 1.0F : 0.0F;
                for (ServerPlayer player : level.players()) {
                    player.connection.send(new ClientboundGameEventPacket(
                        ClientboundGameEventPacket.LIMITED_CRAFTING, value
                    ));
                }
            });

            case "doImmediateRespawn" -> bridge.arclight$setPerWorldCallback((level, rule) -> {
                boolean immediate = ((GameRules.BooleanValue) rule).get();
                float value = immediate ? 1.0F : 0.0F;
                for (ServerPlayer player : level.players()) {
                    player.connection.send(new ClientboundGameEventPacket(
                        ClientboundGameEventPacket.IMMEDIATE_RESPAWN, value
                    ));
                }
            });

            case "spawnChunkRadius" -> bridge.arclight$setPerWorldCallback((level, rule) ->
                level.setDefaultSpawnPos(
                    level.getSharedSpawnPos(),
                    level.getSharedSpawnAngle()
                )
            );
        }
    }
}