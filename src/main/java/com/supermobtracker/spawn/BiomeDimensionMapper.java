package com.supermobtracker.spawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;

import com.supermobtracker.SuperMobTracker;

/**
 * Maps biomes to their available dimensions.
 * Used to determine which dimension a mob can spawn in based on its native biomes.
 * 
 * Strategy per dimension:
 * 1. Create a minimal fake world to properly initialize the WorldProvider and BiomeProvider
 * 2. If BiomeProviderSingle: use that single biome, then sample to verify/expand
 * 3. If BiomeProvider has getBiomesToSpawnIn(): use those, then sample to verify/expand
 * 4. Sample using getBiomesForGeneration() for batched retrieval across multiple grids
 * 5. If any new biomes found during small sample, start background sampling that expands outwards
 */
public class BiomeDimensionMapper {

    private static final int SAMPLE_COUNT_INITIAL = 100;
    private static final int DEFAULT_SAMPLE_COUNT_EXTENDED = 2000;
    private static final int GRID_SIZE = 512;
    private static final int GRID_SPACING = 2048;
    private static final int DEFAULT_NUM_GRIDS = 16;
    private static final int BATCH_SIZE = 16;

    // Background sampling constants
    private static final int BACKGROUND_SAMPLE_INTERVAL_MS = 1000; // 1 sample per second
    private static final int BACKGROUND_SAMPLE_DURATION_MS = 5 * 60 * 1000; // 5 minutes
    private static final int BACKGROUND_GRID_EXPANSION = 4096; // How much to expand per ring

    // Current parameters (can be customized for benchmarking)
    private static int numGrids = DEFAULT_NUM_GRIDS;

    private static Map<String, List<Integer>> biomeToDimensions = null;
    private static Map<Integer, Set<String>> dimensionToBiomes = null;
    private static List<Integer> sortedDimensionIds = null;
    private static boolean initialized = false;

    // Background sampling state
    private static ScheduledExecutorService backgroundSampler = null;
    private static Map<Integer, BackgroundSampleTask> activeBackgroundTasks = new ConcurrentHashMap<>();

    /** Get default extended sample count (legacy, background sampling now used) */
    public static int getDefaultExtendedCount() { return DEFAULT_SAMPLE_COUNT_EXTENDED; }

    /** Get default number of grids */
    public static int getDefaultNumGrids() { return DEFAULT_NUM_GRIDS; }

    /**
     * A minimal fake world used solely to initialize WorldProviders and get their BiomeProviders.
     * This is necessary because WorldProvider.getBiomeProvider() returns null until setWorld() is called.
     * 
     * IMPORTANT: We use a cloned WorldInfo to prevent any modifications from affecting the real world.
     * Some mods (like BiomesOPlenty + Lost Cities) check/modify the terrain type during initialization.
     */
    private static class MinimalWorld extends World {
        private final WorldProvider targetProvider;
        private BiomeProvider cachedBiomeProvider = null;
        private Exception initException = null;

        public MinimalWorld(WorldInfo info, WorldProvider provider) {
            super(null, info, provider, new Profiler(), true);
            this.targetProvider = provider;

            try {
                this.provider.setWorld(this);
                this.cachedBiomeProvider = targetProvider.getBiomeProvider();
            } catch (Exception e) {
                // Provider initialization failed - likely due to WorldType mismatch
                // Store the exception for logging but don't rethrow
                this.initException = e;
            }
        }

        public BiomeProvider getInitializedBiomeProvider() {
            return cachedBiomeProvider;
        }

        public Exception getInitException() {
            return initException;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return false;
        }
    }

    private static Map<Integer, Long> dimensionTimings = new HashMap<>();

    /**
     * Initialize the biome-dimension mapping.
     * Should be called once when first needed (e.g., at first GUI open).
     */
    public static void init() {
        initWithParams(DEFAULT_NUM_GRIDS);
    }

    /**
     * Initialize with custom parameters (for benchmarking).
     * @param extendedCount Ignored (legacy parameter, background sampling now used)
     * @param grids Number of grids to sample initially
     */
    public static void initWithParams(int extendedCount, int grids) {
        initWithParams(grids);
    }

