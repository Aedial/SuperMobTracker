package com.supermobtracker.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.entity.EntityLiving;
import net.minecraft.util.text.translation.I18n;

import static com.supermobtracker.spawn.ConditionUtils.*;


/**
 * Expands spawn conditions from a valid sample by exhaustively testing all values.
 */
public class ConditionExpander {

    private final Class<? extends EntityLiving> entityClass;
    private final SpawnConditionAnalyzer.SimulatedWorld world;

    public ConditionExpander(Class<? extends EntityLiving> entityClass,
                             SpawnConditionAnalyzer.SimulatedWorld world) {
        this.entityClass = entityClass;
        this.world = world;
    }

    public static class ExpandedConditions {
        public List<String> biomes = new ArrayList<>();
        public List<String> groundBlocks = new ArrayList<>();
        public List<Integer> lightLevels = new ArrayList<>();
        public List<Integer> yLevels = new ArrayList<>();
        public List<String> times = new ArrayList<>();
        public List<String> weathers = new ArrayList<>();
        public List<String> hints = new ArrayList<>();
    }

    /**
     * Expand all conditions from a valid sample by exhaustively testing all values.
     * Biomes are taken as-is from native spawn tables (no expansion).
     */
    public ExpandedConditions expandAll(SampleFinder.ValidSample sample,
                                        List<String> candidateBiomes,
                                        List<String> candidateGroundBlocks) {
        ExpandedConditions result = new ExpandedConditions();

        world.biome = extractBiomePath(sample.biome);
        world.groundBlock = sample.ground;
        world.lightLevel = sample.light;

        result.yLevels = expandYLevels(sample.y);
        result.lightLevels = expandLightLevels(sample);
        result.groundBlocks = expandGroundBlocks(sample, candidateGroundBlocks);
        result.biomes = new ArrayList<>(candidateBiomes);
        result.times = expandTimes(sample);
        result.weathers = expandWeathers(sample);

        return result;
    }

    /**
     * Expand Y levels using probe points to identify valid ranges efficiently.
     * Only exhaustively tests ranges that contain at least one passing probe point.
     */
    private List<Integer> expandYLevels(int sampleY) {
        int[] probeYs = {1, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240, 255};

        boolean[] probeResults = new boolean[probeYs.length];
        boolean allPass = true;
        for (int i = 0; i < probeYs.length; i++) {
            probeResults[i] = canSpawn(entityClass, world, 0.5, probeYs[i], 0.5);
            if (!probeResults[i]) allPass = false;
        }

        if (allPass) {
            List<Integer> fullRange = new ArrayList<>();
            for (int y = 1; y <= 255; y++) fullRange.add(y);

            return fullRange;
        }

        List<int[]> rangesToTest = new ArrayList<>();
        for (int i = 0; i < probeYs.length - 1; i++) {
            if (probeResults[i] || probeResults[i + 1]) {
                if (!rangesToTest.isEmpty() && rangesToTest.get(rangesToTest.size() - 1)[1] == probeYs[i]) {
                    rangesToTest.get(rangesToTest.size() - 1)[1] = probeYs[i + 1];  // extend existing range
                } else {
                    rangesToTest.add(new int[]{probeYs[i], probeYs[i + 1]});        // new range
                }
            }
        }

        if (rangesToTest.isEmpty()) return Arrays.asList(sampleY);

        List<Integer> allValid = new ArrayList<>();
        for (int[] range : rangesToTest) {
            for (int y = range[0]; y <= range[1]; y++) {
                if (canSpawn(entityClass, world, 0.5, y, 0.5)) allValid.add(y);
            }
        }

        if (allValid.isEmpty()) return Arrays.asList(sampleY);

        return allValid;
    }

    private List<Integer> expandLightLevels(SampleFinder.ValidSample sample) {
        List<Integer> allValid = new ArrayList<>();

        for (int light = 0; light <= 15; light++) {
            world.lightLevel = light;

            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) allValid.add(light);
        }

        world.lightLevel = sample.light;

        if (allValid.isEmpty()) return Arrays.asList(sample.light);

        return allValid;
    }

    private List<String> expandGroundBlocks(SampleFinder.ValidSample sample,
                                            List<String> candidateGroundBlocks) {
        world.groundBlock = "sky";
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) {
            world.groundBlock = sample.ground;
            return Arrays.asList("any");
        }

        List<String> allValid = new ArrayList<>();

        for (String ground : candidateGroundBlocks) {
            world.groundBlock = ground;

            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) allValid.add(ground);
        }

        world.groundBlock = sample.ground;

        if (allValid.size() == candidateGroundBlocks.size() && !allValid.isEmpty()) return Arrays.asList("gui.mobtracker.any");

        return allValid.isEmpty() ? Arrays.asList(sample.ground) : allValid;
    }

    private List<String> expandTimes(SampleFinder.ValidSample sample) {
        List<String> allValid = new ArrayList<>();

        for (String time : DEFAULT_TIMES) {
            world.timeOfDay = time;

            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) {
                allValid.add(time);
            }
        }

        world.timeOfDay = "day";

        if (allValid.size() == DEFAULT_TIMES.size()) return Arrays.asList("any");

        return allValid.isEmpty() ? Arrays.asList("unknown") : allValid;
    }

    private List<String> expandWeathers(SampleFinder.ValidSample sample) {
        List<String> allValid = new ArrayList<>();

        for (String weather : DEFAULT_WEATHERS) {
            world.weather = weather;

            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) {
                allValid.add(weather);
            }
        }

        world.weather = "clear";

        if (allValid.size() == DEFAULT_WEATHERS.size()) return Arrays.asList("any");

        return allValid.isEmpty() ? Arrays.asList("unknown") : allValid;
    }

    public SpawnConditionAnalyzer.SpawnConditions toSpawnConditions(ExpandedConditions expanded) {
        return new SpawnConditionAnalyzer.SpawnConditions(
            expanded.biomes,
            expanded.groundBlocks,
            expanded.lightLevels,
            expanded.yLevels,
            expanded.times,
            expanded.weathers,
            expanded.hints
        );
    }
}
