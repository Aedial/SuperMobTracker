package com.supermobtracker.drops;

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

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.drops.DropSimulator.ProfileResult;
import com.supermobtracker.network.NetworkHandler;
import com.supermobtracker.network.PacketLootAnalysisProgress;
import com.supermobtracker.network.PacketLootAnalysisResult;


/**
 * Server-side loot analysis runner.
 * Performs the loot analysis on the server and sends results back to the client.
 */
public class LootAnalysisRunner {

    private static final String OUTPUT_DIR = "supermobtracker";

    /**
     * Run a full loot analysis on the server and send results back to the client.
     * This method spawns a new thread to avoid blocking the server.
     *
     * @param player The player who requested the analysis
     * @param world The WorldServer to use for simulation
     * @param samples Number of profiling samples per entity
     * @param simulationCount Number of kill simulations per sample
     */
    public static void runServerAnalysis(EntityPlayerMP player, WorldServer world, int samples, int simulationCount) {
        new Thread(() -> {
            try {
                runAnalysisInternal(player, world, samples, simulationCount);
            } catch (Exception e) {
                SuperMobTracker.LOGGER.error("Loot analysis failed", e);
                sendResult(player, new PacketLootAnalysisResult("Analysis failed: " + e.getMessage()));
            }
        }, "SMT-LootAnalysis-Server").start();
    }

    private static void runAnalysisInternal(EntityPlayerMP player, WorldServer world, int samples, int simulationCount) {
        long startTime = System.nanoTime();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        List<LootPerformanceEntry> successfulMobs = new ArrayList<>();
        List<LootPerformanceEntry> noDropsMobs = new ArrayList<>();
        List<LootPerformanceEntry> invalidEntityMobs = new ArrayList<>();
        List<LootPerformanceEntry> entityConstructionFailedMobs = new ArrayList<>();
        List<LootPerformanceEntry> crashedMobs = new ArrayList<>();

        List<ResourceLocation> entityIds = new ArrayList<>();

        // Collect all living entities
        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            if (EntityLiving.class.isAssignableFrom(entry.getEntityClass())) {
                entityIds.add(entry.getRegistryName());
            }
        }

        int total = entityIds.size();
        int current = 0;

