package io.izzel.arclight.common.mod.server.block;

import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.common.mod.util.remapper.ArclightRemapper;
import net.minecraft.world.CompoundContainer;

import java.lang.reflect.Field;

/**
 * Accesses the hidden {@link CompoundContainer} field inside the anonymous
 * double-chest inventory class ({@code BlockChest$2$1}) via {@code Unsafe}.
 *
 * <p>This is necessary because the class is private and anonymous, making it
 * impossible to reference directly. The remapper is used to obtain the
 * runtime class name across different mappings (MojMap, SRG, etc.).</p>
 */
public final class ChestBlockDoubleInventoryHacks {

    // Utility class — prevent instantiation
    private ChestBlockDoubleInventoryHacks() {}

    private static final Class<?> TARGET_CLASS;
    private static final long FIELD_OFFSET;

    static {
        try {
            // Remap the obfuscated inner class name to the runtime name
            String className = ArclightRemapper
                .getNmsMapper()
                .mapType("net/minecraft/world/level/block/BlockChest$2$1")
                .replace('/', '.');

            TARGET_CLASS = Class.forName(className);

            Field field = TARGET_CLASS.getDeclaredField("inventorylargechest");
            FIELD_OFFSET = Unsafe.objectFieldOffset(field);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                "Failed to locate CompoundContainer field in BlockChest double-inventory class", e
            );
        }
    }

    /**
     * Extracts the {@link CompoundContainer} from a double-chest inventory object.
     *
     * @param obj the anonymous inventory object; must be an instance of the target class
     * @return the underlying {@link CompoundContainer}
     * @throws IllegalArgumentException if obj is not an instance of the target class
     */
    public static CompoundContainer get(Object obj) {
        if (!isInstance(obj)) {
            throw new IllegalArgumentException(
                "Object is not an instance of " + TARGET_CLASS.getName()
            );
        }
        return (CompoundContainer) Unsafe.getObject(obj, FIELD_OFFSET);
    }

    /**
     * Returns {@code true} if the given object is an instance of the
     * anonymous double-chest inventory class.
     *
     * @param obj the object to check
     * @return {@code true} if it is an instance of the target class
     */
    public static boolean isInstance(Object obj) {
        return TARGET_CLASS.isInstance(obj);
    }
}