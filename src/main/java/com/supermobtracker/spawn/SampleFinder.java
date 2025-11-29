package com.supermobtracker.spawn;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.EntityLiving;

import com.supermobtracker.config.ModConfig;

import static com.supermobtracker.spawn.ConditionUtils.*;

/**
 * Finds a first valid spawn sample by iterating through candidate conditions.
 */
public class SampleFinder {

    private final Class<? extends EntityLiving> entityClass;
    private final SpawnConditionAnalyzer.SimulatedWorld world;

    /** Queried conditions from the last spawn check */
    private Map<String, Boolean> lastQueriedConditions;

    public SampleFinder(Class<? extends EntityLiving> entityClass,
                        SpawnConditionAnalyzer.SimulatedWorld world) {
        this.entityClass = entityClass;
        this.world = world;
    }

    public Map<String, Boolean> getLastQueriedConditions() {
        return lastQueriedConditions;
    }

    /**
     * Narrow light levels by testing which levels pass isValidLightLevel.
     */
    public List<Integer> refineLightLevels(List<Integer> probeLevels) {
        List<Integer> passing = new ArrayList<>();
        EntityLiving probe = createEntity(entityClass, world);
        if (probe == null) return probeLevels;

        Method isValidLightLevel = getIsValidLightLevelMethod(probe);
        if (isValidLightLevel == null) return probeLevels;

        for (Integer level : probeLevels) {
            world.lightLevel = level;

            try {
                if ((boolean) isValidLightLevel.invoke(probe)) passing.add(level);
            } catch (Exception e) {
                passing.add(level);
            }
        }

        if (passing.isEmpty()) return probeLevels;

        int min = Collections.min(passing);
        int max = Collections.max(passing);
        if (min == max) return Arrays.asList(min);

        return Arrays.asList(min, max);
    }

    /**
     * Result of finding a valid sample.
     */
    public static class ValidSample {
        public final int y;
        public final int light;
        public final String ground;
        public final String biome;
        public final Map<String, Boolean> queriedConditions;

        public ValidSample(int y, int light, String ground, String biome,
                           Map<String, Boolean> queriedConditions) {
            this.y = y;
            this.light = light;
            this.ground = ground;
            this.biome = biome;
            this.queriedConditions = queriedConditions;
        }
    }

    /**
     * Find a first valid spawn sample by iterating through conditions.
     */
    public ValidSample find(List<String> candidateBiomes,
                            List<String> groundBlocks,
                            List<Integer> lightProbe,
                            List<Integer> yLevels) {
        if (candidateBiomes.isEmpty() || groundBlocks.isEmpty()) return null;

        String biomeId = candidateBiomes.get(0);
        String biomePath = extractBiomePath(biomeId);
        world.biome = biomePath;
        world.groundBlock = groundBlocks.get(0);

        List<Integer> narrowedLight = refineLightLevels(lightProbe);

        ValidSample sample = findWithCurrentConfig(biomeId, groundBlocks.get(0), narrowedLight, yLevels);
        if (sample != null) return sample;

        if (lastQueriedConditions != null && lastQueriedConditions.getOrDefault("groundBlock", false)) {
            for (String groundBlock : groundBlocks) {
                if (groundBlock.equals(groundBlocks.get(0))) continue;

                world.groundBlock = groundBlock;
                sample = findWithCurrentConfig(biomeId, groundBlock, narrowedLight, yLevels);
                if (sample != null) return sample;
            }
        }

        return null;
    }

    private ValidSample findWithCurrentConfig(String biomeId, String groundBlock,
                                              List<Integer> lightLevels, List<Integer> yLevels) {
        for (Integer y : yLevels) {
            for (Integer light : lightLevels) {
                world.lightLevel = light;

                if (canSpawn(entityClass, world, 0.5, y, 0.5)) {
                    world.getAndResetQueriedConditions();
                    EntityLiving entity = createEntity(entityClass, world);
                    if (entity == null) return null;

                    entity.setPosition(0.5, y, 0.5);
                    entity.getCanSpawnHere();
                    lastQueriedConditions = world.getAndResetQueriedConditions();

                    return new ValidSample(y, light, groundBlock, biomeId, lastQueriedConditions);
                }
            }
        }

        return null;
    }

    /**
     * Build a failure result with hints based on queried conditions.
     */
    public SpawnConditionAnalyzer.SpawnConditions buildFailureResult(List<Integer> lightProbe) {
        List<String> failBiomes = Arrays.asList("unknown");
        List<String> failGround = Arrays.asList("unknown");
        List<Integer> narrowedLight = new ArrayList<>();
        List<Integer> emptyY = new ArrayList<>();
        List<String> time = Arrays.asList("unknown");
        List<String> weather = Arrays.asList("unknown");
        List<String> hints = new ArrayList<>();

        if (lastQueriedConditions != null) {
            if (lastQueriedConditions.getOrDefault("dimension", false)) hints.add(HINT_DIMENSION);
            if (lastQueriedConditions.getOrDefault("lightLevel", false)) hints.add(HINT_LIGHT);
            if (lastQueriedConditions.getOrDefault("groundBlock", false)) hints.add(HINT_GROUND);
            if (lastQueriedConditions.getOrDefault("biome", false)) hints.add(HINT_BIOME);
            if (lastQueriedConditions.getOrDefault("timeOfDay", false)) hints.add(HINT_TIME);
            if (lastQueriedConditions.getOrDefault("weather", false)) hints.add(HINT_WEATHER);
        }

        return new SpawnConditionAnalyzer.SpawnConditions(
            failBiomes, failGround, narrowedLight, emptyY, time, weather, hints
        );
    }
}
