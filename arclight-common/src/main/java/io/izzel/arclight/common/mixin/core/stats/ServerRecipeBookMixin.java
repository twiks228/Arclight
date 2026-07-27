package io.izzel.arclight.common.mixin.core.stats;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.RecipeBook;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Mixin for {@link ServerRecipeBook} that integrates Bukkit recipe unlock events
 * and guards packet sending when the player connection is no longer available.
 */
@Mixin(value = ServerRecipeBook.class, priority = 1100)
public abstract class ServerRecipeBookMixin extends RecipeBook {

    // @formatter:off
    @Shadow protected abstract void sendRecipes(ClientboundRecipePacket.State state, ServerPlayer player, List<ResourceLocation> recipes);
    // @formatter:on

    /**
     * @author IzzelAliz
     * @reason Overwritten to fire Bukkit recipe unlock hooks before recipes are
     * added to the player's recipe book, allowing plugins to cancel unlocks.
     */
    @Overwrite
    public int addRecipes(Collection<RecipeHolder<?>> recipes, ServerPlayer player) {
        List<ResourceLocation> unlockedRecipes = new ArrayList<>();
        int addedCount = 0;

        for (RecipeHolder<?> recipeHolder : recipes) {
            ResourceLocation recipeId = recipeHolder.id();

            if (this.known.contains(recipeId)) {
                continue;
            }

            if (recipeHolder.value().isSpecial()) {
                continue;
            }

            if (!CraftEventFactory.handlePlayerRecipeListUpdateEvent(player, recipeId)) {
                continue;
            }

            this.add(recipeId);
            this.addHighlight(recipeId);
            unlockedRecipes.add(recipeId);
            CriteriaTriggers.RECIPE_UNLOCKED.trigger(player, recipeHolder);
            ++addedCount;
        }

        if (!unlockedRecipes.isEmpty()) {
            this.sendRecipes(ClientboundRecipePacket.State.ADD, player, unlockedRecipes);
        }

        return addedCount;
    }

    /**
     * Prevents recipe packets from being sent to players whose network connection
     * is already gone (e.g. during disconnect race conditions).
     *
     * @param state     the packet state
     * @param player    the target player
     * @param recipesIn the recipes to send
     * @param ci        callback info
     */
    @Inject(
        method = "sendRecipes",
        cancellable = true,
        at = @At("HEAD")
    )
    public void arclight$returnIfFail(
            ClientboundRecipePacket.State state,
            ServerPlayer player,
            List<ResourceLocation> recipesIn,
            CallbackInfo ci
    ) {
        if (player.connection == null) {
            ci.cancel();
        }
    }
}