        for (ResourceLocation entityId : entityIds) {
            current++;

            // Send progress updates every 50 mobs
            if (current % 50 == 0) sendProgress(player, current, total, entityId.toString());

            List<Long> timings = new ArrayList<>();
            ProfileResult lastResult = null;

            for (int i = 0; i < samples; i++) {
                ProfileResult result = DropSimulator.profileEntityServer(entityId, simulationCount, world);
                timings.add(result.durationNanos);
                lastResult = result;
            }

            if (lastResult == null) continue;

            int dropCount = (lastResult.result != null && lastResult.result.drops != null) ?
                lastResult.result.drops.size() : 0;
            LootPerformanceEntry entry = new LootPerformanceEntry(
                entityId.toString(), timings, lastResult.status, dropCount, lastResult.error);

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
                case ENTITY_CONSTRUCTION_FAILED:
                    entityConstructionFailedMobs.add(entry);
                    break;
                case CRASHED:
                case WORLD_CREATION_FAILED:
                case SERVER_SIDE_ONLY:
                    crashedMobs.add(entry);
                    break;
            }
        }

        // Sort each list by average time (slowest first)
        Comparator<LootPerformanceEntry> byAverageTime = (a, b) ->
            Double.compare(b.getAverageTime(), a.getAverageTime());
        successfulMobs.sort(byAverageTime);
        noDropsMobs.sort(byAverageTime);
        crashedMobs.sort(byAverageTime);

        // Write results to files on server
        String outputPath = writeResults(timestamp, samples, simulationCount,
            successfulMobs, noDropsMobs, invalidEntityMobs, entityConstructionFailedMobs, crashedMobs);

        // Clear cached profiling resources to free memory
        DropSimulator.clearProfileCache();

        // Send final result back to client
        PacketLootAnalysisResult result = new PacketLootAnalysisResult(
            successfulMobs.size(),
            noDropsMobs.size(),
            invalidEntityMobs.size(),
            entityConstructionFailedMobs.size(),
            crashedMobs.size(),
            outputPath
        );
        sendResult(player, result);
    }

    private static String writeResults(String timestamp, int samples, int simulationCount,
                                        List<LootPerformanceEntry> successfulMobs,
                                        List<LootPerformanceEntry> noDropsMobs,
                                        List<LootPerformanceEntry> invalidEntityMobs,
                                        List<LootPerformanceEntry> entityConstructionFailedMobs,
                                        List<LootPerformanceEntry> crashedMobs) {

        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) outputDir.mkdirs();

        String baseFilename = "loot_performance_" + samples + "samples_" + simulationCount + "sims_" + timestamp;

        // Write successful mobs
        File successFile = new File(outputDir, baseFilename + ".txt");
        writePerformanceReport(successFile, "Successful Loot Analysis Performance Report",
            null, successfulMobs, samples,
            (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Drop types: %d%n",
                entry.entityId,
                entry.getAverageTime() / 1_000_000.0,
                entry.getWorstTime() / 1_000_000.0,
                entry.getBestTime() / 1_000_000.0,
                entry.dropCount)
        );

        // Write no drops mobs
        if (!noDropsMobs.isEmpty()) {
            noDropsMobs.sort((a, b) -> a.entityId.compareTo(b.entityId));
            File noDropsFile = new File(outputDir, "loot_no_drops_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt");
            writePerformanceReport(noDropsFile, "No Drops - Loot Analysis Report",
                "These mobs have no loot drops (may not have a loot table or drops are conditional).",
                noDropsMobs, samples,
                (writer, entry) -> writer.println(entry.entityId)
            );
        }

        // Write invalid entity mobs
        if (!invalidEntityMobs.isEmpty()) {
            invalidEntityMobs.sort((a, b) -> a.entityId.compareTo(b.entityId));
            File invalidFile = new File(outputDir, "loot_invalid_entity_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt");
            writePerformanceReport(invalidFile, "Invalid Entity - Loot Analysis Report",
                "These entities are not valid living entities.",
                invalidEntityMobs, samples,
                (writer, entry) -> writer.println(entry.entityId)
            );
        }

        // Write entity construction failed mobs
        if (!entityConstructionFailedMobs.isEmpty()) {
            File failedFile = new File(outputDir, "loot_entity_construction_failed_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt");
            writePerformanceReport(failedFile, "Entity Construction Failed - Loot Analysis Report",
                "These entities couldn't be constructed for loot simulation.",
                entityConstructionFailedMobs, samples,
                (writer, entry) -> writer.printf("%s | Error: %s%n", entry.entityId, entry.error != null ? entry.error : "Unknown")
            );
        }

        // Write crashed mobs
        if (!crashedMobs.isEmpty()) {
            File crashedFile = new File(outputDir, "loot_crashed_" + samples + "samples_" + simulationCount + "sims_" + timestamp + ".txt");
            writePerformanceReport(crashedFile, "Crashed - Loot Analysis Report",
                "These mobs caused exceptions during loot simulation.",
                crashedMobs, samples,
                (writer, entry) -> writer.printf("%s - Avg: %.2fms, Worst: %.2fms, Best: %.2fms | Error: %s%n",
                    entry.entityId,
                    entry.getAverageTime() / 1_000_000.0,
                    entry.getWorstTime() / 1_000_000.0,
                    entry.getBestTime() / 1_000_000.0,
                    entry.error != null ? entry.error : "Unknown")
            );
        }

        return successFile.getAbsolutePath();
    }

    private static void writePerformanceReport(File file, String title, String description,
                                               List<LootPerformanceEntry> entries, int samples,
                                               EntryWriter writer) {
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            out.println("=== " + title + " ===");
            out.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            out.println("Samples: " + samples);
            out.println("Total entries: " + entries.size());

            if (description != null) {
                out.println();
                out.println(description);
            }

            out.println();

            for (LootPerformanceEntry entry : entries) writer.write(out, entry);
        } catch (IOException e) {
            SuperMobTracker.LOGGER.error("Failed to write performance report: " + file.getName(), e);
        }
    }

    private static void sendProgress(EntityPlayerMP player, int current, int total, String currentEntity) {
        SuperMobTracker.LOGGER.info(String.format("Loot Analysis Progress: %d/%d - %s", current, total, currentEntity));

        // FIXME: I think sometimes player.connection is null here in multiplayer?
        //        It doesn't seem to send the progress updates.
        if (player.connection != null) {
            NetworkHandler.INSTANCE.sendTo(new PacketLootAnalysisProgress(current, total, currentEntity), player);
        } else {
            SuperMobTracker.LOGGER.warn("Cannot send loot analysis progress: player connection is null");
        }
    }

    private static void sendResult(EntityPlayerMP player, PacketLootAnalysisResult result) {
        SuperMobTracker.LOGGER.info("Analyis complete, sending results to player");

        if (player.connection != null) {
            NetworkHandler.INSTANCE.sendTo(result, player);
        } else {
            SuperMobTracker.LOGGER.warn("Cannot send loot analysis result: player connection is null");
        }
    }

    @FunctionalInterface
    private interface EntryWriter {
        void write(PrintWriter writer, LootPerformanceEntry entry);
    }

    /**
     * Entry for tracking loot performance statistics.
     */
    public static class LootPerformanceEntry {
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

        long getWorstTime() {
            return Collections.max(timings);
        }

        long getBestTime() {
            return Collections.min(timings);
        }

        double getAverageTime() {
            return timings.stream().mapToLong(Long::longValue).average().orElse(0);
        }
    }
}
