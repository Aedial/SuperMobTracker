package com.supermobtracker.command;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.IClientCommand;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.drops.DropSimulator;
import com.supermobtracker.drops.DropSimulator.ProfileResult;
import com.supermobtracker.spawn.BiomeDimensionMapper;
import com.supermobtracker.spawn.ConditionUtils;
import com.supermobtracker.spawn.SpawnConditionAnalyzer;


/**
 * Client-side command to analyze all mobs and export results to files.
 * Usage:
 *   /smtanalyze - Run all analyses with default parameters
 *   /smtanalyze mobs [samples] - Analyze all mobs with performance metrics
 *   /smtanalyze loot [samples] [simulationCount] - Analyze loot drops for all mobs
 *   /smtanalyze dimension [samples] [extendedCount] [numGrids] - Benchmark dimension mapping
 */
public class CommandAnalyze extends CommandBase implements IClientCommand {
    private static final int DEFAULT_SAMPLES = 10;
    private static final int DEFAULT_LOOT_SIMULATION_COUNT = 10000;
    private static final String OUTPUT_DIR = "supermobtracker";

    @Override
    public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) {
        return false;
    }

    @Override
    public String getName() {
        return "smtanalyze";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/smtanalyze [mobs|loot|dimension] [samples] [simulationCount|extendedCount] [numGrids]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // Allow all players to use this command
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "mobs", "loot", "dimension");

        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            // Run all analyses with default parameters
            sendMessage(sender, TextFormatting.YELLOW, "Starting full analysis (this may take a while)...");
            runAllAnalyses(sender, DEFAULT_SAMPLES, BiomeDimensionMapper.getDefaultExtendedCount(), BiomeDimensionMapper.getDefaultNumGrids());
            return;
        }

        String subCommand = args[0].toLowerCase();
        int samples = args.length > 1 ? parseInt(args[1], 1, 100) : DEFAULT_SAMPLES;

        switch (subCommand) {
            case "mobs":
                sendMessage(sender, TextFormatting.YELLOW, "Analyzing all mobs (" + samples + " samples)...");
                new Thread(() -> runMobAnalysis(sender, samples), "SMT-MobAnalysis").start();
                break;

            case "loot":
                int simulationCount = args.length > 2 ? parseInt(args[2], 1, 10000) : DEFAULT_LOOT_SIMULATION_COUNT;
                sendMessage(sender, TextFormatting.YELLOW, "Analyzing loot drops (" + samples + " samples, " + simulationCount + " simulations)...");
                new Thread(() -> runLootAnalysis(sender, samples, simulationCount), "SMT-LootAnalysis").start();
                break;

            case "dimension":
                int extendedCount = args.length > 2 ? parseInt(args[2], 100, 100000) : BiomeDimensionMapper.getDefaultExtendedCount();
                int numGrids = args.length > 3 ? parseInt(args[3], 1, 64) : BiomeDimensionMapper.getDefaultNumGrids();
                sendMessage(sender, TextFormatting.YELLOW, "Benchmarking dimension mapping (" + samples + " samples, extended=" + extendedCount + ", grids=" + numGrids + ")...");
                new Thread(() -> runDimensionBenchmark(sender, samples, extendedCount, numGrids), "SMT-DimensionBenchmark").start();
                break;

            default:
                throw new CommandException("Unknown subcommand: " + subCommand);
        }
    }

    private void runAllAnalyses(ICommandSender sender, int samples, int extendedCount, int numGrids) {
        long totalStart = System.nanoTime();

        // Run each analysis sequentially
        new Thread(() -> {
            runDimensionBenchmark(sender, samples, extendedCount, numGrids);
            runMobAnalysis(sender, samples);
            runLootAnalysis(sender, samples, DEFAULT_LOOT_SIMULATION_COUNT);

            long totalElapsed = System.nanoTime() - totalStart;
            sendMessage(sender, TextFormatting.GREEN, "All analyses complete! Total time: " + formatDuration(totalElapsed));
        }, "SMT-AllAnalyses").start();
    }

    /**
     * Analyze all mobs and export performance metrics.
     * Separates results into:
     * - successful: spawn conditions determined
     * - failed: has native biomes but conditions couldn't be determined  
     * - noDimension: biomes couldn't be mapped to any dimension
     * - noNativeBiomes: doesn't spawn naturally (no biomes in spawn tables)
     * - crashed: threw an exception during analysis
     */
    private void runMobAnalysis(ICommandSender sender, int samples) {
        ConditionUtils.suppressProfiling(true);
        long startTime = System.nanoTime();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        List<MobPerformanceEntry> successfulMobs = new ArrayList<>();
        List<MobPerformanceEntry> failedMobs = new ArrayList<>();
        List<MobPerformanceEntry> sparseMobs = new ArrayList<>();
        List<MobPerformanceEntry> noDimensionMobs = new ArrayList<>();
        List<MobPerformanceEntry> noNativeBiomeMobs = new ArrayList<>();
        List<MobPerformanceEntry> crashedMobs = new ArrayList<>();

        SpawnConditionAnalyzer analyzer = new SpawnConditionAnalyzer();
        List<ResourceLocation> entityIds = new ArrayList<>();

        // Collect all living entities
        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            if (EntityLiving.class.isAssignableFrom(entry.getEntityClass())) entityIds.add(entry.getRegistryName());
        }

        int total = entityIds.size();
        int current = 0;

        for (ResourceLocation entityId : entityIds) {
            current++;
            if (current % 50 == 0) sendProgress(sender, "Progress: " + current + "/" + total + " mobs analyzed...");

            List<Long> timings = new ArrayList<>();
            SpawnConditionAnalyzer.SpawnConditions result = null;
            String error = null;
            boolean hasNativeBiomes = false;

            for (int i = 0; i < samples; i++) {
                // Clear caches between samples for accurate timing
                analyzer = new SpawnConditionAnalyzer();

                long sampleStart = System.nanoTime();
                result = analyzer.analyze(entityId);
                long sampleTime = System.nanoTime() - sampleStart;
                timings.add(sampleTime);

                hasNativeBiomes = analyzer.hasNativeBiomes();

                // Check if analysis crashed internally
                Throwable lastError = analyzer.getLastError();
                if (lastError != null) {
                    error = lastError.getClass().getSimpleName() + ": " + lastError.getMessage();
                    break; // No point retrying
                }
            }

            // Categorize the result
            if (error != null) {
                crashedMobs.add(new MobPerformanceEntry(entityId.toString(), timings, false, null, null, error));
            } else if (!hasNativeBiomes) {
                noNativeBiomeMobs.add(new MobPerformanceEntry(entityId.toString(), timings, false, null, null, null));
            } else if (result == null) {
                // Has biomes but result is null - shouldn't happen, but track it
                failedMobs.add(new MobPerformanceEntry(entityId.toString(), timings, false, null, null, null));
            } else if (result.dimension == null) {
                // Has biomes but couldn't map to dimension
                noDimensionMobs.add(new MobPerformanceEntry(entityId.toString(), timings, false, null, result.biomes, null));
            } else if (result.isSparse()) {
                // Analysis returned multiple ranges (sparse) which indicates incomplete or ambiguous sampling
                sparseMobs.add(new MobPerformanceEntry(entityId.toString(), timings, false, result.dimension, result.biomes, null));
            } else if (result.failed()) {
                failedMobs.add(new MobPerformanceEntry(entityId.toString(), timings, false, result.dimension, result.biomes, null));
            } else {
                successfulMobs.add(new MobPerformanceEntry(entityId.toString(), timings, true, result.dimension, result.biomes, null));
            }
        }

        // Sort each list by average (slowest time first)
        Comparator<MobPerformanceEntry> byAverageTime = (a, b) -> Double.compare(b.getAverageTime(), a.getAverageTime());
        successfulMobs.sort(byAverageTime);
        failedMobs.sort(byAverageTime);
        sparseMobs.sort(byAverageTime);
        noDimensionMobs.sort(byAverageTime);
        noNativeBiomeMobs.sort(byAverageTime);
        crashedMobs.sort(byAverageTime);

        // Write successful mobs to file
        File successFile = writePerformanceReport(
            "mob_performance_" + samples + "samples_" + timestamp + ".txt",
            "Successful Mob Analysis Performance Report",
            null,
            successfulMobs,
            samples,
            "Format: [Entity ID] - Avg: Xms, Worst: Xms, Best: Xms | Dimension: X",
            (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Dimension: %s%n",
                entry.entityId,
                entry.getAverageTime() / 1_000_000.0,
                entry.getWorstTime() / 1_000_000.0,
                entry.getBestTime() / 1_000_000.0,
                entry.dimension != null ? entry.dimension : "?"),
            sender
        );
        if (successFile == null) {
            ConditionUtils.suppressProfiling(false);
            return;
        }

        // Write failed mobs to file
        if (!failedMobs.isEmpty()) {
            writePerformanceReport(
                "failed_performance_" + samples + "samples_" + timestamp + ".txt",
                "Failed Mob Analysis Performance Report",
                "These mobs have native biomes but spawn conditions could not be determined.",
                failedMobs,
                samples,
                "Format: [Entity ID] - Avg: Xms, Worst: Xms, Best: Xms | Dimension: X",
                (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Dimension: %s%n",
                    entry.entityId,
                    entry.getAverageTime() / 1_000_000.0,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.dimension != null ? entry.dimension : "?"),
                sender
            );
        }

        // Write sparse mobs to file (ambiguous/multiple-range results)
        if (!sparseMobs.isEmpty()) {
            writePerformanceReport(
                "sparse_performance_" + samples + "samples_" + timestamp + ".txt",
                "Sparse Mob Analysis Performance Report",
                "These mobs produced sparse/ambiguous spawn condition results (multiple ranges detected).",
                sparseMobs,
                samples,
                "Format: [Entity ID] - Avg: Xms, Worst: Xms, Best: Xms | Dimension: X",
                (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Dimension: %s%n",
                    entry.entityId,
                    entry.getAverageTime() / 1_000_000.0,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.dimension != null ? entry.dimension : "?"),
                sender
            );
        }

        // Write crashed mobs to file
        if (!crashedMobs.isEmpty()) {
            writePerformanceReport(
                "crashed_performance_" + samples + "samples_" + timestamp + ".txt",
                "Crashed Mob Analysis Performance Report",
                "These mobs caused exceptions during spawn condition analysis.",
                crashedMobs,
                samples,
                "Format: [Entity ID] - Avg: Xms, Worst: Xms, Best: Xms | Error: X",
                (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Error: %s%n",
                    entry.entityId,
                    entry.getAverageTime() / 1_000_000.0,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.error),
                sender
            );
        }

        // Write noDimension mobs to file (biomes couldn't be mapped to any dimension)
        if (!noDimensionMobs.isEmpty()) {
            writePerformanceReport(
                "no_dimension_" + samples + "samples_" + timestamp + ".txt",
                "No Dimension Mapping - Mob Analysis Report",
                "These mobs have native biomes that couldn't be mapped to any dimension.",
                noDimensionMobs,
                samples,
                "Format: [Entity ID] - Avg: Xms, Worst: Xms, Best: Xms | Biomes: [list]",
                (writer, entry) -> {
                    String biomeList = entry.biomes != null ? String.join(", ", entry.biomes) : "?";
                    writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Biomes: %s%n",
                        entry.entityId,
                        entry.getAverageTime() / 1_000_000.0,
                        entry.getWorstTime() / 1_000_000.0,
                        entry.getBestTime() / 1_000_000.0,
                        biomeList);
                },
                sender
            );
        }

        // Write noNativeBiomes mobs to file (don't spawn naturally)
        if (!noNativeBiomeMobs.isEmpty()) {
            // Sort alphabetically for this list since timing is irrelevant
            noNativeBiomeMobs.sort((a, b) -> a.entityId.compareTo(b.entityId));
            writePerformanceReport(
                "no_native_biomes_" + samples + "samples_" + timestamp + ".txt",
                "No Native Biomes - Mob Analysis Report",
                "These mobs don't have native biomes and cannot spawn naturally.",
                noNativeBiomeMobs,
                samples,
                null, // No format hint, just entity IDs
                (writer, entry) -> writer.println(entry.entityId),
                sender
            );
        }

        ConditionUtils.suppressProfiling(false);
        long elapsed = System.nanoTime() - startTime;
        sendMessage(sender, TextFormatting.GREEN, "Mob analysis complete! Time: " + formatDuration(elapsed));
        sendMessage(sender, TextFormatting.AQUA, "Successful: " + successfulMobs.size() + ", Failed: " + failedMobs.size() + 
            ", Sparse: " + sparseMobs.size() + ", No dimension: " + noDimensionMobs.size() + ", Crashed: " + crashedMobs.size() + ", No biomes: " + noNativeBiomeMobs.size());
        sendMessage(sender, TextFormatting.AQUA, "Results saved to: " + successFile.getParent());
    }

    /**
     * Benchmark dimension mapping with customizable parameters.
     * Tracks both total initialization time and per-dimension timing.
     */
    private void runDimensionBenchmark(ICommandSender sender, int samples, int extendedCount, int numGrids) {
        ConditionUtils.suppressProfiling(true);
        long startTime = System.nanoTime();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        List<Long> totalTimings = new ArrayList<>();
        Map<Integer, List<Long>> perDimensionTimings = new HashMap<>();

        // Run multiple samples of the full initialization
        for (int i = 0; i < samples; i++) {
            BiomeDimensionMapper.clearCache();

            long sampleStart = System.nanoTime();
            BiomeDimensionMapper.initWithParams(extendedCount, numGrids);
            long sampleTime = System.nanoTime() - sampleStart;

            totalTimings.add(sampleTime);

            // Collect per-dimension timings from this sample
            Map<Integer, Long> dimTimings = BiomeDimensionMapper.getDimensionTimings();
            for (Map.Entry<Integer, Long> entry : dimTimings.entrySet()) {
                perDimensionTimings.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
            }

            sendProgress(sender, "Sample " + (i + 1) + "/" + samples + " complete: " + formatDuration(sampleTime));
        }

        // Collect dimension data after final init
        List<Integer> dimensionIds = BiomeDimensionMapper.getSortedDimensionIds();
        List<DimensionPerformanceEntry> entries = new ArrayList<>();

        for (Integer dimId : dimensionIds) {
            int biomeCount = BiomeDimensionMapper.getBiomesInDimension(dimId).size();
            List<Long> timings = perDimensionTimings.getOrDefault(dimId, Collections.singletonList(0L));
            entries.add(new DimensionPerformanceEntry(
                dimId,
                BiomeDimensionMapper.getDimensionName(dimId),
                biomeCount,
                timings
            ));
        }

        // Sort by worst time (slowest first)
        entries.sort((a, b) -> Long.compare(b.getWorstTime(), a.getWorstTime()));

        // Write to file
        File outputFile = getOutputFile("dimension_performance_" + samples + "samples_" + extendedCount + "ext_" + numGrids + "grids_" + timestamp + ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("=== Dimension Mapping Performance Report ===");
            writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("Parameters: extendedCount=" + extendedCount + ", numGrids=" + numGrids);
            writer.println("Samples: " + samples);
            writer.println();

            // Total init performance
            writer.println("=== Total Initialization Time ===");
            writer.printf("Avg: %.2fms, Worst: %.2fms, Best: %.2fms%n",
                totalTimings.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0,
                Collections.max(totalTimings) / 1_000_000.0,
                Collections.min(totalTimings) / 1_000_000.0
            );
            writer.println();

            // Per-dimension performance
            writer.println("=== Per-Dimension Performance ===");
            writer.println("Sorted by average time (slowest first)");
            writer.println();

            for (DimensionPerformanceEntry entry : entries) {
                writer.printf("%d (%s) - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Biomes: %d%n",
                    entry.dimId,
                    entry.dimName,
                    entry.getAverageTime() / 1_000_000.0,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.biomeCount
                );
            }
        } catch (IOException e) {
            sendMessage(sender, TextFormatting.RED, "Failed to write output file: " + e.getMessage());
            ConditionUtils.suppressProfiling(false);
            return;
        }

        ConditionUtils.suppressProfiling(false);
        long elapsed = System.nanoTime() - startTime;
        sendMessage(sender, TextFormatting.GREEN, "Dimension benchmark complete! Time: " + formatDuration(elapsed));
        sendMessage(sender, TextFormatting.AQUA, "Results saved to: " + outputFile.getAbsolutePath());
    }

    /**
     * Analyze loot drops for all mobs with performance metrics.
     * Separates results into:
     * - successful: has drops
     * - noDrops: no drops (may not have a loot table or drops are conditional)
     * - invalidEntity: entity is not a valid living entity
     * - serverSideOnly: multiplayer or no loot table manager
     * - entityConstructionFailed: entity couldn't be constructed
     * - worldCreationFailed: simulation world couldn't be created
     * - crashed: threw an exception during simulation
     */
    private void runLootAnalysis(ICommandSender sender, int samples, int simulationCount) {
        long startTime = System.nanoTime();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        List<LootPerformanceEntry> successfulMobs = new ArrayList<>();
        List<LootPerformanceEntry> noDropsMobs = new ArrayList<>();
        List<LootPerformanceEntry> invalidEntityMobs = new ArrayList<>();
        List<LootPerformanceEntry> serverSideOnlyMobs = new ArrayList<>();
        List<LootPerformanceEntry> entityConstructionFailedMobs = new ArrayList<>();
        List<LootPerformanceEntry> worldCreationFailedMobs = new ArrayList<>();
        List<LootPerformanceEntry> crashedMobs = new ArrayList<>();

        List<ResourceLocation> entityIds = new ArrayList<>();

        // Collect all living entities
        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            if (EntityLiving.class.isAssignableFrom(entry.getEntityClass())) entityIds.add(entry.getRegistryName());
        }

        int total = entityIds.size();
        int current = 0;

        for (ResourceLocation entityId : entityIds) {
            current++;
            if (current % 50 == 0) sendProgress(sender, "Loot progress: " + current + "/" + total + " mobs analyzed...");

            List<Long> timings = new ArrayList<>();
            ProfileResult lastResult = null;

            long entityStart = System.nanoTime();
            for (int i = 0; i < samples; i++) {
                ProfileResult result = DropSimulator.profileEntity(entityId, simulationCount);
                timings.add(result.durationNanos);
                lastResult = result;
            }
            long entityTimeMs = (System.nanoTime() - entityStart) / 1_000_000;

            // Log slow entities (> 50 seconds for all samples) to help diagnose performance issues
            if (entityTimeMs > 50_000) {
                SuperMobTracker.LOGGER.warn("Slow entity #{}: {} took {}ms", current, entityId, entityTimeMs);
            }

            if (lastResult == null) continue;

            int dropCount = (lastResult.result != null && lastResult.result.drops != null) ? lastResult.result.drops.size() : 0;
            LootPerformanceEntry entry = new LootPerformanceEntry(entityId.toString(), timings, lastResult.status, dropCount, lastResult.error);

            switch (lastResult.status) {
                case SUCCESS:
                    successfulMobs.add(entry);
                    break;
                case NO_DROPS:
                    noDropsMobs.add(entry);
                    break;
                case INVALID_ENTITY:
                    invalidEntityMobs.add(entry);
                    break;
                case SERVER_SIDE_ONLY:
                    serverSideOnlyMobs.add(entry);
                    break;
                case ENTITY_CONSTRUCTION_FAILED:
                    entityConstructionFailedMobs.add(entry);
                    break;
                case WORLD_CREATION_FAILED:
                    worldCreationFailedMobs.add(entry);
                    break;
                case CRASHED:
                    crashedMobs.add(entry);
                    break;
            }
        }

        // Sort each list by average time (slowest first)
        Comparator<LootPerformanceEntry> byAverageTime = (a, b) -> Double.compare(b.getAverageTime(), a.getAverageTime());
        successfulMobs.sort(byAverageTime);
        noDropsMobs.sort(byAverageTime);
        crashedMobs.sort(byAverageTime);

        // Write successful mobs to file
        File successFile = writePerformanceReport(
            "loot_performance_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt",
            "Successful Loot Analysis Performance Report",
            null,
            successfulMobs,
            samples,
            "Format: [Entity ID] - Avg: Xms, Worst: Xms, Best: Xms | Drop types: X",
            (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Drop types: %d%n",
                entry.entityId,
                entry.getAverageTime() / 1_000_000.0,
                entry.getWorstTime() / 1_000_000.0,
                entry.getBestTime() / 1_000_000.0,
                entry.dropCount),
            sender
        );
        if (successFile == null) return;

        // Write no drops mobs to file
        if (!noDropsMobs.isEmpty()) {
            noDropsMobs.sort((a, b) -> a.entityId.compareTo(b.entityId));
            writePerformanceReport(
                "loot_no_drops_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt",
                "No Drops - Loot Analysis Report",
                "These mobs have no loot drops (may not have a loot table or drops are conditional).",
                noDropsMobs,
                samples,
                null,
                (writer, entry) -> writer.println(entry.entityId),
                sender
            );
        }

        // Write invalid entity mobs to file
        if (!invalidEntityMobs.isEmpty()) {
            invalidEntityMobs.sort((a, b) -> a.entityId.compareTo(b.entityId));
            writePerformanceReport(
                "loot_invalid_entity_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt",
                "Invalid Entity - Loot Analysis Report",
                "These entities are not valid living entities.",
                invalidEntityMobs,
                samples,
                null,
                (writer, entry) -> writer.println(entry.entityId),
                sender
            );
        }

        // Write entity construction failed mobs to file
        if (!entityConstructionFailedMobs.isEmpty()) {
            writePerformanceReport(
                "loot_entity_construction_failed_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt",
                "Entity Construction Failed - Loot Analysis Report",
                "These entities couldn't be constructed for loot simulation.",
                entityConstructionFailedMobs,
                samples,
                "Format: [Entity ID] | Error: X",
                (writer, entry) -> writer.printf("%s | Error: %s%n", entry.entityId, entry.error != null ? entry.error : "Unknown"),
                sender
            );
        }

        // Write crashed mobs to file
        if (!crashedMobs.isEmpty()) {
            writePerformanceReport(
                "loot_crashed_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt",
                "Crashed - Loot Analysis Report",
                "These mobs caused exceptions during loot simulation.",
                crashedMobs,
                samples,
                "Format: [Entity ID] - Avg: Xms, Worst: Xms, Best: Xms | Error: X",
                (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Error: %s%n",
                    entry.entityId,
                    entry.getAverageTime() / 1_000_000.0,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.error != null ? entry.error : "Unknown"),
                sender
            );
        }

        long elapsed = System.nanoTime() - startTime;

        // Clear cached profiling resources to free memory
        DropSimulator.clearProfileCache();

        sendMessage(sender, TextFormatting.GREEN, "Loot analysis complete! Time: " + formatDuration(elapsed));
        sendMessage(sender, TextFormatting.AQUA, "Successful: " + successfulMobs.size() + ", No drops: " + noDropsMobs.size() + 
            ", Invalid: " + invalidEntityMobs.size() + ", Construction failed: " + entityConstructionFailedMobs.size() + 
            ", Crashed: " + crashedMobs.size());
        sendMessage(sender, TextFormatting.AQUA, "Results saved to: " + successFile.getParent());
    }

    // --- Helper classes ---

    private static class MobPerformanceEntry {
        final String entityId;
        final List<Long> timings;
        final boolean success;
        final String dimension;
        final List<String> biomes;
        final String error;

        MobPerformanceEntry(String entityId, List<Long> timings, boolean success, String dimension, List<String> biomes, String error) {
            this.entityId = entityId;
            this.timings = timings;
            this.success = success;
            this.dimension = dimension;
            this.biomes = biomes;
            this.error = error;
        }

        long getWorstTime() { return Collections.max(timings); }
        long getBestTime() { return Collections.min(timings); }
        double getAverageTime() { return timings.stream().mapToLong(Long::longValue).average().orElse(0); }
    }

    private static class DimensionPerformanceEntry {
        final int dimId;
        final String dimName;
        final int biomeCount;
        final List<Long> timings;

        DimensionPerformanceEntry(int dimId, String dimName, int biomeCount, List<Long> timings) {
            this.dimId = dimId;
            this.dimName = dimName;
            this.biomeCount = biomeCount;
            this.timings = timings;
        }

        long getWorstTime() { return Collections.max(timings); }
        long getBestTime() { return Collections.min(timings); }
        double getAverageTime() { return timings.stream().mapToLong(Long::longValue).average().orElse(0); }
    }

    private static class LootPerformanceEntry {
        final String entityId;
        final List<Long> timings;
        final ProfileResult.Status status;
        final int dropCount;
        final String error;

        LootPerformanceEntry(String entityId, List<Long> timings, ProfileResult.Status status, int dropCount, String error) {
            this.entityId = entityId;
            this.timings = timings;
            this.status = status;
            this.dropCount = dropCount;
            this.error = error;
        }

        long getWorstTime() { return Collections.max(timings); }
        long getBestTime() { return Collections.min(timings); }
        double getAverageTime() { return timings.stream().mapToLong(Long::longValue).average().orElse(0); }
    }

    /**
     * Functional interface for writing entries to a PrintWriter.
     * @param <T> the type of entry to write
     */
    @FunctionalInterface
    private interface EntryWriter<T> {
        void write(PrintWriter writer, T entry);
    }

    /**
     * Writes a performance report to a file.
     *
     * @param <T> the type of entries in the report
     * @param filename the output filename
     * @param title the report title
     * @param description optional description (can be null)
     * @param entries the list of entries to write
     * @param samples the number of samples per entry
     * @param formatHint the format hint line describing the entry format
     * @param entryWriter the function to write each entry
     * @param sender the command sender for error messages
     * @return the created file, or null if writing failed
     */
    private <T> File writePerformanceReport(String filename, String title, String description,
                                            List<T> entries, int samples, String formatHint,
                                            EntryWriter<T> entryWriter, ICommandSender sender) {
        File file = getOutputFile(filename);
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("=== " + title + " ===");
            if (description != null) writer.println(description);

            writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("Total: " + entries.size());
            writer.println("Samples per mob: " + samples);
            writer.println();

            if (formatHint != null) {
                writer.println(formatHint);
                writer.println("Sorted by average time (slowest first)");
                writer.println();
            }

            for (T entry : entries) entryWriter.write(writer, entry);

            return file;
        } catch (IOException e) {
            sendMessage(sender, TextFormatting.RED, "Failed to write " + filename + ": " + e.getMessage());
            return null;
        }
    }

    // --- Utility methods ---

    private File getOutputFile(String filename) {
        File dir = new File(OUTPUT_DIR);
        if (!dir.exists()) dir.mkdirs();

        return new File(dir, filename);
    }

    private void sendMessage(ICommandSender sender, TextFormatting color, String message) {
        // Send on main thread to avoid concurrency issues
        if (sender.getServer() != null) {
            sender.getServer().addScheduledTask(() -> {
                TextComponentString text = new TextComponentString("[SMT] " + message);
                text.getStyle().setColor(color);
                sender.sendMessage(text);
            });
        } else {
            SuperMobTracker.LOGGER.info(message);
        }
    }

    /**
     * Send a progress message to the player's action bar (above hotbar).
     * These disappear after a short time and don't clutter the chat.
     */
    private void sendProgress(ICommandSender sender, String message) {
        if (sender.getServer() != null && sender instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            sender.getServer().addScheduledTask(() -> {
                TextComponentString text = new TextComponentString("[SMT] " + message);
                text.getStyle().setColor(TextFormatting.DARK_GREEN);
                player.sendStatusMessage(text, true);
            });
        } else {
            SuperMobTracker.LOGGER.info(message);
        }
    }

    /**
     * Format a duration in nanoseconds to a human-readable string (hours, minutes, seconds).
     * Skips zero components.
     */
    private String formatDuration(long nanos) {
        long totalSeconds = nanos / 1_000_000_000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long millis = (nanos % 1_000_000_000L) / 1_000_000L;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.length() == 0) {
            if (millis > 0 && hours == 0) {
                sb.append(String.format("%d.%03ds", seconds, millis));
            } else {
                sb.append(seconds).append("s");
            }
        }

        return sb.toString().trim();
    }
}
