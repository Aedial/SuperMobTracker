package com.supermobtracker.spawn;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.supermobtracker.SuperMobTracker;

import net.minecraft.entity.EntityLiving;

import static com.supermobtracker.spawn.ConditionUtils.*;

/**
 * Finds a first valid spawn sample by iterating through candidate conditions.
 * Uses a queue-based approach for optional conditions to avoid uncontrolled nesting.
 */
public class SampleFinder {

    private final Class<? extends EntityLiving> entityClass;
    private final SpawnConditionAnalyzer.SimulatedWorld world;

    /** Queried conditions from the last spawn check */
    private Map<String, Boolean> lastQueriedConditions;

    /**
     * Definition of an optional condition with its possible values.
     */
    private static class OptionalCondition {
        final String name;
        final Object[] values;

        OptionalCondition(String name, Object... values) {
            this.name = name;
            this.values = values;
        }
    }

    /**
     * All optional conditions with their possible values.
     * Order matters: conditions earlier in the list are tried first.
     * Time values are in ticks: day=1000, dusk=12000, night=13000, dawn=23000
     */
    private static final List<OptionalCondition> OPTIONAL_CONDITIONS = Arrays.asList(
        new OptionalCondition("canSeeSky", true, false),
        new OptionalCondition("moonPhase", 0, 1, 2, 3, 4, 5, 6, 7),  // All 8 moon phases (0=full, 4=new)
        new OptionalCondition("isSlimeChunk", true, false),
        new OptionalCondition("isNether", false, true),  // Try non-nether first (most common)
        new OptionalCondition("timeOfDay", 1000L, 13000L, 12000L, 23000L),  // day, night, dusk, dawn in ticks
        new OptionalCondition("weather", "clear", "rain", "thunder")
        // Add more optional conditions here as needed
    );

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
        public final boolean canSeeSky;
        public final int moonPhase;
        public final boolean isSlimeChunk;
        public final boolean isNether;
        public final long worldTime;     // Time in ticks (0-24000)
        public final String weather;
        public final Map<String, Boolean> queriedConditions;