    /**
     * Initialize with custom parameters (for benchmarking).
     * @param grids Number of grids to sample initially
     */
    public static void initWithParams(int grids) {
        if (initialized) return;

        numGrids = grids;

        long totalStartTime = System.nanoTime();

        biomeToDimensions = new HashMap<>();
        dimensionToBiomes = new HashMap<>();
        dimensionTimings = new HashMap<>();
        sortedDimensionIds = new ArrayList<>();

        Integer[] registeredDimensions = DimensionManager.getStaticDimensionIDs();
        for (Integer dimId : registeredDimensions) sortedDimensionIds.add(dimId);

        // Sort by abs(id): 0, -1, 1, -2, 2, etc.
        sortedDimensionIds.sort(Comparator.comparingInt(Math::abs));

        // Get a base WorldInfo from the current world if available
        WorldInfo baseWorldInfo = getBaseWorldInfo();

        for (Integer dimId : sortedDimensionIds) {
            long dimStartTime = System.nanoTime();
            Set<String> biomes = getBiomesForDimension(dimId, baseWorldInfo);
            long dimElapsed = System.nanoTime() - dimStartTime;

            dimensionToBiomes.put(dimId, biomes);
            dimensionTimings.put(dimId, dimElapsed);

            for (String biome : biomes) biomeToDimensions.computeIfAbsent(biome, k -> new ArrayList<>()).add(dimId);

            if (ConditionUtils.isProfilingEnabled()) {
                String dimName = getDimensionName(dimId);
                SuperMobTracker.LOGGER.info("  Dimension " + dimId + " (" + dimName + "): " + biomes.size() + " biomes in " + (dimElapsed / 1_000_000.0) + "ms");
            }
        }

        initialized = true;

        if (ConditionUtils.isProfilingEnabled()) {
            long totalElapsed = System.nanoTime() - totalStartTime;
            SuperMobTracker.LOGGER.info("BiomeDimensionMapper init took " + (totalElapsed / 1_000_000.0) + "ms total");
            SuperMobTracker.LOGGER.info("Found " + sortedDimensionIds.size() + " dimensions, mapped " + biomeToDimensions.size() + " biomes");
        }
    }

    /**
     * Get per-dimension initialization timings in nanoseconds.
     */
    public static Map<Integer, Long> getDimensionTimings() {
        return new HashMap<>(dimensionTimings);
    }

    /**
     * Get base WorldInfo from current loaded world, or create a minimal one.
     * 
     * IMPORTANT: We clone the WorldInfo instead of returning the original to prevent
     * any potential modifications from affecting the actual world. Some mod providers
     * may inadvertently modify the WorldInfo during initialization.
     */
    private static WorldInfo getBaseWorldInfo() {
        // Try to get from overworld first
        WorldServer overworld = DimensionManager.getWorld(0);
        // Clone the WorldInfo to prevent any modifications from affecting the actual world
        if (overworld != null) return cloneWorldInfo(overworld.getWorldInfo());

        // Create minimal WorldInfo with DEFAULT type - this is safe as a fallback
        // since we wrap provider initialization in try-catch
        WorldSettings settings = new WorldSettings(0, net.minecraft.world.GameType.SURVIVAL, true, false, WorldType.DEFAULT);
        return new WorldInfo(settings, "BiomeMapper");
    }

    /**
     * Create a safe copy of WorldInfo to prevent modifications to the original.
     * This is critical because some mod providers (like BiomesOPlenty with Lost Cities)
     * may modify the WorldInfo's terrain type during initialization.
     */
    private static WorldInfo cloneWorldInfo(WorldInfo original) {
        WorldSettings settings = new WorldSettings(
            original.getSeed(),
            original.getGameType(),
            original.isMapFeaturesEnabled(),
            original.isHardcoreModeEnabled(),
            original.getTerrainType()
        );
        settings.setGeneratorOptions(original.getGeneratorOptions());
        WorldInfo cloned = new WorldInfo(settings, original.getWorldName());

        return cloned;
    }

