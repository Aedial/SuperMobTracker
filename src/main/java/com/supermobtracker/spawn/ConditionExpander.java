package com.supermobtracker.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.entity.EntityLiving;

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
        public List<int[]> timeRanges = null;       // null = doesn't matter, else list of valid time ranges [start, end] in ticks
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
        world.worldTime = sample.worldTime;

        result.yLevels = expandYLevels(sample.y);
        result.lightLevels = expandLightLevels(sample);
        result.biomes = new ArrayList<>(candidateBiomes);

        // Only expand these conditions if they were queried during spawn checks
        if (queried != null && queried.getOrDefault("groundBlock", false)) result.groundBlocks = expandGroundBlocks(sample, candidateGroundBlocks);
        if (queried != null && queried.getOrDefault("timeOfDay", false)) result.timeRanges = expandTimeRanges(sample);
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

    /**
     * Expand time ranges by testing every 600 ticks (30 seconds in game time).
     * Returns null if all times work, else a list of valid time ranges.
     * Each range is [start, end] in ticks (0-24000).
     */
    private List<int[]> expandTimeRanges(SampleFinder.ValidSample sample) {
        // Test all 40 time points (every 600 ticks = 30 seconds)
        // 24000 ticks / 600 = 40 samples
        List<Long> validTimes = new ArrayList<>();
        long originalTime = sample.worldTime >= 0 ? sample.worldTime : 1000L;

        for (int i = 0; i < 40; i++) {
            long testTime = i * 600L;
            world.worldTime = testTime;

            if (testSpawn(sample.y)) validTimes.add(testTime);
        }

        world.worldTime = originalTime;

        // If all times are valid, return null (doesn't matter)
        if (validTimes.size() == 40) return null;
        // If no times work, return the sample time as a single-tick range
        if (validTimes.isEmpty()) return Arrays.asList(new int[]{(int) originalTime, (int) originalTime});

        // Convert list of valid times into consecutive ranges
        return buildTimeRanges(validTimes);
    }

    /**
     * Build consecutive time ranges from a list of valid tick values.
     * Groups consecutive times (within 600-tick steps) into ranges.
     */
    private List<int[]> buildTimeRanges(List<Long> validTimes) {
        if (validTimes.isEmpty()) return new ArrayList<>();

        List<int[]> ranges = new ArrayList<>();
        int rangeStart = validTimes.get(0).intValue();
        int rangeEnd = rangeStart + 599;  // Each sample covers 600 ticks

        for (int i = 1; i < validTimes.size(); i++) {
            int currentTime = validTimes.get(i).intValue();

            // If this time is consecutive (within 600 ticks of the end), extend the range
            if (currentTime <= rangeEnd + 1) {
                rangeEnd = currentTime + 599;
            } else {
                // Save the current range and start a new one
                ranges.add(new int[]{rangeStart, Math.min(rangeEnd, 23999)});
                rangeStart = currentTime;
                rangeEnd = currentTime + 599;
            }
        }

        // Add the last range
        ranges.add(new int[]{rangeStart, Math.min(rangeEnd, 23999)});

        // Handle wrap-around: if first range starts at 0 and last range ends at 23999,
        // they might be part of the same continuous range
        if (ranges.size() > 1) {
            int[] firstRange = ranges.get(0);
            int[] lastRange = ranges.get(ranges.size() - 1);

            if (firstRange[0] == 0 && lastRange[1] >= 23400) {
                // Merge first and last ranges into one wrap-around range
                ranges.remove(ranges.size() - 1);
                ranges.set(0, new int[]{lastRange[0], firstRange[1] + 24000});
            }
        }

        return ranges;
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
            expanded.timeRanges,
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
