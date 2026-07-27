package io.izzel.arclight.common.mixin.core.world.level;

import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.bridge.core.world.level.GameRules_TypeBridge;
import io.izzel.arclight.common.bridge.core.world.level.GameRules_ValueBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link GameRules.Value} that replaces the global
 * {@link MinecraftServer}-level callback with per-world callbacks.
 *
 * <p>The original Minecraft implementation calls {@code onChanged(MinecraftServer)}
 * which broadcasts to all worlds. We redirect this to a no-op and instead
 * inject a per-world callback invocation using the command source's level.</p>
 */
@Mixin(value = GameRules.Value.class, priority = 1100)
public abstract class GameRules_ValueMixin<T extends GameRules.Value<T>>
        implements GameRules_ValueBridge<T> {

    @Shadow @Final
    protected GameRules.Type<T> type;

    @Shadow
    protected abstract T getSelf();

    // ── Per-world change notification ─────────────────────────────────────────

    /**
     * Fires the per-world callback for this rule value.
     * Only executes if a non-null {@link ServerLevel} is provided.
     *
     * @param level the level this change is scoped to, or {@code null} for global
     */
    @Unique
    public void onChanged(@Nullable ServerLevel level) {
        if (level != null) {
            ((GameRules_TypeBridge<T>) this.type).arclight$runCallback(level, getSelf());
        }
    }

    /**
     * Abstract method implemented by concrete value subclasses
     * ({@link GameRules_BooleanValueMixin}, {@link GameRules_IntegerValueMixin})
     * to copy the value from {@code source} and fire the per-world callback.
     */
    @Unique
    public abstract void setFrom(T source, @Nullable ServerLevel level);

    @Override
    public void arclight$setFrom(T source, @Nullable ServerLevel level) {
        setFrom(source, level);
    }

    // ── Callback redirect ─────────────────────────────────────────────────────

    /**
     * Suppresses the global {@link MinecraftServer} callback that vanilla fires
     * when a game rule is changed via command. The per-world callback is handled
     * by {@link #arclight$invokeLocalCallback} instead.
     */
    @Redirect(
        method = "setFromArgument",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules$Value;onChanged(Lnet/minecraft/server/MinecraftServer;)V"
        )
    )
    private void arclight$skipGlobalCallback(GameRules.Value<?> instance, MinecraftServer server) {
        // Intentionally suppressed — replaced by arclight$invokeLocalCallback below
    }

    /**
     * Fires the per-world callback using the level from the command source,
     * after the rule value has been updated from the command argument.
     *
     * @param ctx    the command context (provides the executing level)
     * @param string the argument name (unused here)
     * @param ci     injection callback info
     */
    @Inject(
        method = "setFromArgument",
        at = @At("RETURN")
    )
    private void arclight$invokeLocalCallback(
            CommandContext<CommandSourceStack> ctx,
            String string,
            CallbackInfo ci
    ) {
        // Use the level from the command source so multi-world servers only
        // affect the world where the command was executed
        onChanged(ctx.getSource().getLevel());
    }
}