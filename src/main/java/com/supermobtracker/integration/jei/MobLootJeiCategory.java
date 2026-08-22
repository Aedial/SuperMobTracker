package com.supermobtracker.integration.jei;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.client.resources.I18n;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.supermobtracker.Tags;


/** JEI shell for the locally dumped mob loot data. */
public class MobLootJeiCategory implements IRecipeCategory<MobLootJeiRecipe>, IRecipeCategoryWithOverlay {
    public static final String UID = "supermobtracker.mob_loot";

    private final IGuiHelper guiHelper;
    private final IDrawable slotDrawable;
    private IDrawable background;
    private int backgroundHeight;
    private final IDrawable icon;

    public MobLootJeiCategory(IGuiHelper guiHelper) {
        this.guiHelper = guiHelper;
        this.slotDrawable = guiHelper.getSlotDrawable();
        this.icon = guiHelper.createDrawableIngredient(new ItemStack(Items.ROTTEN_FLESH));
        this.backgroundHeight = -1;
        refreshBackground();
    }

    @Override
    @Nonnull
    public String getUid() {
        return UID;
    }

    @Override
    @Nonnull
    public String getTitle() {
        return I18n.format("jei.category.supermobtracker.mob_loot");
    }

    @Override
    @Nonnull
    public String getModName() {
        return Tags.MODNAME;
    }

    @Override
    @Nonnull
    public IDrawable getBackground() {
        refreshBackground();
        return background;
    }

    @Override
    @Nullable
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(@Nonnull IRecipeLayout recipeLayout, @Nonnull MobLootJeiRecipe recipe,
            @Nonnull IIngredients ingredients) {
        IGuiItemStackGroup itemStacks = recipeLayout.getItemStacks();
        recipe.bindJeiLayout(slotDrawable, itemStacks);
    }

    @Override
    public void drawOverlay(Minecraft minecraft, int offsetX, int offsetY, int mouseX, int mouseY,
            IRecipeWrapper recipeWrapper) {
        if (!(recipeWrapper instanceof MobLootJeiRecipe)) return;

        ((MobLootJeiRecipe) recipeWrapper).drawOverlay(minecraft, offsetX, offsetY);
    }

    private void refreshBackground() {
        int height = MobLootJeiRecipe.getHeight();
        if (backgroundHeight == height) return;

        background = guiHelper.createBlankDrawable(MobLootJeiRecipe.WIDTH, height);
        backgroundHeight = height;
    }
}
