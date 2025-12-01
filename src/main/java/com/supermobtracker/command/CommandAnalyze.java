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
import java.util.List;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.EntityLiving;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.spawn.BiomeDimensionMapper;
import com.supermobtracker.spawn.ConditionUtils;
import com.supermobtracker.spawn.SpawnConditionAnalyzer;


/**
 * Command to analyze all mobs and export results to files.
 * Usage:
 *   /smtanalyze - Run all analyses with default parameters
 *   /smtanalyze mobs [samples] - Analyze all mobs with performance metrics
 *   /smtanalyze dimension [samples] [extendedCount] [numGrids] - Benchmark dimension mapping
 */
public class CommandAnalyze extends CommandBase {

    private static final int DEFAULT_SAMPLES = 10;
    private static final String OUTPUT_DIR = "supermobtracker";

    @Override
    public String getName() {
        return "smtanalyze";
    }

    // TODO: add localization support? I don't see any anyone really using that, beside myself.
    @Override
    public String getUsage(ICommandSender sender) {
        return "/smtanalyze [mobs|dimension] [samples] [extendedCount] [numGrids]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // Allow all players to use this command
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "mobs", "dimension");

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
            } else if (result.failed()) {
                failedMobs.add(new MobPerformanceEntry(entityId.toString(), timings, false, result.dimension, result.biomes, null));
            } else {
                successfulMobs.add(new MobPerformanceEntry(entityId.toString(), timings, true, result.dimension, result.biomes, null));
            }
        }

        // Sort each list by worst (slowest) time first
        Comparator<MobPerformanceEntry> byWorstTime = (a, b) -> Long.compare(b.getWorstTime(), a.getWorstTime());
        successfulMobs.sort(byWorstTime);
        failedMobs.sort(byWorstTime);
        noDimensionMobs.sort(byWorstTime);
        noNativeBiomeMobs.sort(byWorstTime);
        crashedMobs.sort(byWorstTime);

        // TODO: Refactor file writing into a common method
        // Write successful mobs to file
        File successFile = getOutputFile("mob_performance_" + samples + "samples_" + timestamp + ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(successFile))) {
            writer.println("=== Successful Mob Analysis Performance Report ===");
            writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("Total successful: " + successfulMobs.size());
            writer.println("Samples per mob: " + samples);
            writer.println();
            writer.println("Format: [Entity ID] - Worst: Xms, Best: Xms, Avg: Xms | Dimension: X");
            writer.println("Sorted by worst time (slowest first)");
            writer.println();

            for (MobPerformanceEntry entry : successfulMobs) {
                writer.printf("%s - Worst: %.2fms, Best: %.2fms, Avg: %.2fms | Dimension: %s%n",
                    entry.entityId,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.getAverageTime() / 1_000_000.0,
                    entry.dimension != null ? entry.dimension : "?"
                );
            }
        } catch (IOException e) {
            sendMessage(sender, TextFormatting.RED, "Failed to write output file: " + e.getMessage());
            ConditionUtils.suppressProfiling(false);
            return;
        }

        // Write failed mobs to file
        if (!failedMobs.isEmpty()) {
            File failedFile = getOutputFile("failed_performance_" + samples + "samples_" + timestamp + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(failedFile))) {
                writer.println("=== Failed Mob Analysis Performance Report ===");
                writer.println("These mobs have native biomes but spawn conditions could not be determined.");
                writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                writer.println("Total failed: " + failedMobs.size());
                writer.println("Samples per mob: " + samples);
                writer.println();
                writer.println("Format: [Entity ID] - Worst: Xms, Best: Xms, Avg: Xms | Dimension: X");
                writer.println("Sorted by worst time (slowest first)");
                writer.println();

                for (MobPerformanceEntry entry : failedMobs) {
                    writer.printf("%s - Worst: %.2fms, Best: %.2fms, Avg: %.2fms | Dimension: %s%n",
                        entry.entityId,
                        entry.getWorstTime() / 1_000_000.0,
                        entry.getBestTime() / 1_000_000.0,
                        entry.getAverageTime() / 1_000_000.0,
                        entry.dimension != null ? entry.dimension : "?"
                    );
                }
            } catch (IOException e) {
                sendMessage(sender, TextFormatting.RED, "Failed to write failed file: " + e.getMessage());
            }
        }

        // Write crashed mobs to file
        if (!crashedMobs.isEmpty()) {
            File crashedFile = getOutputFile("crashed_performance_" + samples + "samples_" + timestamp + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(crashedFile))) {
                writer.println("=== Crashed Mob Analysis Performance Report ===");
                writer.println("These mobs caused exceptions during spawn condition analysis.");
                writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                writer.println("Total crashed: " + crashedMobs.size());
                writer.println("Samples per mob: " + samples);
                writer.println();
                writer.println("Format: [Entity ID] - Worst: Xms, Best: Xms, Avg: Xms | Error: X");
                writer.println("Sorted by worst time (slowest first)");
                writer.println();

                for (MobPerformanceEntry entry : crashedMobs) {
                    writer.printf("%s - Worst: %.2fms, Best: %.2fms, Avg: %.2fms | Error: %s%n",
                        entry.entityId,
                        entry.getWorstTime() / 1_000_000.0,
                        entry.getBestTime() / 1_000_000.0,
                        entry.getAverageTime() / 1_000_000.0,
                        entry.error
                    );
                }
            } catch (IOException e) {
                sendMessage(sender, TextFormatting.RED, "Failed to write crashed file: " + e.getMessage());
            }
        }

        // Write noDimension mobs to file (biomes couldn't be mapped to any dimension)
        if (!noDimensionMobs.isEmpty()) {
            File noDimFile = getOutputFile("no_dimension_" + samples + "samples_" + timestamp + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(noDimFile))) {
                writer.println("=== No Dimension Mapping - Mob Analysis Report ===");
                writer.println("These mobs have native biomes that couldn't be mapped to any dimension.");
                writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                writer.println("Total: " + noDimensionMobs.size());
                writer.println("Samples per mob: " + samples);
                writer.println();
                writer.println("Format: [Entity ID] - Worst: Xms, Best: Xms, Avg: Xms | Biomes: [list]");
                writer.println("Sorted by worst time (slowest first)");
                writer.println();

                for (MobPerformanceEntry entry : noDimensionMobs) {
                    String biomeList = entry.biomes != null ? String.join(", ", entry.biomes) : "?";
                    writer.printf("%s - Worst: %.2fms, Best: %.2fms, Avg: %.2fms | Biomes: %s%n",
                        entry.entityId,
                        entry.getWorstTime() / 1_000_000.0,
                        entry.getBestTime() / 1_000_000.0,
                        entry.getAverageTime() / 1_000_000.0,
                        biomeList
                    );
                }
            } catch (IOException e) {
                sendMessage(sender, TextFormatting.RED, "Failed to write no-dimension file: " + e.getMessage());
            }
        }

        // Write noNativeBiomes mobs to file (don't spawn naturally)
        if (!noNativeBiomeMobs.isEmpty()) {
            File noBiomesFile = getOutputFile("no_native_biomes_" + samples + "samples_" + timestamp + ".txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(noBiomesFile))) {
                writer.println("=== No Native Biomes - Mob Analysis Report ===");
                writer.println("These mobs don't have native biomes and cannot spawn naturally.");
                writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                writer.println("Total: " + noNativeBiomeMobs.size());
                writer.println("Samples per mob: " + samples);
                writer.println();
                writer.println("Entity IDs (alphabetically sorted):");
                writer.println();

                // Sort alphabetically for this list since timing is irrelevant
                noNativeBiomeMobs.sort((a, b) -> a.entityId.compareTo(b.entityId));
                for (MobPerformanceEntry entry : noNativeBiomeMobs) {
                    writer.println(entry.entityId);
                }
            } catch (IOException e) {
                sendMessage(sender, TextFormatting.RED, "Failed to write no-biomes file: " + e.getMessage());
            }
        }

        ConditionUtils.suppressProfiling(false);
        long elapsed = System.nanoTime() - startTime;
        sendMessage(sender, TextFormatting.GREEN, "Mob analysis complete! Time: " + formatDuration(elapsed));
        sendMessage(sender, TextFormatting.AQUA, "Successful: " + successfulMobs.size() + ", Failed: " + failedMobs.size() + 
            ", No dimension: " + noDimensionMobs.size() + ", Crashed: " + crashedMobs.size() + ", No biomes: " + noNativeBiomeMobs.size());
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
        Map<Integer, List<Long>> perDimensionTimings = new java.util.HashMap<>();

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
            writer.printf("Worst: %.2fms, Best: %.2fms, Avg: %.2fms%n",
                Collections.max(totalTimings) / 1_000_000.0,
                Collections.min(totalTimings) / 1_000_000.0,
                totalTimings.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0
            );
            writer.println();

            // Per-dimension performance
            writer.println("=== Per-Dimension Performance ===");
            writer.println("Sorted by worst time (slowest first)");
            writer.println();

            for (DimensionPerformanceEntry entry : entries) {
                writer.printf("%d (%s) - Worst: %.2fms, Best: %.2fms, Avg: %.2fms | Biomes: %d%n",
                    entry.dimId,
                    entry.dimName,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.getAverageTime() / 1_000_000.0,
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
        if (sender.getServer() != null && sender instanceof net.minecraft.entity.player.EntityPlayerMP) {
            net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) sender;
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
