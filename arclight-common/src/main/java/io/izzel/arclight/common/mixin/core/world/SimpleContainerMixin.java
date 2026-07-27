package io.izzel.arclight.common.mixin.core.world;

import io.izzel.arclight.common.bridge.core.world.IInventoryBridge;
import io.izzel.arclight.common.mod.mixins.annotation.CreateConstructor;
import io.izzel.arclight.common.mod.mixins.annotation.ShadowConstructor;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
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
 * Mixin for {@link SimpleContainer} that integrates Bukkit's inventory API.
 *
 * <p>{@link SimpleContainer} is used for many in-game inventories that don't
 * belong to a specific block entity (e.g., crafting result slots, loot table
 * containers, villager trade inventories).</p>
 *
 * <p>This mixin adds:</p>
 * <ul>
 *   <li>An optional {@link InventoryHolder} for Bukkit ownership queries</li>
 *   <li>A per-instance viewer list for open/close tracking</li>
 *   <li>A configurable max stack size (falls back to {@link Container#MAX_STACK})</li>
 *   <li>An additional constructor that accepts an {@link InventoryHolder}</li>
 * </ul>
 */
@Mixin(value = SimpleContainer.class, priority = 1100)
public abstract class SimpleContainerMixin implements Container, IInventoryBridge {

    // @formatter:off
    @Shadow @Final public NonNullList<ItemStack> items;
    // @formatter:on

    /** Players currently viewing this container. */
    public List<HumanEntity> transaction = new ArrayList<>();

    /**
     * Per-instance max stack size override.
     * Initialized to 0 as a sentinel; the first call to {@link #getMaxStackSize()}
     * sets it to {@link Container#MAX_STACK} if still 0.
     */
    private int maxStack = MAX_STACK;

    /** Optional Bukkit inventory owner (e.g., for plugin-created inventories). */
    protected InventoryHolder bukkitOwner;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Shadow of the original {@link SimpleContainer} constructor.
     *
     * @param numSlots the number of inventory slots
     */
    @ShadowConstructor
    public void arclight$constructor(int numSlots) {
        throw new RuntimeException("Shadow constructor stub; should not be called directly.");
    }

    /**
     * Additional constructor that creates a {@link SimpleContainer} with a Bukkit owner.
     * Used when plugins create inventories via the Bukkit API.
     *
     * @param numSlots the number of inventory slots
     * @param owner    the Bukkit inventory owner
     */
    @CreateConstructor
    public void arclight$constructor(int numSlots, InventoryHolder owner) {
        this.arclight$constructor(numSlots);
        this.bukkitOwner = owner;
    }

    // ── IInventoryBridge implementation ───────────────────────────────────────

    /**
     * Returns the backing item stack list directly.
     * Changes to the returned list are reflected in the inventory.
     */
    @Override
    public List<ItemStack> getContents() {
        return this.items;
    }

    @Override
    public void onOpen(CraftHumanEntity who) {
        transaction.add(who);
    }

    @Override
    public void onClose(CraftHumanEntity who) {
        transaction.remove(who);
    }

    @Override
    public List<HumanEntity> getViewers() {
        return transaction;
    }

    @Override
    public InventoryHolder getOwner() {
        return bukkitOwner;
    }

    @Override
    public void setOwner(InventoryHolder owner) {
        this.bukkitOwner = owner;
    }

    /**
     * Returns the effective max stack size.
     *
     * <p>Guards against an edge case where {@code maxStack} was set to 0
     * (which can happen via {@link #setMaxStackSize(int)} with a 0 argument),
     * by falling back to the default {@link Container#MAX_STACK}.</p>
     */
    @Override
    public int getMaxStackSize() {
        if (maxStack == 0) {
            maxStack = MAX_STACK;
        }
        return maxStack;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    /**
     * {@link SimpleContainer} instances are not associated with a world position.
     */
    @Override
    public Location getLocation() {
        return null;
    }

    /**
     * {@link SimpleContainer} does not track crafting recipes.
     */
    @Override
    public RecipeHolder<?> getCurrentRecipe() {
        return null;
    }

    @Override
    public void setCurrentRecipe(RecipeHolder<?> recipe) {
        // No-op: SimpleContainer does not support recipe tracking
    }
}