package com.supermobtracker.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.EntityLiving;

import static com.supermobtracker.spawn.ConditionUtils.DEFAULT_TIMES;
import static com.supermobtracker.spawn.ConditionUtils.DEFAULT_WEATHERS;
import static com.supermobtracker.spawn.ConditionUtils.canSpawn;


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
        public List<Integer> lightLevels = new ArrayList<>();
        public List<Integer> yLevels = new ArrayList<>();
        public List<String> hints = new ArrayList<>();
        public List<String> groundBlocks = null;    // null = doesn't matter, else list of valid ground blocks
        public List<String> times = null;           // null = doesn't matter, else list of valid times
        public List<String> weathers = null;        // null = doesn't matter, else list of valid weathers
        public Boolean requiresSky = null;          // null = doesn't matter, true = requires sky, false = requires no sky
        public List<Integer> moonPhases = null;     // null = doesn't matter, else list of valid moon phases (0-7)
        public Boolean requiresSlimeChunk = null;   // null = doesn't matter, true = requires slime chunk, false = excludes slime chunk
        public Boolean requiresNether = null;       // null = doesn't matter, true = requires nether-like, false = excludes nether-like
        public String dimension = null;
        public int dimensionId = 0;
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

    // TODO: refactor similar methods into a common utility (e.g., expandBooleanCondition, expandListCondition, etc.)
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

    private List<String> expandGroundBlocks(SampleFinder.ValidSample sample, List<String> candidateGroundBlocks) {
        world.groundBlock = "sky";
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) {
            world.groundBlock = sample.ground;
            return null;
        }

        List<String> allValid = new ArrayList<>();

        for (String ground : candidateGroundBlocks) {
            world.groundBlock = ground;

            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) allValid.add(ground);
        }

        world.groundBlock = sample.ground;

        if (allValid.size() == candidateGroundBlocks.size() && !allValid.isEmpty()) return null;

        return allValid.isEmpty() ? Arrays.asList(sample.ground) : allValid;
    }

    private List<String> expandTimes(SampleFinder.ValidSample sample) {
        List<String> allValid = new ArrayList<>();

        for (String time : DEFAULT_TIMES) {
            world.timeOfDay = time;

            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) allValid.add(time);
        }

        world.timeOfDay = sample.timeOfDay;

        if (allValid.size() == DEFAULT_TIMES.size()) return null;

        return allValid.isEmpty() ? Arrays.asList("unknown") : allValid;
    }

    private List<String> expandWeathers(SampleFinder.ValidSample sample) {
        List<String> allValid = new ArrayList<>();

        for (String weather : DEFAULT_WEATHERS) {
            world.weather = weather;

            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) allValid.add(weather);
        }

        world.weather = sample.weather;

        if (allValid.size() == DEFAULT_WEATHERS.size()) return null;

        return allValid.isEmpty() ? Arrays.asList("unknown") : allValid;
    }

    /**
     * Expand canSeeSky condition by testing both true and false.
     * @return null if both work (doesn't matter), true if only sky works, false if only no-sky works
     */
    private Boolean expandCanSeeSky(SampleFinder.ValidSample sample) {
        boolean worksWithSky = false;
        boolean worksWithoutSky = false;

        world.canSeeSky = true;
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) worksWithSky = true;

        world.canSeeSky = false;
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) worksWithoutSky = true;

        // Restore to sample value
        world.canSeeSky = sample.canSeeSky;

        if (worksWithSky && worksWithoutSky) {
            return null; // Doesn't matter
        } else if (worksWithSky) {
            return true; // Requires sky
        } else if (worksWithoutSky) {
            return false; // Requires no sky (underground)
        }

        // Neither worked - shouldn't happen since we found a valid sample
        return null;
    }

    /**
     * Expand moon phase condition by testing all 8 moon phases.
     * @return null if all phases work (doesn't matter), else list of valid phases
     */
    private List<Integer> expandMoonPhases(SampleFinder.ValidSample sample) {
        List<Integer> validPhases = new ArrayList<>();

        for (int phase = 0; phase < 8; phase++) {
            world.moonPhase = phase;
            if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) validPhases.add(phase);
        }

        // Restore to sample value
        world.moonPhase = sample.moonPhase;

        // If all 8 phases work, it doesn't matter
        if (validPhases.size() == 8) return null;

        // If no phases work, return the sample phase (shouldn't happen since we found a valid sample)
        if (validPhases.isEmpty()) return Arrays.asList(sample.moonPhase);

        return validPhases;
    }

    /**
     * Expand slime chunk condition by testing slime chunk vs non-slime chunk.
     * @return null if both work (doesn't matter), true if requires slime chunk, false if excludes slime chunk
     */
    private Boolean expandSlimeChunk(SampleFinder.ValidSample sample) {
        boolean worksInSlimeChunk = false;
        boolean worksOutsideSlimeChunk = false;

        world.isSlimeChunk = true;
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) worksInSlimeChunk = true;

        world.isSlimeChunk = false;
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) worksOutsideSlimeChunk = true;

        // Restore to sample value
        world.isSlimeChunk = sample.isSlimeChunk;

        if (worksInSlimeChunk && worksOutsideSlimeChunk) {
            return null; // Doesn't matter
        } else if (worksInSlimeChunk) {
            return true; // Requires slime chunk
        } else if (worksOutsideSlimeChunk) {
            return false; // Requires non-slime chunk
        }

        return null;
    }

    /**
     * Expand isNether condition by testing nether-like vs overworld-like.
     * @return null if both work (doesn't matter), true if requires nether-like, false if excludes nether-like
     */
    private Boolean expandIsNether(SampleFinder.ValidSample sample) {
        boolean worksInNether = false;
        boolean worksOutsideNether = false;

        world.isNether = true;
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) worksInNether = true;

        world.isNether = false;
        if (canSpawn(entityClass, world, 0.5, sample.y, 0.5)) worksOutsideNether = true;

        // Restore to sample value
        world.isNether = sample.isNether;

        if (worksInNether && worksOutsideNether) {
            return null; // Doesn't matter
        } else if (worksInNether) {
            return true; // Requires nether-like dimension (doesWaterVaporize)
        } else if (worksOutsideNether) {
            return false; // Requires non-nether-like dimension
        }

        return null;
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
