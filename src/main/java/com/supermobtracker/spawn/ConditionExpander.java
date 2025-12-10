package com.supermobtracker.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.entity.EntityLiving;

import static com.supermobtracker.spawn.ConditionUtils.DEFAULT_TIMES;
import static com.supermobtracker.spawn.ConditionUtils.DEFAULT_WEATHERS;
import static com.supermobtracker.spawn.ConditionUtils.canSpawnWithSeed;
import static com.supermobtracker.spawn.ConditionUtils.getLastSuccessfulSeed;


/**
 * Expands spawn conditions from a valid sample by exhaustively testing all values.
 */
public class ConditionExpander {

    private final Class<? extends EntityLiving> entityClass;
    private final SpawnConditionAnalyzer.SimulatedWorld world;
    private final long seed;

    public ConditionExpander(Class<? extends EntityLiving> entityClass,
                             SpawnConditionAnalyzer.SimulatedWorld world) {
        this.entityClass = entityClass;
        this.world = world;
        this.seed = getLastSuccessfulSeed();
    }

    public static class ExpandedConditions {
        public List<String> biomes = new ArrayList<>();
        public List<Integer> lightLevels = new ArrayList<>();
        public List<Integer> yLevels = new ArrayList<>();
        public String dimension = null;
        public int dimensionId = 0;

        public List<String> hints = new ArrayList<>();

        public List<String> groundBlocks = null;    // null = doesn't matter, else list of valid ground blocks
        public List<String> times = null;           // null = doesn't matter, else list of valid times
        public List<String> weathers = null;        // null = doesn't matter, else list of valid weathers
        public Boolean requiresSky = null;          // null = doesn't matter, true = requires sky, false = requires no sky
        public List<Integer> moonPhases = null;     // null = doesn't matter, else list of valid moon phases (0-7)
        public Boolean requiresSlimeChunk = null;   // null = doesn't matter, true = requires slime chunk, false = excludes slime chunk
        public Boolean requiresNether = null;       // null = doesn't matter, true = requires nether-like, false = excludes nether-like
    }

    /**
     * Expand all conditions from a valid sample by exhaustively testing all values.
     * Biomes are taken as-is from native spawn tables (no expansion).
     * Conditions are only expanded if they were queried during spawn checks.
     */
    public ExpandedConditions expandAll(SampleFinder.ValidSample sample,
                                        List<String> candidateBiomes,
                                        List<String> candidateGroundBlocks) {
        ExpandedConditions result = new ExpandedConditions();
        Map<String, Boolean> queried = sample.queriedConditions;

        world.biomeId = sample.biome;
        world.groundBlock = sample.ground;
        world.lightLevel = sample.light;
        world.canSeeSky = sample.canSeeSky;
        world.moonPhase = sample.moonPhase;
        world.isSlimeChunk = sample.isSlimeChunk;
        world.isNether = sample.isNether;

        result.yLevels = expandYLevels(sample.y);
        result.lightLevels = expandLightLevels(sample);
        result.biomes = new ArrayList<>(candidateBiomes);

        // Only expand these conditions if they were queried during spawn checks
        if (queried != null && queried.getOrDefault("groundBlock", false)) result.groundBlocks = expandGroundBlocks(sample, candidateGroundBlocks);
        if (queried != null && queried.getOrDefault("timeOfDay", false)) result.times = expandTimes(sample);
        if (queried != null && queried.getOrDefault("weather", false)) result.weathers = expandWeathers(sample);
        if (queried != null && queried.getOrDefault("canSeeSky", false)) result.requiresSky = expandCanSeeSky(sample);
        if (queried != null && queried.getOrDefault("moonPhase", false)) result.moonPhases = expandMoonPhases(sample);
        if (queried != null && queried.getOrDefault("isSlimeChunk", false)) result.requiresSlimeChunk = expandSlimeChunk(sample);
        if (queried != null && queried.getOrDefault("isNether", false)) result.requiresNether = expandIsNether(sample);

        return result;
    }

    /**
     * Helper method to test if spawn succeeds with the saved seed.
     */
    private boolean testSpawn(int y) {
        return canSpawnWithSeed(entityClass, world, 0.5, y, 0.5, seed);
    }

    /**
     * Expand a boolean condition by testing both true and false.
     * @param y The Y level to test at
     * @param setter Function to set the condition value in the world
     * @param sampleValue The value from the successful sample (to restore after testing)
     * @return null if both work, true if only true works, false if only false works
     */
    private Boolean expandBooleanCondition(int y, Consumer<Boolean> setter, boolean sampleValue) {
        boolean worksWithTrue = false;
        boolean worksWithFalse = false;

        setter.accept(true);            // Test true
        if (testSpawn(y)) worksWithTrue = true;

        setter.accept(false);           // Test false
        if (testSpawn(y)) worksWithFalse = true;

        setter.accept(sampleValue);     // Restore original value

        if (worksWithTrue && worksWithFalse) return null;
        if (worksWithTrue) return true;
        if (worksWithFalse) return false;

        return null;
    }

