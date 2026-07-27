package io.izzel.arclight.common.mixin.core.world;

import io.izzel.arclight.common.bridge.core.world.IInventoryBridge;
import io.izzel.arclight.common.mod.inventory.SideViewingTracker;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Mixin for the {@link Container} interface that provides default implementations
 * of {@link IInventoryBridge} methods for all containers.
 *
 * <p>Uses {@link SideViewingTracker} (a global weak-reference map) to track
 * which players are viewing each container, avoiding the need for an instance
 * field on every container implementation.</p>
 *
 * <p>Specific containers that require custom behavior (e.g., {@link CompoundContainerMixin})
 * override these defaults by providing their own {@link IInventoryBridge} implementation.</p>
 */
@Mixin(value = Container.class, priority = 1100)
public interface ContainerMixin extends IInventoryBridge {

    /**
     * Registers a player as viewing this container.
     * Delegates to {@link SideViewingTracker} for thread-safe tracking.
     *
     * @param who the player opening the container
     */
    @Override
    default void onOpen(CraftHumanEntity who) {
        SideViewingTracker.onOpen((Container) this, who);
    }

    /**
     * Unregisters a player from viewing this container.
     * Delegates to {@link SideViewingTracker} for thread-safe tracking.
     *
     * @param who the player closing the container
     */
    @Override
    default void onClose(CraftHumanEntity who) {
        SideViewingTracker.onClose((Container) this, who);
    }

    /**
     * Returns all players currently viewing this container.
     * The returned list is backed by {@link SideViewingTracker} and is
     * safe for iteration during concurrent modifications (uses CopyOnWriteArrayList).
     */
    @Override
    default List<HumanEntity> getViewers() {
        return SideViewingTracker.getViewers((Container) this);
    }

    /**
     * Default containers have no Bukkit owner.
     * Override in specific container implementations to return the owning tile entity.
     */
    @Override
    default InventoryHolder getOwner() {
        return null;
    }

    /**
     * Default no-op: most containers do not support dynamic max stack size changes.
     * Override in specific implementations (e.g., player inventory) if needed.
     *
     * @param size the new maximum stack size
     */
    @Override
    default void setMaxStackSize(int size) {
        // No-op by default; override in implementations that support it
    }

    /**
     * Default containers have no world location.
     * Override in block entity containers to return their block's location.
     */
    @Override
    default Location getLocation() {
        return null;
    }

    /**
     * Default containers do not track the last used crafting recipe.
     */
    @Override
    default RecipeHolder<?> getCurrentRecipe() {
        return null;
    }

    @Override
    default void setCurrentRecipe(RecipeHolder<?> recipe) {
        // No-op by default
    }
}