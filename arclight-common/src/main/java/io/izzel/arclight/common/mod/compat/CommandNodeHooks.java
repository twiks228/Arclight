package io.izzel.arclight.common.mod.compat;

import com.mojang.brigadier.tree.CommandNode;
import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.common.bridge.core.commands.CommandSourceStackBridge;

import java.util.Map;

/**
 * Utility hooks for Brigadier CommandNode manipulation using Unsafe.
 * Allows deep modification of command trees for plugin/mod command bridging.
 */
public class CommandNodeHooks {

    private static final long CHILDREN;
    private static final long LITERALS;
    private static final long ARGUMENTS;
    
    private static final Object CURRENT_BASE;
    private static final long CURRENT;

    static {
        try {
            CHILDREN = Unsafe.objectFieldOffset(CommandNode.class.getDeclaredField("children"));
            LITERALS = Unsafe.objectFieldOffset(CommandNode.class.getDeclaredField("literals"));
            ARGUMENTS = Unsafe.objectFieldOffset(CommandNode.class.getDeclaredField("arguments"));
            CURRENT_BASE = Unsafe.staticFieldBase(CommandNode.class.getDeclaredField("CURRENT_COMMAND"));
            CURRENT = Unsafe.staticFieldOffset(CommandNode.class.getDeclaredField("CURRENT_COMMAND"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to initialize CommandNode Unsafe hooks", e);
        }
    }

    /**
     * Forcibly removes a command from a Brigadier CommandNode.
     * Bypasses standard encapsulation for dynamic unregistration.
     */
    @SuppressWarnings("unchecked")
    public static void removeCommand(CommandNode<?> node, String command) {
        ((Map<String, ?>) Unsafe.getObject(node, CHILDREN)).remove(command);
        ((Map<String, ?>) Unsafe.getObject(node, LITERALS)).remove(command);
        ((Map<String, ?>) Unsafe.getObject(node, ARGUMENTS)).remove(command);
    }

    /**
     * Retrieves the current command node being executed.
     */
    public static CommandNode<?> getCurrent() {
        return (CommandNode<?>) Unsafe.getObjectVolatile(CURRENT_BASE, CURRENT);
    }

    /**
     * Checks if the given source can use the specified command node.
     * Applies Arclight's command bridge tracking if applicable.
     */
    public static <S> boolean canUse(CommandNode<S> node, S source) {
        if (source instanceof CommandSourceStackBridge bridge) {
            try {
                bridge.bridge$setCurrentCommand(node);
                return node.canUse(source);
            } finally {
                bridge.bridge$setCurrentCommand(null);
            }
        } else {
            return node.canUse(source);
        }
    }
}