    /**
     * Get biomes available in a dimension by creating a minimal world to initialize its provider.
     */
    private static Set<String> getBiomesForDimension(int dimId, WorldInfo baseWorldInfo) {
        Set<String> biomes = new HashSet<>();

        try {
            // First try to get from already loaded world
            WorldServer loadedWorld = DimensionManager.getWorld(dimId);
            BiomeProvider biomeProvider = null;

            if (loadedWorld != null) {
                biomeProvider = loadedWorld.getBiomeProvider();
            } else {
                // Create a minimal world to initialize the provider
                DimensionType dimType = DimensionManager.getProviderType(dimId);
                if (dimType == null) {
                    if (ConditionUtils.isProfilingEnabled()) {
                        SuperMobTracker.LOGGER.info("    No DimensionType for dimension " + dimId);
                    }

                    return biomes;
                }

                WorldProvider provider = dimType.createDimension();
                if (provider == null) {
                    if (ConditionUtils.isProfilingEnabled()) {
                        SuperMobTracker.LOGGER.info("    Could not create WorldProvider for dimension " + dimId);
                    }

                    return biomes;
                }

                // Create minimal world to initialize the provider
                // baseWorldInfo is already cloned with the correct WorldType from the overworld
                // This means if the player is in a BoP world, we use WorldType.BIOMESOP automatically
                MinimalWorld minimalWorld = new MinimalWorld(baseWorldInfo, provider);
                biomeProvider = minimalWorld.getInitializedBiomeProvider();

                // If initialization failed, try with DEFAULT WorldType as fallback
                // (for dimensions that don't care about WorldType, or vanilla dimensions)
                Exception initException = minimalWorld.getInitException();
                if (initException != null) {
                    if (ConditionUtils.isProfilingEnabled()) {
                        SuperMobTracker.LOGGER.info("    Provider init failed with current WorldType, trying DEFAULT: " +
                            initException.getMessage());
                    }

                    // Try again with DEFAULT WorldType
                    WorldSettings defaultSettings = new WorldSettings(
                        baseWorldInfo.getSeed(),
                        baseWorldInfo.getGameType(),
                        baseWorldInfo.isMapFeaturesEnabled(),
                        baseWorldInfo.isHardcoreModeEnabled(),
                        WorldType.DEFAULT
                    );
                    WorldInfo defaultWorldInfo = new WorldInfo(defaultSettings, "BiomeMapper");

                    // Need a fresh provider instance
                    provider = dimType.createDimension();
                    if (provider != null) {
                        minimalWorld = new MinimalWorld(defaultWorldInfo, provider);
                        biomeProvider = minimalWorld.getInitializedBiomeProvider();

                        if (minimalWorld.getInitException() != null && ConditionUtils.isProfilingEnabled()) {
                            SuperMobTracker.LOGGER.info("    Provider init also failed with DEFAULT WorldType: " +
                                minimalWorld.getInitException().getMessage());
                        }
                    }
                }
            }

            if (biomeProvider == null) {
                if (ConditionUtils.isProfilingEnabled()) SuperMobTracker.LOGGER.info("    No BiomeProvider available for dimension " + dimId);

                return biomes;
            }

            int initialCount = 0;
            boolean needsExtendedSample = true;

            // Strategy 1: BiomeProviderSingle - single biome dimension
            if (biomeProvider instanceof BiomeProviderSingle) {
                Biome singleBiome = biomeProvider.getBiome(BlockPos.ORIGIN);
                if (singleBiome != null && singleBiome.getRegistryName() != null) {
                    biomes.add(singleBiome.getRegistryName().toString());
                    initialCount = biomes.size();
                }

                if (ConditionUtils.isProfilingEnabled()) SuperMobTracker.LOGGER.info("    BiomeProviderSingle: " + biomes);

                // Even for single biome, sample to verify
                int newFound = sampleBiomesMultiGrid(biomeProvider, biomes, SAMPLE_COUNT_INITIAL);
                needsExtendedSample = newFound > 0;

            } else {
                // Strategy 2: getBiomesToSpawnIn()
                List<Biome> spawnBiomes = biomeProvider.getBiomesToSpawnIn();
                if (spawnBiomes != null && !spawnBiomes.isEmpty()) {
                    for (Biome b : spawnBiomes) {
                        if (b != null && b.getRegistryName() != null) biomes.add(b.getRegistryName().toString());
                    }

                    if (ConditionUtils.isProfilingEnabled()) SuperMobTracker.LOGGER.info("    getBiomesToSpawnIn(): " + biomes.size() + " biomes");
                }

                initialCount = biomes.size();

                // Sample to find additional biomes
                int newFound = sampleBiomesMultiGrid(biomeProvider, biomes, SAMPLE_COUNT_INITIAL);
                needsExtendedSample = newFound > 0;
            }

            // Strategy 3: If we found new biomes during initial sample, start background sampling
            if (needsExtendedSample) {
                if (ConditionUtils.isProfilingEnabled()) {
                    SuperMobTracker.LOGGER.info("    Found new biomes during initial sample, starting background sampling");
                }
                startBackgroundSampling(dimId, biomeProvider, biomes);
            }

            if (ConditionUtils.isProfilingEnabled() && biomes.size() > initialCount) {
                SuperMobTracker.LOGGER.info("    Sampling found " + (biomes.size() - initialCount) + " additional biomes");
            }

        } catch (Exception e) {
            if (ConditionUtils.isProfilingEnabled()) {
                SuperMobTracker.LOGGER.warn("Failed to get biomes for dimension " + dimId + ": " + e.getMessage());
            }
        }

        return biomes;
    }

