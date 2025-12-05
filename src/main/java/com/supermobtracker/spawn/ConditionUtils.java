package com.supermobtracker.spawn;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.entity.EntityLiving;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;

/**
 * Shared utilities for spawn condition analysis.
 */
public final class ConditionUtils {

    public static final List<String> DEFAULT_TIMES = Arrays.asList("day", "night", "dusk", "dawn");
    public static final List<String> DEFAULT_WEATHERS = Arrays.asList("clear", "rain", "thunder");

    /** Localization key for "any" value */
    public static final String KEY_ANY = I18n.translateToLocal("gui.mobtracker.any");

    /** Localization key for unknown value */
    public static final String KEY_UNKNOWN = I18n.translateToLocal("gui.mobtracker.unknown");

    /** Hint localization keys */
    public static final String HINT_LIGHT = I18n.translateToLocal("gui.mobtracker.hint.light");
    public static final String HINT_GROUND = I18n.translateToLocal("gui.mobtracker.hint.ground");
    public static final String HINT_BIOME = I18n.translateToLocal("gui.mobtracker.hint.biome");
    public static final String HINT_TIME = I18n.translateToLocal("gui.mobtracker.hint.time");
    public static final String HINT_WEATHER = I18n.translateToLocal("gui.mobtracker.hint.weather");

    /** Whether profiling is enabled (Java arg). Can be temporarily suppressed. */
    private static final boolean PROFILING_ARG = Boolean.getBoolean("supermobtracker.profile");
    private static boolean profilingSuppressed = false;
    /** Whether to report complete crashes in spawn conditions for debugging (Java arg). */
    private static final boolean SHOW_CRASHES = Boolean.getBoolean("supermobtracker.showcrashes");

    public static boolean isProfilingEnabled() { return PROFILING_ARG && !profilingSuppressed; }
    public static void suppressProfiling(boolean suppress) { profilingSuppressed = suppress; }
    public static boolean shouldShowCrashes() { return SHOW_CRASHES; }

    /**
     * Translate a list of strings with an optional prefix.
     * @param list   List of strings to translate
     * @param prefix Optional prefix for each string (can be null or empty). If provided, each string
     *               will be translated as prefix.string
     * @return Translated list of strings
     */
    public static List<String> translateList(List<String> list, String prefix) {
        if (list == null) return null;
        if (prefix == null) return list;

        if (prefix.isEmpty()) return list.stream().map(s -> I18n.translateToLocal(s)).collect(Collectors.toList());

        return list.stream().map(s -> I18n.translateToLocal(prefix + "." + s)).collect(Collectors.toList());
    }

    /**
     * Create a new entity instance using reflection.
     */
    public static EntityLiving createEntity(Class<? extends EntityLiving> entityClass, World world) {
        try {
            Constructor<? extends EntityLiving> c = entityClass.getDeclaredConstructor(World.class);
            c.setAccessible(true);

            return c.newInstance(world);
        } catch (Exception e) {
            if (ConditionUtils.shouldShowCrashes()) {
                SuperMobTracker.LOGGER.error("Error creating entity instance for class " + entityClass.getName(), e);
            }

            return null;
        }
    }

    /**
     * Get the isValidLightLevel method from an entity, if available.
     */
    public static Method getIsValidLightLevelMethod(EntityLiving entity) {
        try {
            return entity.getClass().getMethod("isValidLightLevel");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Check if an entity can spawn at a position with retries for random spawn checks.
     * Returns true if spawning succeeds at least once within the configured retry limit.
     */
    public static boolean canSpawn(Class<? extends EntityLiving> entityClass,
                                   SpawnConditionAnalyzer.SimulatedWorld world,
                                   double x, int y, double z) {
        int maxRetries = ModConfig.clientSpawnCheckRetries;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            EntityLiving entity = createEntity(entityClass, world);
            if (entity == null) return false;

            entity.setPosition(x, y, z);
            if (entity.getCanSpawnHere()) return true;
        }

        return false;
    }

    /**
     * Extract the biome path from a biome ID (removes namespace prefix if present).
     */
    public static String extractBiomePath(String biomeId) {
        return biomeId.contains(":") ? biomeId.split(":", 2)[1] : biomeId;
    }
}
