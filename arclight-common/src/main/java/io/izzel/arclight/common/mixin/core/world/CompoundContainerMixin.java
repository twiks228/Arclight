package io.izzel.arclight.common.mixin.core.world;

import io.izzel.arclight.common.bridge.core.world.IInventoryBridge;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixin for {@link CompoundContainer} (double chest inventory) that integrates
 * Bukkit's {@link IInventoryBridge} viewer tracking and inventory metadata.
 *
 * <p>A CompoundContainer wraps two side-by-side containers (e.g., two chest halves).
 * Open/close events must be forwarded to both sub-containers so their internal
 * viewer lists stay consistent, while this mixin maintains its own combined list.</p>
 */
@Mixin(value = CompoundContainer.class, priority = 1100)
public abstract class CompoundContainerMixin implements IInventoryBridge, Container {

    // @formatter:off
    @Shadow @Final public Container container1;
    @Shadow @Final public Container container2;
    // @formatter:on

    /**
     * Tracks which players currently have this double-chest open.
     * Managed by {@link #onOpen} and {@link #onClose}.
     */
    private final List<HumanEntity> transactions = new ArrayList<>();

    // ── IInventoryBridge implementation ───────────────────────────────────────

    /**
     * Returns all item stacks from both sub-containers as a single flat list.
     * The list is a snapshot — mutations do not affect the inventory.
     */
    @Override
    public List<ItemStack> getContents() {
        int size = this.getContainerSize();
        List<ItemStack> contents = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            contents.add(this.getItem(i));
        }
        return contents;
    }

    /**
     * Notifies both sub-containers that a player has opened this inventory
     * and adds the player to the combined viewer list.
     *
     * @param who the player opening the inventory
     */
    @Override
    public void onOpen(CraftHumanEntity who) {
        ((IInventoryBridge) this.container1).onOpen(who);
        ((IInventoryBridge) this.container2).onOpen(who);
        this.transactions.add(who);
    }

    /**
     * Notifies both sub-containers that a player has closed this inventory
     * and removes the player from the combined viewer list.
     *
     * @param who the player closing the inventory
     */
    @Override
    public void onClose(CraftHumanEntity who) {
        ((IInventoryBridge) this.container1).onClose(who);
        ((IInventoryBridge) this.container2).onClose(who);
        this.transactions.remove(who);
    }

    @Override
    public List<HumanEntity> getViewers() {
        return transactions;
    }

    /**
     * Double-chest inventories are not owned by a specific block entity;
     * ownership is determined by the individual chest halves.
     */
    @Override
    public InventoryHolder getOwner() {
        return null;
    }

    @Override
    public void setOwner(InventoryHolder owner) {
        // No-op: double-chest ownership is determined by its sub-containers
    }

    /**
     * Returns the most restrictive max stack size between the two sub-containers.
     * This ensures items cannot exceed either container's individual limit.
     */
    @Override
    public int getMaxStackSize() {
        return Math.min(this.container1.getMaxStackSize(), this.container2.getMaxStackSize());
    }

    /**
     * Propagates the max stack size to both sub-containers.
     *
     * @param size the new maximum stack size
     */
    @Override
    public void setMaxStackSize(int size) {
        ((IInventoryBridge) this.container1).setMaxStackSize(size);
        ((IInventoryBridge) this.container2).setMaxStackSize(size);
    }

    /**
     * Returns the location of the first sub-container (the left chest half).
     * Used by Bukkit to determine the inventory's world position.
     */
    @Override
    public Location getLocation() {
        return ((IInventoryBridge) this.container1).getLocation();
    }

    /**
     * Double-chest inventories do not track the last used crafting recipe.
     */
    @Override
    public RecipeHolder<?> getCurrentRecipe() {
        return null;
    }

    @Override
    public void setCurrentRecipe(RecipeHolder<?> recipe) {
        // No-op: double-chests do not support recipe tracking
    }
}