package com.supermobtracker.spawn;

import java.util.List;

import net.minecraft.entity.EntityLiving;

/**
 * Orchestrates spawn condition refinement and expansion.
 */
public class ConditionRefiner {

    private final Class<? extends EntityLiving> entityClass;
    private final SpawnConditionAnalyzer.SimulatedWorld world;
    private final SampleFinder sampleFinder;
    private final ConditionExpander expander;

    public ConditionRefiner(Class<? extends EntityLiving> entityClass,
                            SpawnConditionAnalyzer.SimulatedWorld world) {
        this.entityClass = entityClass;
        this.world = world;
        this.sampleFinder = new SampleFinder(entityClass, world);
        this.expander = new ConditionExpander(entityClass, world);
    }

    /**
     * Narrow light levels by testing which levels pass isValidLightLevel.
     */
    public List<Integer> refineLightLevels(List<Integer> probeLevels) {
        return sampleFinder.refineLightLevels(probeLevels);
    }

    /**
     * Attempt to find a valid sample and expand all spawn conditions exhaustively.
     * Biomes are taken as-is from native spawn tables (no expansion).
     */
    public SpawnConditionAnalyzer.SpawnConditions findValidConditions(
            List<String> candidateBiomes,
            List<String> groundBlocks,
            List<Integer> lightProbe,
            List<Integer> yLevels) {

        if (candidateBiomes.isEmpty() || groundBlocks.isEmpty()) return null;

        SampleFinder.ValidSample sample = sampleFinder.find(candidateBiomes, groundBlocks, lightProbe, yLevels);
        if (sample == null) return sampleFinder.buildFailureResult(lightProbe);

        ConditionExpander.ExpandedConditions expanded = expander.expandAll(sample, candidateBiomes, groundBlocks);
        return expander.toSpawnConditions(expanded);
    }
}