    /**
     * Sample biomes across multiple grids spread across the dimension.
     * Uses getBiomesForGeneration for batched retrieval (much faster than individual calls).
     * 
     * @return Number of NEW biomes found during this sampling
     */
    private static int sampleBiomesMultiGrid(BiomeProvider biomeProvider, Set<String> biomes, int totalSamples) {
        Random random = new Random(42);
        int initialSize = biomes.size();
        int batchesPerGrid = Math.max(1, totalSamples / (numGrids * BATCH_SIZE * BATCH_SIZE));

        for (int grid = 0; grid < numGrids; grid++) {
            // Each grid is centered at a different location
            int gridCenterX = (grid % 4 - 2) * GRID_SPACING;
            int gridCenterZ = (grid / 4 - 2) * GRID_SPACING;

            for (int batch = 0; batch < batchesPerGrid; batch++) {
                // Sample a 16x16 area at random position within the grid
                int batchX = gridCenterX + random.nextInt(GRID_SIZE) - GRID_SIZE / 2;
                int batchZ = gridCenterZ + random.nextInt(GRID_SIZE) - GRID_SIZE / 2;

                try {
                    // Use getBiomesForGeneration for batched retrieval - gets 256 biomes in one call
                    Biome[] batchBiomes = biomeProvider.getBiomesForGeneration(null, batchX, batchZ, BATCH_SIZE, BATCH_SIZE);
                    if (batchBiomes != null) {
                        for (Biome biome : batchBiomes) {
                            if (biome != null && biome.getRegistryName() != null) biomes.add(biome.getRegistryName().toString());
                        }
                    }
                } catch (Exception ignored) {
                    // Some providers may not support getBiomesForGeneration, fall back to single point
                    try {
                        Biome biome = biomeProvider.getBiome(new BlockPos(batchX, 64, batchZ));
                        if (biome != null && biome.getRegistryName() != null) biomes.add(biome.getRegistryName().toString());
                    } catch (Exception ignored2) {
                        // Skip this position
                    }
                }
            }
        }

        return biomes.size() - initialSize;
    }

    /**
     * Find the first dimension (sorted by abs(id)) that contains at least one of the given biomes.
     *
     * @param biomeNames List of biome registry names to search for
     * @param fallbackDimId Dimension to fall back to if no match found
     * @return The dimension ID, or Integer.MIN_VALUE if none found
     */
    public static int findDimensionForBiomes(List<String> biomeNames, int fallbackDimId) {
        init();

        if (biomeNames == null || biomeNames.isEmpty()) return fallbackDimId;

        for (Integer dimId : sortedDimensionIds) {
            Set<String> dimBiomes = dimensionToBiomes.get(dimId);
            if (dimBiomes == null || dimBiomes.isEmpty()) continue;

            for (String biome : biomeNames) {
                if (dimBiomes.contains(biome)) return dimId;
            }
        }

        // Return special value to indicate no dimension found
        return Integer.MIN_VALUE;
    }

