package com.supermobtracker.mixin.jei;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.gui.recipes.RecipeLayout;

import com.supermobtracker.integration.jei.IRecipeCategoryWithOverlay;


/** Invokes category overlays after JEI draws item ingredients. */
@Mixin(value = RecipeLayout.class, remap = false)
public class MixinRecipeLayout {
    @Shadow @Final private IRecipeCategory<?> recipeCategory;
    @Shadow @Final private IRecipeWrapper recipeWrapper;
    @Shadow private int posX;
    @Shadow private int posY;

    @Inject(method = "drawRecipe", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GlStateManager;disableBlend()V",
        remap = true
    ))
    private void supermobtracker$drawOverlay(Minecraft minecraft, int mouseX, int mouseY, CallbackInfo callback) {
        if (recipeCategory instanceof IRecipeCategoryWithOverlay) {
            ((IRecipeCategoryWithOverlay) recipeCategory)
                .drawOverlay(minecraft, posX, posY, mouseX, mouseY, recipeWrapper);
        }
    }
}
