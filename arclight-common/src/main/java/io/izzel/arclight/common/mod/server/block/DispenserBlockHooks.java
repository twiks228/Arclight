package io.izzel.arclight.common.mod.server.block;

import net.minecraft.world.level.block.DispenserBlock;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Provides access to the package-private {@code eventFired} field of
 * {@link DispenserBlock} using a {@link VarHandle}.
 *
 * <p>{@code eventFired} is a static flag set by Bukkit's dispenser event
 * handling to indicate whether the {@code BlockDispenseEvent} has already
 * been fired for the current dispense cycle. It prevents double-firing
 * when multiple dispenser hooks are active.</p>
 */
public final class DispenserBlockHooks {

    // Utility class — prevent instantiation
    private DispenserBlockHooks() {}

    private static final VarHandle H_EVENT_FIRED;

    static {
        try {
            var field = DispenserBlock.class.getDeclaredField("eventFired");
            field.setAccessible(true);
            H_EVENT_FIRED = MethodHandles.lookup().unreflectVarHandle(field);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Returns whether the dispenser event has already been fired
     * in the current dispense cycle.
     *
     * @return {@code true} if the event was fired
     */
    public static boolean isEventFired() {
        return (boolean) H_EVENT_FIRED.get();
    }

    /**
     * Sets the event-fired flag for the current dispense cycle.
     * Must be reset to {@code false} after each dispense operation.
     *
     * @param b the new flag value
     */
    public static void setEventFired(boolean b) {
        H_EVENT_FIRED.set(b);
    }
}