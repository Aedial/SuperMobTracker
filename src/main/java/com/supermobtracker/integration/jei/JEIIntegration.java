package com.supermobtracker.integration.jei;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import net.minecraft.util.ResourceLocation;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;

import com.supermobtracker.drops.LootDump;
import com.supermobtracker.drops.LootDump.MobLoot;


/**
 * JEI plugin that stores the runtime for later use by JEIHelper.
 */
@JEIPlugin
public class JEIIntegration implements IModPlugin {
    private static IJeiRuntime runtime = null;
    private static final Map<ResourceLocation, MobLootJeiRecipe> registeredMobLootRecipes = new LinkedHashMap<>();

    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        registry.addRecipeCategories(new MobLootJeiCategory(registry.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void register(IModRegistry registry) {
        // Load the dump's mob & item indexes once, at JEI initialization
        LootDump.getMobs();
        registry.addRecipes(rebuildMobLootRecipes(), MobLootJeiCategory.UID);
        registry.addRecipeRegistryPlugin(new MobLootJeiRegistryPlugin());
    }

    @Override
    public void onRuntimeAvailable(@Nonnull IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    public static boolean isRuntimeAvailable() {
        return runtime != null;
    }

    @SuppressWarnings("deprecation")
    public static void refreshMobLootRecipes() {
        Map<ResourceLocation, MobLootJeiRecipe> previousRecipes = new LinkedHashMap<>(registeredMobLootRecipes);
        List<MobLootJeiRecipe> currentRecipes = rebuildMobLootRecipes();
        if (runtime == null) return;

        IRecipeRegistry recipeRegistry = runtime.getRecipeRegistry();
        for (MobLootJeiRecipe previousRecipe : previousRecipes.values()) {
            recipeRegistry.removeRecipe(previousRecipe, MobLootJeiCategory.UID);
        }

        for (MobLootJeiRecipe currentRecipe : currentRecipes) {
            recipeRegistry.addRecipe(currentRecipe, MobLootJeiCategory.UID);
        }
    }

    private static List<MobLootJeiRecipe> rebuildMobLootRecipes() {
        registeredMobLootRecipes.clear();
        for (MobLoot mob : LootDump.getMobs()) {
            registeredMobLootRecipes.put(mob.entityId, new MobLootJeiRecipe(mob.entityId));
        }

        return new ArrayList<>(registeredMobLootRecipes.values());
    }
}