    /**
     * Check if the biomes were found in any dimension.
     */
    public static boolean foundDimensionForBiomes(List<String> biomeNames) {
        int result = findDimensionForBiomes(biomeNames, Integer.MIN_VALUE);
        return result != Integer.MIN_VALUE;
    }

    /**
     * Get the name of a dimension by its ID.
     */
    public static String getDimensionName(int dimId) {
        try {
            DimensionType type = DimensionManager.getProviderType(dimId);
            if (type != null) return type.getName();
        } catch (Exception ignored) {}

        return "dimension_" + dimId;
    }

    /**
     * Get the WorldProvider for a dimension by its ID.
     */
    public static WorldProvider getProviderForDimension(int dimId) {
        // Try to get from loaded world first
        WorldServer worldServer = DimensionManager.getWorld(dimId);
        if (worldServer != null) return worldServer.provider;

        // Fall back to creating a new provider
        try {
            DimensionType type = DimensionManager.getProviderType(dimId);
            if (type != null) return type.createDimension();
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Get all biomes for a dimension.
     */
    public static Set<String> getBiomesInDimension(int dimId) {
        init();
        return dimensionToBiomes.getOrDefault(dimId, new HashSet<>());
    }

    /**
     * Get all dimensions sorted by abs(id).
     */
    public static List<Integer> getSortedDimensionIds() {
        init();
        return new ArrayList<>(sortedDimensionIds);
    }

    /**
     * Check if initialization is complete.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if background sampling is still active for any dimension.
     */
    public static boolean isBackgroundSamplingActive() {
        return !activeBackgroundTasks.isEmpty();
    }

    /**
     * Get the number of dimensions currently being background sampled.
     */
    public static int getActiveBackgroundTaskCount() {
        return activeBackgroundTasks.size();
    }

    /**
     * Clear the cache (useful for reloading).
     */
    public static void clearCache() {
        stopAllBackgroundSampling();
        biomeToDimensions = null;
        dimensionToBiomes = null;
        sortedDimensionIds = null;
        initialized = false;
    }

    /**
     * Stop all background sampling tasks.
     */
    public static void stopAllBackgroundSampling() {
        for (BackgroundSampleTask task : activeBackgroundTasks.values()) task.cancel();
        activeBackgroundTasks.clear();

        if (backgroundSampler != null) {
            backgroundSampler.shutdownNow();
            backgroundSampler = null;
        }
    }

    /**
     * Start background sampling for a dimension.
     * Samples expand outwards in rings at 1 sample per second for 5 minutes.
     */
    private static void startBackgroundSampling(int dimId, BiomeProvider biomeProvider, Set<String> biomes) {
        if (backgroundSampler == null) {
            backgroundSampler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "BiomeDimensionMapper-BackgroundSampler");
                t.setDaemon(true);
                return t;
            });
        }

        BackgroundSampleTask task = new BackgroundSampleTask(dimId, biomeProvider, biomes);
        activeBackgroundTasks.put(dimId, task);

        ScheduledFuture<?> future = backgroundSampler.scheduleAtFixedRate(
            task,
            BACKGROUND_SAMPLE_INTERVAL_MS,
            BACKGROUND_SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        task.setFuture(future);
    }

    /**
     * Background sampling task that expands outwards in rings.
     */
    private static class BackgroundSampleTask implements Runnable {
        private final int dimId;
        private final BiomeProvider biomeProvider;
        private final Set<String> biomes;
        private final long startTime;
        private final Random random = new Random(42);

        private int currentRing = 1; // Start from ring 1 (ring 0 was covered by initial sampling)
        private int samplesInCurrentRing = 0;
        private ScheduledFuture<?> future;

        BackgroundSampleTask(int dimId, BiomeProvider biomeProvider, Set<String> biomes) {
            this.dimId = dimId;
            this.biomeProvider = biomeProvider;
            this.biomes = biomes;
            this.startTime = System.currentTimeMillis();
        }