    /**
     * Expand a list condition by testing all possible values.
     * @param y The Y level to test at
     * @param values All possible values to test
     * @param setter Function to set the condition value in the world
     * @param sampleValue The value from the successful sample (to restore after testing)
     * @return null if all values work, else list of valid values
     */
    private <T> List<T> expandListCondition(int y, List<T> values, Consumer<T> setter, T sampleValue) {
        List<T> validValues = new ArrayList<>();

        for (T value : values) {
            setter.accept(value);
            if (testSpawn(y)) validValues.add(value);
        }

        setter.accept(sampleValue);

        // Sometimes, mods query the condition, but do not actually use it (to centralize logic)
        if (validValues.size() == values.size()) return null;
        // Should not happen, but may be the case for high randomness and bad luck
        if (validValues.isEmpty()) return Arrays.asList(sampleValue);

        return validValues;
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
            probeResults[i] = testSpawn(probeYs[i]);
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
                    rangesToTest.get(rangesToTest.size() - 1)[1] = probeYs[i + 1];
                } else {
                    rangesToTest.add(new int[]{probeYs[i], probeYs[i + 1]});
                }
            }
        }

        if (rangesToTest.isEmpty()) return Arrays.asList(sampleY);

        List<Integer> allValid = new ArrayList<>();
        for (int[] range : rangesToTest) {
            for (int y = range[0]; y <= range[1]; y++) {
                if (testSpawn(y)) allValid.add(y);
            }
        }

        if (allValid.isEmpty()) return Arrays.asList(sampleY);

        return allValid;
    }

    private List<Integer> expandLightLevels(SampleFinder.ValidSample sample) {
        List<Integer> levels = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        List<Integer> result = expandListCondition(
            sample.y, levels,
            v -> world.lightLevel = v,
            sample.light
        );

        return result != null ? result : levels;
    }

    private List<String> expandGroundBlocks(SampleFinder.ValidSample sample, List<String> candidateGroundBlocks) {
        world.groundBlock = "sky";
        if (testSpawn(sample.y)) {
            world.groundBlock = sample.ground;
            return null;
        }

        return expandListCondition(
            sample.y, candidateGroundBlocks,
            v -> world.groundBlock = v,
            sample.ground
        );
    }

    private List<String> expandTimes(SampleFinder.ValidSample sample) {
        List<String> result = expandListCondition(
            sample.y, DEFAULT_TIMES,
            v -> world.timeOfDay = v,
            sample.timeOfDay
        );

        if (result != null && result.isEmpty()) return Arrays.asList("unknown");

        return result;
    }

    private List<String> expandWeathers(SampleFinder.ValidSample sample) {
        List<String> result = expandListCondition(
            sample.y, DEFAULT_WEATHERS,
            v -> world.weather = v,
            sample.weather
        );

        if (result != null && result.isEmpty()) return Arrays.asList("unknown");

        return result;
    }

    private Boolean expandCanSeeSky(SampleFinder.ValidSample sample) {
        return expandBooleanCondition(sample.y, v -> world.canSeeSky = v, sample.canSeeSky);
    }

    private List<Integer> expandMoonPhases(SampleFinder.ValidSample sample) {
        List<Integer> phases = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);

        return expandListCondition(
            sample.y, phases,
            v -> world.moonPhase = v,
            sample.moonPhase
        );
    }

    private Boolean expandSlimeChunk(SampleFinder.ValidSample sample) {
        return expandBooleanCondition(sample.y, v -> world.isSlimeChunk = v, sample.isSlimeChunk);
    }

    private Boolean expandIsNether(SampleFinder.ValidSample sample) {
        return expandBooleanCondition(sample.y, v -> world.isNether = v, sample.isNether);
    }

    public SpawnConditionAnalyzer.SpawnConditions toSpawnConditions(ExpandedConditions expanded) {
        return new SpawnConditionAnalyzer.SpawnConditions(
            expanded.biomes,
            expanded.groundBlocks,
            expanded.lightLevels,
            expanded.yLevels,
            expanded.times,
            expanded.weathers,
            expanded.hints,
            expanded.requiresSky,
            expanded.moonPhases,
            expanded.requiresSlimeChunk,
            expanded.requiresNether,
            expanded.dimension,
            expanded.dimensionId
        );
    }
}
