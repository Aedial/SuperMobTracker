package com.supermobtracker.integration.jei;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import mezz.jei.api.recipe.IRecipeWrapper;


/**
 * Marker for categories that need to draw above JEI's item stacks.
 */
@SideOnly(Side.CLIENT)
public interface IRecipeCategoryWithOverlay {
    void drawOverlay(Minecraft minecraft, int offsetX, int offsetY, int mouseX, int mouseY,
            IRecipeWrapper recipeWrapper);
}