        void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        void cancel() {
            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public void run() {
            try {
                // Check if we've exceeded the time limit
                if (System.currentTimeMillis() - startTime > BACKGROUND_SAMPLE_DURATION_MS) {
                    if (ConditionUtils.isProfilingEnabled()) {
                        SuperMobTracker.LOGGER.info("Background sampling for dimension " + dimId +
                            " complete after 5 minutes. Found " + biomes.size() + " biomes total.");
                    }
                    cancel();
                    activeBackgroundTasks.remove(dimId);

                    return;
                }

                // Sample one batch in the current ring
                int initialSize = biomes.size();
                sampleOneGridInRing();

                // Update biome->dimension mapping if we found new biomes
                if (biomes.size() > initialSize) {
                    synchronized (biomeToDimensions) {
                        for (String biome : biomes) {
                            List<Integer> dims = biomeToDimensions.computeIfAbsent(biome, k -> new ArrayList<>());
                            if (!dims.contains(dimId)) dims.add(dimId);
                        }
                    }

                    if (ConditionUtils.isProfilingEnabled()) {
                        SuperMobTracker.LOGGER.info("Background sampling for dimension " + dimId +
                            " found " + (biomes.size() - initialSize) + " new biomes in ring " + currentRing);
                    }
                }

                // Move to next position in ring, or next ring
                samplesInCurrentRing++;
                int samplesPerRing = 8 * currentRing; // 8 samples per ring perimeter unit
                if (samplesInCurrentRing >= samplesPerRing) {
                    currentRing++;
                    samplesInCurrentRing = 0;
                }
            } catch (Exception e) {
                if (ConditionUtils.isProfilingEnabled()) {
                    SuperMobTracker.LOGGER.warn("Error in background sampling for dimension " + dimId + ": " + e.getMessage());
                }
            }
        }

        private void sampleOneGridInRing() {
            // Calculate position in current ring (expanding outward from origin)
            // Ring N is at distance N * BACKGROUND_GRID_EXPANSION from origin
            int ringDistance = currentRing * BACKGROUND_GRID_EXPANSION;

            // Sample at a random position on the ring perimeter
            // Use the sample index to determine which side of the ring square we're on
            int samplesPerRing = 8 * currentRing;
            int side = (samplesInCurrentRing * 4) / samplesPerRing;
            int positionOnSide = samplesInCurrentRing % (samplesPerRing / 4);
            float sideProgress = (float) positionOnSide / (samplesPerRing / 4);

            int gridCenterX, gridCenterZ;
            switch (side) {
                case 0: // Top side (from -ringDistance to +ringDistance on X, Z = -ringDistance)
                    gridCenterX = (int) (-ringDistance + 2 * ringDistance * sideProgress);
                    gridCenterZ = -ringDistance;
                    break;
                case 1: // Right side
                    gridCenterX = ringDistance;
                    gridCenterZ = (int) (-ringDistance + 2 * ringDistance * sideProgress);
                    break;
                case 2: // Bottom side
                    gridCenterX = (int) (ringDistance - 2 * ringDistance * sideProgress);
                    gridCenterZ = ringDistance;
                    break;
                default: // Left side
                    gridCenterX = -ringDistance;
                    gridCenterZ = (int) (ringDistance - 2 * ringDistance * sideProgress);
                    break;
            }

            // Sample a batch at this grid position
            int batchX = gridCenterX + random.nextInt(GRID_SIZE) - GRID_SIZE / 2;
            int batchZ = gridCenterZ + random.nextInt(GRID_SIZE) - GRID_SIZE / 2;

            try {
                Biome[] batchBiomes = biomeProvider.getBiomesForGeneration(null, batchX, batchZ, BATCH_SIZE, BATCH_SIZE);
                if (batchBiomes != null) {
                    for (Biome biome : batchBiomes) {
                        if (biome != null && biome.getRegistryName() != null) biomes.add(biome.getRegistryName().toString());
                    }
                }
            } catch (Exception ignored) {
                // Fall back to single point sampling
                try {
                    Biome biome = biomeProvider.getBiome(new BlockPos(batchX, 64, batchZ));
                    if (biome != null && biome.getRegistryName() != null) biomes.add(biome.getRegistryName().toString());
                } catch (Exception ignored2) {
                    // Skip this position
                }
            }
        }
    }
}
