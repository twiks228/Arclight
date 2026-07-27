package io.izzel.arclight.common.mixin.core.world.level;

import com.google.common.base.Joiner;
import io.izzel.arclight.common.bridge.core.commands.CommandSourceStackBridge;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.level.BaseCommandBlock;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.event.server.ServerCommandEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mixin for {@link BaseCommandBlock} that fires {@link ServerCommandEvent}
 * and applies Bukkit command block security restrictions.
 *
 * <p>The following vanilla commands are blocked from being executed by
 * command blocks for security reasons (they can be used to grant unauthorized
 * privileges or disrupt the server):</p>
 * <ul>
 *   <li>{@code /stop}, {@code /kick}, {@code /op}, {@code /deop}</li>
 *   <li>{@code /ban}, {@code /ban-ip}, {@code /pardon}, {@code /pardon-ip}</li>
 *   <li>{@code /reload}</li>
 * </ul>
 *
 * <p>Also applies the {@code commandBlockOverride} setting from Bukkit's server
 * config to prefix allowed commands with the {@code minecraft:} namespace,
 * ensuring they bypass Bukkit's command routing when configured.</p>
 */
@Mixin(value = BaseCommandBlock.class, priority = 1100)
public class BaseCommandBlockMixin {

    /** Used to rejoin command arguments after modification. */
    private static final Joiner SPACE_JOINER = Joiner.on(" ");

    /**
     * Intercepts the command execution from a command block and:
     * <ol>
     *   <li>Strips the leading {@code /} if present (command blocks sometimes include it)</li>
     *   <li>Fires {@link ServerCommandEvent} — allows plugins to modify or cancel the command</li>
     *   <li>Blocks restricted commands (stop, kick, op, ban, etc.)</li>
     *   <li>Applies namespace override from Bukkit's command block config</li>
     * </ol>
     *
     * @param commands the command dispatcher
     * @param sender   the command source (the command block's source stack)
     * @param command  the raw command string to execute
     */
    @Decorate(
        method = "performCommand",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;performPrefixedCommand(" +
                     "Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V"
        )
    )
    private void arclight$serverCommand(
            Commands commands,
            CommandSourceStack sender,
            String command
    ) throws Throwable {
        // Strip leading slash if present (some command blocks include it)
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        // Fire ServerCommandEvent so plugins can modify or cancel it
        ServerCommandEvent event = new ServerCommandEvent(
            ((CommandSourceStackBridge) sender).bridge$getBukkitSender(),
            command
        );
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            // Plugin cancelled the command — do not execute
            return;
        }

        command = event.getCommand();
        String[] args = command.split(" ");
        String cmd = args[0];

        // Strip namespace prefix for restriction checks
        if (cmd.startsWith("minecraft:")) cmd = cmd.substring("minecraft:".length());
        if (cmd.startsWith("bukkit:"))    cmd = cmd.substring("bukkit:".length());

        // Block dangerous/administrative commands from command blocks
        if (isRestrictedCommand(cmd)) {
            return;
        }

        // Apply commandBlockOverride: prefix with minecraft: if configured
        if (((CraftServer) Bukkit.getServer()).getCommandBlockOverride(args[0])) {
            args[0] = "minecraft:" + args[0];
        }

        DecorationOps.callsite().invoke(commands, sender, SPACE_JOINER.join(args));
    }

    /**
     * Returns {@code true} if the given command name is restricted from
     * being executed by command blocks for security reasons.
     *
     * @param cmd the command name (without namespace prefix)
     * @return {@code true} if the command is blocked
     */
    private static boolean isRestrictedCommand(String cmd) {
        return switch (cmd.toLowerCase()) {
            case "stop", "kick", "op", "deop",
                 "ban", "ban-ip", "pardon", "pardon-ip",
                 "reload" -> true;
            default -> false;
        };
    }
}