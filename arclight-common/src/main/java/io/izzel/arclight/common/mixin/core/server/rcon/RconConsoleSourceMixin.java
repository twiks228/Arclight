package io.izzel.arclight.common.mixin.core.server.rcon;

import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import io.izzel.arclight.common.bridge.core.server.rcon.RconConsoleSourceBridge;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.rcon.RconConsoleSource;
import org.bukkit.command.CommandSender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mixin for {@link RconConsoleSource} that bridges RCON command execution
 * with Bukkit's command sender system.
 *
 * <p>The RCON console source is used when commands are executed via the
 * remote console protocol. This mixin allows Bukkit plugins to correctly
 * identify and interact with the RCON sender via
 * {@link CommandSourceBridge#bridge$getBukkitSender}.</p>
 *
 * <p>Messages sent to this source are appended to the RCON response buffer
 * rather than being broadcast to players.</p>
 */
@Mixin(value = RconConsoleSource.class, priority = 1100)
public class RconConsoleSourceMixin implements CommandSourceBridge, RconConsoleSourceBridge {

    // @formatter:off
    @Shadow @Final private StringBuffer buffer;
    @Shadow @Final private MinecraftServer server;
    // @formatter:on

    /**
     * Returns the Bukkit remote console sender associated with this RCON session.
     * Retrieved from the server bridge to ensure the correct sender instance is used.
     */
    public CommandSender getBukkitSender() {
        return ((MinecraftServerBridge) this.server).bridge$getRemoteConsole();
    }

    /**
     * Appends a message to the RCON response buffer.
     * The buffered content is sent back to the RCON client after command execution.
     *
     * @param message the message to append to the response buffer
     */
    public void sendMessage(String message) {
        this.buffer.append(message);
    }

    @Override
    public CommandSender bridge$getBukkitSender(CommandSourceStack wrapper) {
        return getBukkitSender();
    }

    @Override
    public void bridge$sendMessage(String message) {
        sendMessage(message);
    }
}