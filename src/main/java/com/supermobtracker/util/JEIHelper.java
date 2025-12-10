package com.supermobtracker.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.IRecipesGui;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.integration.JEIIntegration;


/**
 * Helper class for JEI integration.
 */
public class JEIHelper {
    private static Boolean jeiLoaded = null;
    private static Boolean jerLoaded = null;

    private static final String JER_MOB_CATEGORY = "jeresources.mob";

    private static Map<ResourceLocation, Integer> mobIndexCache = null;

    public static boolean isJEILoaded() {
        if (jeiLoaded == null) jeiLoaded = Loader.isModLoaded("jei");

        return jeiLoaded;
    }

    public static boolean isJERLoaded() {
        if (jerLoaded == null) jerLoaded = Loader.isModLoaded("jeresources");

        return jerLoaded;
    }

    /**
     * Opens JEI to show the mob drops page for a specific entity.
     * Returns true if successfully opened, false otherwise.
     */
    public static boolean showMobPage(ResourceLocation entityId) {
        int mobIndex = getMobIndex(entityId);
        if (mobIndex < 0) return false;

        IJeiRuntime runtime = JEIIntegration.getRuntime();
        IRecipesGui recipesGui = runtime.getRecipesGui();

        try {
            // Open the mob category
            recipesGui.showCategories(Collections.singletonList(JER_MOB_CATEGORY));

            // Navigate to the mob's page using reflection on JEI internals
            setRecipeIndex(recipesGui, mobIndex);

            return true;
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn("Failed to open JEI mob page for {}", entityId, e);

            return false;
        }
    }

    /**
     * Checks if JEI can show information for a specific mob.
     */
    public static boolean canShowMobPage(ResourceLocation entityId) {
        if (!isJEILoaded() || !isJERLoaded()) return false;
        if (!JEIIntegration.isRuntimeAvailable()) return false;

        return getMobIndex(entityId) >= 0;
    }

    /**
     * Gets the cached index for an entity in the JER mob category.
     * Returns -1 if not found.
     */
    private static int getMobIndex(ResourceLocation entityId) {
        if (mobIndexCache == null) buildMobIndexCache();

        return mobIndexCache.getOrDefault(entityId, -1);
    }

    /**
     * Builds the cache mapping entity IDs to their index in JER's mob category.
     * Uses JEI's recipe registry to get wrappers, then reflection to extract entities.
     */
    private static void buildMobIndexCache() {
        mobIndexCache = new HashMap<>();

        if (!isJEILoaded() || !isJERLoaded()) return;
        if (!JEIIntegration.isRuntimeAvailable()) return;

        try {
            long startTime = System.currentTimeMillis();

            IJeiRuntime runtime = JEIIntegration.getRuntime();
            IRecipeRegistry recipeRegistry = runtime.getRecipeRegistry();

            IRecipeCategory<?> mobCategory = recipeRegistry.getRecipeCategory(JER_MOB_CATEGORY);
            if (mobCategory == null) throw new RuntimeException("No JEI category '" + JER_MOB_CATEGORY + "' found");

            List<? extends IRecipeWrapper> wrappers = recipeRegistry.getRecipeWrappers(mobCategory);

            for (int i = 0; i < wrappers.size(); i++) {
                IRecipeWrapper wrapper = wrappers.get(i);
                EntityLivingBase entity = extractEntityFromWrapper(wrapper);

                if (entity != null) {
                    ResourceLocation entityKey = EntityList.getKey(entity);

                    if (entityKey != null) mobIndexCache.put(entityKey, i);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            SuperMobTracker.LOGGER.info("Built JER mob index cache with {} entries in {}ms", mobIndexCache.size(), elapsed);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn("Failed to build JER mob index cache", e);
        }
    }

    /**
     * Extracts the entity from a JER MobWrapper using reflection.
     * MobWrapper has a "mob" field (MobEntry) with getEntity() method.
     */
    private static EntityLivingBase extractEntityFromWrapper(IRecipeWrapper wrapper) {
        try {
            // MobWrapper.mob is a MobEntry
            Field mobField = wrapper.getClass().getDeclaredField("mob");
            mobField.setAccessible(true);
            Object mobEntry = mobField.get(wrapper);

            // MobEntry.getEntity() returns EntityLivingBase
            Method getEntityMethod = mobEntry.getClass().getMethod("getEntity");

            return (EntityLivingBase) getEntityMethod.invoke(mobEntry);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sets the recipe index in JEI's RecipesGui using reflection.
     * JEI doesn't expose this through the API, but we need it to jump to a specific mob.
     *
     * Path: RecipesGui.logic (IRecipeGuiLogic) -> RecipeGuiLogic.state (IngredientLookupState) -> setRecipeIndex(int)
     */
    private static void setRecipeIndex(IRecipesGui recipesGui, int index) {
        try {
            // RecipesGui has a "logic" field of type IRecipeGuiLogic (actually RecipeGuiLogic)
            Field logicField = recipesGui.getClass().getDeclaredField("logic");
            logicField.setAccessible(true);
            Object logic = logicField.get(recipesGui);

            // RecipeGuiLogic has a "state" field of type IngredientLookupState
            Field stateField = logic.getClass().getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(logic);

            // IngredientLookupState has setRecipeIndex method
            Method setRecipeIndexMethod = state.getClass().getMethod("setRecipeIndex", int.class);
            setRecipeIndexMethod.invoke(state, index);

            // RecipeGuiLogic has a "stateListener" field of type IRecipeLogicStateListener
            Field stateListenerField = logic.getClass().getDeclaredField("stateListener");
            stateListenerField.setAccessible(true);
            Object stateListener = stateListenerField.get(logic);

            // IRecipeLogicStateListener has onStateChange() method
            Method onStateChangeMethod = stateListener.getClass().getMethod("onStateChange");
            onStateChangeMethod.invoke(stateListener);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn("Failed to set JEI recipe index to {}", index, e);
        }
    }
}