        public ValidSample(int y, int light, String ground, String biome, boolean canSeeSky,
                           int moonPhase, boolean isSlimeChunk, boolean isNether,
                           long worldTime, String weather,
                           Map<String, Boolean> queriedConditions) {
            this.y = y;
            this.light = light;
            this.ground = ground;
            this.biome = biome;
            this.canSeeSky = canSeeSky;
            this.moonPhase = moonPhase;
            this.isSlimeChunk = isSlimeChunk;
            this.isNether = isNether;
            this.worldTime = worldTime;
            this.weather = weather;
            this.queriedConditions = queriedConditions;
        }
    }

    /**
     * Represents a combination of optional condition values to try.
     */
    private static class ConditionCombination {
        final Map<String, Object> values;

        ConditionCombination() {
            this.values = new HashMap<>();
        }

        ConditionCombination(ConditionCombination other) {
            this.values = new HashMap<>(other.values);
        }

        void set(String name, Object value) {
            values.put(name, value);
        }

        Object get(String name) {
            return values.get(name);
        }

        boolean has(String name) {
            return values.containsKey(name);
        }
    }

    /**
     * Find a first valid spawn sample by iterating through conditions.
     * Uses a queue-based approach to handle optional conditions dynamically.
     */
    public ValidSample find(List<String> candidateBiomes,
                            List<String> groundBlocks,
                            List<Integer> lightProbe,
                            List<Integer> yLevels) {
        if (candidateBiomes.isEmpty() || groundBlocks.isEmpty()) return null;

        world.biomeId = candidateBiomes.get(0);     // native biomes should always be successful
        world.groundBlock = groundBlocks.get(0);    // give a default block to start with

        List<Integer> narrowedLight = refineLightLevels(lightProbe);    // cut options by half or more

        // Queue of condition combinations to try
        Deque<ConditionCombination> queue = new ArrayDeque<>();
        queue.add(new ConditionCombination());  // Start with empty (default) combination

        // Track which optional conditions have been added to the queue
        Set<String> conditionsInQueue = new HashSet<>();

        while (!queue.isEmpty()) {
            ConditionCombination combo = queue.poll();

            // Apply the combination to the world
            applyConditionCombination(combo);

            // Try to find a valid sample with this combination
            ValidSample sample = findWithCurrentConfig(narrowedLight, yLevels);
            if (sample != null) return sample;

            // Check which optional conditions were queried but not yet in the combination
            // and expand the queue with those conditions
            if (lastQueriedConditions != null) {
                for (OptionalCondition optCond : OPTIONAL_CONDITIONS) {
                    String condName = optCond.name;

                    // Not queried or already in queue
                    if (!lastQueriedConditions.getOrDefault(condName, false)) continue;
                    if (conditionsInQueue.contains(condName)) continue;

                    // Add all value combinations for this condition to the queue
                    conditionsInQueue.add(condName);

                    // Create new combinations based on existing queue + new condition values
                    // We need to expand all existing items in the queue with the new values
                    List<ConditionCombination> currentQueue = new ArrayList<>(queue);
                    queue.clear();
                    currentQueue.add(0, combo);

                    for (ConditionCombination existing : currentQueue) {
                        for (Object value : optCond.values) {
                            ConditionCombination newCombo = new ConditionCombination(existing);
                            newCombo.set(condName, value);
                            queue.add(newCombo);
                        }
                    }

                    // Break to process the expanded queue
                    break;
                }

                // Handle groundBlock as a dynamic optional condition with provided candidates
                if (lastQueriedConditions.getOrDefault("groundBlock", false) && !conditionsInQueue.contains("groundBlock")) {
                    conditionsInQueue.add("groundBlock");

                    List<ConditionCombination> currentQueue = new ArrayList<>(queue);
                    queue.clear();
                    currentQueue.add(0, combo);

                    for (ConditionCombination existing : currentQueue) {
                        for (String gb : groundBlocks) {
                            ConditionCombination newCombo = new ConditionCombination(existing);
                            newCombo.set("groundBlock", gb);
                            queue.add(newCombo);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Apply a condition combination to the simulated world.
     */
    private void applyConditionCombination(ConditionCombination combo) {
        if (combo.has("canSeeSky")) world.canSeeSky = (Boolean) combo.get("canSeeSky");
        if (combo.has("moonPhase")) world.moonPhase = (Integer) combo.get("moonPhase");
        if (combo.has("isSlimeChunk")) world.isSlimeChunk = (Boolean) combo.get("isSlimeChunk");
        if (combo.has("isNether")) world.isNether = (Boolean) combo.get("isNether");
        if (combo.has("timeOfDay")) world.worldTime = (Long) combo.get("timeOfDay");
        if (combo.has("weather")) world.weather = (String) combo.get("weather");
        if (combo.has("groundBlock")) world.groundBlock = (String) combo.get("groundBlock");
    }

    private ValidSample findWithCurrentConfig(List<Integer> lightLevels, List<Integer> yLevels) {
        Map<String, Boolean> aggregatedQueried = new HashMap<>();

        for (Integer y : yLevels) {
            for (Integer light : lightLevels) {
                world.lightLevel = light;

                if (canSpawn(entityClass, world, 0.5, y, 0.5)) {
                    mergeQueriedConditions(aggregatedQueried, world.getAndResetQueriedConditions());

                    long sampleTime = aggregatedQueried.getOrDefault("timeOfDay", false) ? world.worldTime : -1L;
                    String sampleWeather = aggregatedQueried.getOrDefault("weather", false) ? world.weather : null;
                    String ground = aggregatedQueried.getOrDefault("groundBlock", false) ? world.groundBlock : null;

                    return new ValidSample(y, light, ground, world.biomeId, world.canSeeSky, world.moonPhase,
                        world.isSlimeChunk, world.isNether, sampleTime, sampleWeather, aggregatedQueried);
                }

                mergeQueriedConditions(aggregatedQueried, world.getAndResetQueriedConditions());
                lastQueriedConditions = aggregatedQueried;
            }
        }

        return null;
    }

    /**
     * Merge queried conditions from a spawn check into the aggregated map.
     */
    private void mergeQueriedConditions(Map<String, Boolean> aggregated, Map<String, Boolean> source) {
        if (source == null) return;

        for (Map.Entry<String, Boolean> entry : source.entrySet()) {
            if (entry.getValue()) aggregated.put(entry.getKey(), true);
        }
    }

    /**
     * Build a failure result with hints based on queried conditions.
     */
    public SpawnConditionAnalyzer.SpawnConditions buildFailureResult(List<Integer> lightProbe) {
        List<String> failBiomes = Arrays.asList("unknown");
        List<String> failGround = Arrays.asList("unknown");
        List<Integer> narrowedLight = new ArrayList<>();
        List<Integer> emptyY = new ArrayList<>();
        List<int[]> time = null;  // null indicates unknown/not determined
        List<String> weather = Arrays.asList("unknown");
        List<String> hints = new ArrayList<>();

        if (lastQueriedConditions != null) {
            if (lastQueriedConditions.getOrDefault("lightLevel", false)) hints.add(HINT_LIGHT);
            if (lastQueriedConditions.getOrDefault("groundBlock", false)) hints.add(HINT_GROUND);
            if (lastQueriedConditions.getOrDefault("biome", false)) hints.add(HINT_BIOME);
            if (lastQueriedConditions.getOrDefault("timeOfDay", false)) hints.add(HINT_TIME);
            if (lastQueriedConditions.getOrDefault("weather", false)) hints.add(HINT_WEATHER);
            if (lastQueriedConditions.getOrDefault("canSeeSky", false)) hints.add(HINT_SKY);
            if (lastQueriedConditions.getOrDefault("moonPhase", false)) hints.add(HINT_MOON_PHASE);
            if (lastQueriedConditions.getOrDefault("isSlimeChunk", false)) hints.add(HINT_SLIME_CHUNK);
            if (lastQueriedConditions.getOrDefault("isNether", false)) hints.add(HINT_NETHER);
        }

        return new SpawnConditionAnalyzer.SpawnConditions(
            failBiomes, failGround, narrowedLight, emptyY, time, weather, hints, null, null, Integer.MIN_VALUE
        );
    }
}
