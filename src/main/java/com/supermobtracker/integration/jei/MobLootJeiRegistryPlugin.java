package com.supermobtracker.integration.jei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeRegistryPlugin;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.supermobtracker.drops.LootDump;
import com.supermobtracker.drops.LootDump.MobLoot;


/**
 * Exposes the immutable dump indexes to JEI. The dump command invalidates and rebuilds
 * those indexes after it finishes, so the user can view the new data in JEI without
 * restarting the game.
 */
public class MobLootJeiRegistryPlugin implements IRecipeRegistryPlugin {
    @Override
    @Nonnull
    public <V> List<String> getRecipeCategoryUids(@Nonnull IFocus<V> focus) {
        if (!(focus.getValue() instanceof ItemStack)) return Collections.emptyList();

        ItemStack stack = (ItemStack) focus.getValue();

        if (focus.getMode() == IFocus.Mode.INPUT && MobLootJeiRecipe.isAnchorStack(stack)) {
            ResourceLocation entityId = MobLootJeiRecipe.getAnchorEntityId(stack);
            return LootDump.getMob(entityId) != null
                ? Collections.singletonList(MobLootJeiCategory.UID)
                : Collections.emptyList();
        }

        return Collections.emptyList();
    }

    @Override
    @Nonnull
    public <T extends IRecipeWrapper, V> List<T> getRecipeWrappers(@Nonnull IRecipeCategory<T> recipeCategory,
            @Nonnull IFocus<V> focus) {
        if (!MobLootJeiCategory.UID.equals(recipeCategory.getUid())) return Collections.emptyList();
        if (!(focus.getValue() instanceof ItemStack)) return Collections.emptyList();

        ItemStack stack = (ItemStack) focus.getValue();

        if (focus.getMode() == IFocus.Mode.INPUT && MobLootJeiRecipe.isAnchorStack(stack)) {
            ResourceLocation entityId = MobLootJeiRecipe.getAnchorEntityId(stack);
            return wrapOne(LootDump.getMob(entityId));
        }

        return Collections.emptyList();
    }

    @Override
    @Nonnull
    public <T extends IRecipeWrapper> List<T> getRecipeWrappers(@Nonnull IRecipeCategory<T> recipeCategory) {
        return Collections.emptyList();
    }

    @Nonnull
    private static <T extends IRecipeWrapper> List<T> wrapOne(MobLoot mob) {
        if (mob == null) return Collections.emptyList();
        return Collections.singletonList(cast(new MobLootJeiRecipe(mob.entityId)));
    }

    @Nonnull
    private static <T extends IRecipeWrapper> List<T> wrapAll(List<MobLoot> mobs) {
        if (mobs.isEmpty()) return Collections.emptyList();

        List<T> wrappers = new ArrayList<>(mobs.size());
        for (MobLoot mob : mobs) wrappers.add(cast(new MobLootJeiRecipe(mob.entityId)));
        return wrappers;
    }

    @SuppressWarnings("unchecked")
    private static <T extends IRecipeWrapper> T cast(MobLootJeiRecipe recipe) {
        return (T) recipe;
    }
}
