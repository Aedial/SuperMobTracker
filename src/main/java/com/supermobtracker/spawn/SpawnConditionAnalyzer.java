package com.supermobtracker.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;


/**
 * Analyzes spawn conditions for entities by testing various environmental factors.
 * Uses a FakeWorld to simulate spawn checks without affecting the real world.
 */
public class SpawnConditionAnalyzer {

    // All biomes from Forge registry
    private static final List<Biome> ALL_BIOMES = ForgeRegistries.BIOMES
        .getValuesCollection().stream().collect(Collectors.toList());

    // All biome names from Forge registry
    private static final List<String> ALL_BIOME_NAMES = ALL_BIOMES.stream()
        .map(b -> b.getRegistryName().toString())
        .collect(Collectors.toList());

    // Ground blocks list to be sampled
    private static final List<String> GROUND_BLOCKS = Arrays.asList(
        "stone", "grass", "dirt", "cobblestone", "sand", "ice", "gravel",
        "wood", "netherrack", "end_stone", "soul_sand", "mycelium", "clay"
    );

    // Entity id to CreatureType mapping cache
    private static final Map<ResourceLocation, EnumCreatureType> CREATURE_TYPE_CACHE = buildCreatureTypeCache();

    private static Map<ResourceLocation, EnumCreatureType> buildCreatureTypeCache() {
        Map<ResourceLocation, EnumCreatureType> cache = new HashMap<>();
        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            EnumCreatureType type = Arrays.stream(EnumCreatureType.values())
                    .filter(t -> t.getCreatureClass().isAssignableFrom(entry.getEntityClass()))
                    .findFirst()
                    .orElse(null);
            if (type != null) {
                cache.put(entry.getRegistryName(), type);
            }
        }

        return cache;
    }

    // Y levels to probe for finding first valid sample
    // Includes 50, 55, 60 for aquatic mobs that require posY > 45 && posY < seaLevel (63)
    private static final List<Integer> PROBE_Y_LEVELS = Arrays.asList(1, 10, 32, 50, 55, 60, 63, 64, 96, 128, 150);

    // Light levels to probe for finding first valid sample
    private static final List<Integer> PROBE_LIGHT_LEVELS = Arrays.asList(0, 1, 7, 12, 14, 15);

    // Native biome cache
    private static final Map<ResourceLocation, List<String>> NATIVE_BIOME_CACHE = new HashMap<>();

    // Entity instance cache
    private Map<ResourceLocation, EntityLiving> entityInstanceCache = new HashMap<>();

    // Last computed result for GUI helpers
    private SpawnConditions lastResult;

    // Last error during analysis (if any)
    private Throwable lastError;

    // Whether the last analyzed entity had native biomes
    private boolean lastHadNativeBiomes;

    /**
     * Returns whether the last analyzed entity had native biomes.
     * Useful for distinguishing "no native biomes" from "analysis failed".
     */
    public boolean hasNativeBiomes() { return lastHadNativeBiomes; }

    /**
     * Returns the last error that occurred during analysis, or null if none.
     */
    public Throwable getLastError() { return lastError; }

    /**
     * Result of spawn condition analysis.
     */
    public static class SpawnConditions {
        public final List<String> biomes;
        public final List<String> groundBlocks;
        public final List<Integer> lightLevels;
        public final List<Integer> yLevels;
        public final List<String> timeOfDay;
        public final List<String> weather;
        public final List<String> hints;
        public final String dimension;
        public final int dimensionId;

        public SpawnConditions(List<String> biomes,
                               List<String> groundBlocks,
                               List<Integer> lightLevels,
                               List<Integer> yLevels,
                               List<String> timeOfDay,
                               List<String> weather,
                               List<String> hints,
                               String dimension,
                               int dimensionId) {
            this.biomes = biomes;
            this.groundBlocks = groundBlocks;
            this.lightLevels = lightLevels;
            this.yLevels = yLevels;
            this.timeOfDay = timeOfDay;
            this.weather = weather;
            this.hints = hints == null ? new ArrayList<>() : hints;
            this.dimension = dimension;
            this.dimensionId = dimensionId;
        }

        public boolean failed() {
            boolean hasLightLevels = !lightLevels.isEmpty();
            boolean hasYLevels = !yLevels.isEmpty();
            boolean hasGroundBlocks = !groundBlocks.isEmpty() && !groundBlocks.get(0).equals("unknown");
            boolean hasTimeOfDay = !timeOfDay.isEmpty() && !timeOfDay.get(0).equals("unknown");
            boolean hasWeather = !weather.isEmpty() && !weather.get(0).equals("unknown");

            return !(hasLightLevels || hasYLevels || hasGroundBlocks || hasTimeOfDay || hasWeather);
        }
    }

    /**
     * A fake world used to simulate spawn condition checks.
     */
    public static class SimulatedWorld extends World {
        public int lightLevel = 15;
        public String groundBlock = "grass";
        public String biome = "plains";
        public String dimension = "overworld";
        public String timeOfDay = "day";
        public String weather = "clear";

        private Map<String, Boolean> queriedConditions = new HashMap<>();

        static class SimulatedProvider extends WorldProvider {
            private WorldProvider baseProvider;
            private Map<String, Boolean> queriedConditions;
            String dimension;

            private SimulatedProvider(WorldProvider provider) {
                this.baseProvider = provider;
                this.dimension = provider.getDimensionType().getName().toLowerCase();
                // Set the dimension field directly so getDimension() returns the correct value
                this.setDimension(provider.getDimensionType().getId());
            }

            public void bindQueryTracker(Map<String, Boolean> queriedConditions) {
                this.queriedConditions = queriedConditions;
            }

            @Override
            public DimensionType getDimensionType() {
                if (this.queriedConditions != null) {
                    this.queriedConditions.put("dimension", true);
                }
                return baseProvider.getDimensionType();
            }

            @Override
            public int getDimension() {
                if (this.queriedConditions != null) {
                    this.queriedConditions.put("dimension", true);
                }
                return baseProvider.getDimensionType().getId();
            }
        }

        public static SimulatedWorld fromReal(World world) {
            return new SimulatedWorld(null, world.getWorldInfo(), new SimulatedProvider(world.provider), new Profiler(), true);
        }

        public static SimulatedWorld fromProvider(WorldInfo worldInfo, WorldProvider provider) {
            return new SimulatedWorld(null, worldInfo, new SimulatedProvider(provider), new Profiler(), true);
        }

        private SimulatedWorld(ISaveHandler saveHandler, WorldInfo info, WorldProvider provider, Profiler profiler, boolean isClient) {
            super(saveHandler, info, provider, profiler, isClient);
            this.provider.setWorld(this);
            ((SimulatedProvider) provider).bindQueryTracker(queriedConditions);

            if (this.chunkProvider == null) {
                this.chunkProvider = this.createChunkProvider();
            }

            this.queriedConditions.put("dimension", false);
            this.queriedConditions.put("lightLevel", false);
            this.queriedConditions.put("pos", false);
            this.queriedConditions.put("groundBlock", false);
            this.queriedConditions.put("biome", false);
            this.queriedConditions.put("timeOfDay", false);
            this.queriedConditions.put("weather", false);
        }

        public Map<String, Boolean> getAndResetQueriedConditions() {
            Map<String, Boolean> flags = new HashMap<>(queriedConditions);
            queriedConditions.clear();

            return flags;
        }

        @Override
        public int getLightFromNeighbors(BlockPos pos) {
            queriedConditions.put("lightLevel", true);
            return lightLevel;
        }

        @Override
        public EnumDifficulty getDifficulty() {
            return EnumDifficulty.NORMAL;
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            queriedConditions.put("biome", true);
            return ForgeRegistries.BIOMES.getValue(new ResourceLocation("minecraft", biome));
        }

        @Override
        public long getWorldTime() {
            queriedConditions.put("timeOfDay", true);
            switch (timeOfDay) {
                case "day":
                    return 1000;
                case "night":
                    return 13000;
                case "dawn":
                    return 23000;
                case "dusk":
                    return 12000;
                default:
                    return 0;
            }
        }

        @Override
        public boolean isRaining() {
            queriedConditions.put("weather", true);
            return weather.equals("rain") || weather.equals("thunder");
        }

        @Override
        public boolean isThundering() {
            queriedConditions.put("weather", true);
            return weather.equals("thunder");
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            queriedConditions.put("groundBlock", true);
            return ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft", groundBlock)).getDefaultState();
        }

        @Override
        public boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return true;
        }

        @Override
        public BlockPos getTopSolidOrLiquidBlock(BlockPos pos) {
            queriedConditions.put("pos", true);
            return pos;
        }

        @Override
        public boolean canSeeSky(BlockPos pos) {
            queriedConditions.put("pos", true);
            return lightLevel >= 15;
        }

        @Override
        public boolean canBlockSeeSky(BlockPos pos) {
            queriedConditions.put("pos", true);
            return lightLevel >= 15;
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side) {
            queriedConditions.put("groundBlock", true);
            return !groundBlock.equals("air");
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
            queriedConditions.put("groundBlock", true);
            return !groundBlock.equals("air");
        }

        @Override
        public List<AxisAlignedBB> getCollisionBoxes(Entity entityIn, AxisAlignedBB aabb) {
            return new ArrayList<>();
        }

        @Override
        public boolean collidesWithAnyBlock(AxisAlignedBB bbox) {
            return false;
        }

        @Override
        public boolean isAnyPlayerWithinRangeAt(double x, double y, double z, double range) {
            return false;
        }

        @Override
        public boolean isBlockLoaded(BlockPos pos) {
            return true;
        }

        @Override
        public boolean isBlockLoaded(BlockPos pos, boolean allowEmpty) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(BlockPos center, int radius) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(BlockPos center, int radius, boolean allowEmpty) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(BlockPos from, BlockPos to) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(BlockPos from, BlockPos to, boolean allowEmpty) {
            return true;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return new IChunkProvider() {
                private Chunk blankChunk;

                @Override
                public Chunk getLoadedChunk(int x, int z) {
                    return provideChunk(x, z);
                }

                @Override
                public Chunk provideChunk(int x, int z) {
                    if (blankChunk == null) {
                        blankChunk = new Chunk(SimulatedWorld.this, x, z);
                        blankChunk.setTerrainPopulated(true);
                        blankChunk.setLightPopulated(true);
                    }

                    return blankChunk;
                }

                @Override
                public boolean tick() { return false; }

                @Override
                public String makeString() { return "SimulatedChunkProvider"; }

                @Override
                public boolean isChunkGeneratedAt(int x, int z) { return true; }
            };
        }
    }

    /**
     * Get or create a cached entity instance for analysis.
     */
    public EntityLiving getEntityInstance(ResourceLocation entityId) {
        if (entityInstanceCache.containsKey(entityId)) {
            return entityInstanceCache.get(entityId);
        }

        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(entityId);
        if (entry == null || !(EntityLiving.class.isAssignableFrom(entry.getEntityClass()))) {
            return null;
        }

        try {
            World world = Minecraft.getMinecraft().world;
            if (world == null) {
                entityInstanceCache.put(entityId, null);
                return null;
            }

            Entity entity = EntityList.createEntityByIDFromName(entry.getRegistryName(), world);
            if (entity instanceof EntityLiving) {
                entityInstanceCache.put(entityId, (EntityLiving) entity);
                return (EntityLiving) entity;
            }
        } catch (Exception ignored) { }

        entityInstanceCache.put(entityId, null);
        return null;
    }

    public boolean isBoss(ResourceLocation entityId) {
        EntityLiving entity = getEntityInstance(entityId);
        return entity != null && !entity.isNonBoss();
    }

    public boolean isPassive(ResourceLocation entityId) {
        EnumCreatureType type = CREATURE_TYPE_CACHE.get(entityId);
        return type == EnumCreatureType.CREATURE || type == EnumCreatureType.AMBIENT;
    }

    public boolean isNeutral(ResourceLocation entityId) {
        // FIXME: 1.12 lacks a direct neutral creature type. Neutral behavior (e.g., wolves, endermen,
        // polar bears, zombie pigmen) is determined by per-entity logic, not a type flag.
        // A proper implementation would require maintaining a hardcoded list of known neutral mobs.
        return false;
    }

    public boolean isHostile(ResourceLocation entityId) {
        EnumCreatureType type = CREATURE_TYPE_CACHE.get(entityId);
        return type == EnumCreatureType.MONSTER;
    }

    public boolean isAquatic(ResourceLocation entityId) {
        EnumCreatureType type = CREATURE_TYPE_CACHE.get(entityId);
        return type == EnumCreatureType.WATER_CREATURE;
    }

    public boolean isFlying(ResourceLocation entityId) {
        EntityLiving entity = getEntityInstance(entityId);
        return entity != null && EntityFlying.class.isAssignableFrom(entity.getClass());
    }

    public boolean cannotDespawn(ResourceLocation entityId) {
        // FIXME: In 1.12, despawn behavior is controlled by EntityLiving.canDespawn() and
        // persistenceRequired flag, both of which depend on entity instance state (e.g., being
        // name-tagged, interacted with). Without a live entity in the world, we cannot determine this.
        return false;
    }

    public boolean isTameable(ResourceLocation entityId) {
        EntityLiving entity = getEntityInstance(entityId);
        return entity != null && EntityTameable.class.isAssignableFrom(entity.getClass());
    }

    public Vec3d getEntitySize(ResourceLocation entityId) {
        // TODO: use EntityLiving.getRenderBoundingBox() for more accurate size
        EntityLiving entity = getEntityInstance(entityId);
        if (entity == null) return new Vec3d(0, 0, 0);

        return new Vec3d(entity.width, entity.height, entity.width);
    }

    /**
     * Analyze spawn conditions for the given entity.
     *
     * @param entityId the entity registry name
     * @return SpawnConditions result, or null if analysis failed
     */
    public SpawnConditions analyze(ResourceLocation entityId) {
        long startTime = System.nanoTime();
        lastError = null;
        lastResult = null;

        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(entityId);
        if (entry == null || !(EntityLiving.class.isAssignableFrom(entry.getEntityClass()))) return null;

        try {
            EntityLiving entity = getEntityInstance(entityId);
            if (entity == null) return null;

            // Get native biomes from the entity spawn tables
            List<String> nativeBiomes = getNativeBiomes(entityId, entry.getEntityClass());
            lastHadNativeBiomes = nativeBiomes != null && !nativeBiomes.isEmpty();
            if (!lastHadNativeBiomes) {
                // Entities without native biomes cannot spawn naturally
                return null;
            }

            List<String> groundBlocks;
            if (isFlying(entityId)) {
                groundBlocks = Arrays.asList("air");
            } else if (isAquatic(entityId)) {
                groundBlocks = Arrays.asList("water");
            } else {
                groundBlocks = new ArrayList<>(GROUND_BLOCKS);
            }

            SpawnConditions result = computeSpawnConditions(entity, nativeBiomes, groundBlocks, PROBE_LIGHT_LEVELS);
            if (result == null) {
                List<Integer> yLevels = Arrays.asList(PROBE_Y_LEVELS.get(0), PROBE_Y_LEVELS.get(PROBE_Y_LEVELS.size() - 1));
                List<String> timeOfDay = isHostile(entityId) ? Arrays.asList("night") : Arrays.asList("day");
                List<String> weather = Arrays.asList("clear");

                // Fall back to current dimension
                int currentDimId = entity.world.provider.getDimension();
                String dimensionName = BiomeDimensionMapper.getDimensionName(currentDimId);

                result = new SpawnConditions(
                    nativeBiomes, groundBlocks, PROBE_LIGHT_LEVELS, yLevels, timeOfDay, weather, null, dimensionName, currentDimId
                );
            }

            lastResult = result;

            if (ConditionUtils.isProfilingEnabled()) {
                long elapsed = System.nanoTime() - startTime;
                SuperMobTracker.LOGGER.info("Analysis of " + entityId + " took " + (elapsed / 1_000_000.0) + "ms");

                if (result.failed()) SuperMobTracker.LOGGER.info("  Spawn conditions could not be determined, as no valid samples were found.");
            }

            return result;
        } catch (Throwable t) {
            lastError = t;

            if (ConditionUtils.isProfilingEnabled()) {
                long elapsed = System.nanoTime() - startTime;
                SuperMobTracker.LOGGER.info("Analysis of " + entityId + " crashed after " + (elapsed / 1_000_000.0) + "ms: " + t.getMessage());
            }

            return null;
        }
    }

    private void createNativeBiomesCache() {
        for (Biome biome : ALL_BIOMES) {
            for (EnumCreatureType type : EnumCreatureType.values()) {
                List<Biome.SpawnListEntry> entries = biome.getSpawnableList(type);
                for (Biome.SpawnListEntry entry : entries) {
                    ResourceLocation entityId = EntityList.getKey(entry.entityClass);
                    if (entityId != null) {
                        NATIVE_BIOME_CACHE.computeIfAbsent(entityId, k -> new ArrayList<>())
                            .add(biome.getRegistryName().toString());
                    }
                }
            }
        }
    }

    /**
     * Get native spawn biomes for an entity from the biome spawn lists.
     */
    private List<String> getNativeBiomes(ResourceLocation entityId, Class<?> entityClass) {
        if (NATIVE_BIOME_CACHE.isEmpty()) createNativeBiomesCache();

        List<String> nativeBiomes = NATIVE_BIOME_CACHE.get(entityId);
        return nativeBiomes != null ? nativeBiomes : new ArrayList<>();
    }

    /**
     * Compute spawn conditions for an entity with native biomes.
     */
    private SpawnConditions computeSpawnConditions(EntityLiving entity, List<String> biomes,
                                                   List<String> groundBlocks, List<Integer> lightLevels) {
        // Find the correct dimension for this mob's biomes
        int currentDimId = entity.world.provider.getDimension();
        int targetDimId = BiomeDimensionMapper.findDimensionForBiomes(biomes, currentDimId);

        // Check if we actually found a matching dimension
        boolean foundDimension = targetDimId != Integer.MIN_VALUE;
        String dimensionName;
        WorldProvider targetProvider;

        if (foundDimension) {
            targetProvider = BiomeDimensionMapper.getProviderForDimension(targetDimId);
            dimensionName = BiomeDimensionMapper.getDimensionName(targetDimId);

            // Fall back to overworld's provider if we can't get the target
            if (targetProvider == null) {
                targetProvider = BiomeDimensionMapper.getProviderForDimension(0);
                targetDimId = 0;
                dimensionName = BiomeDimensionMapper.getDimensionName(0);

                // If still null, use current dimension
                if (targetProvider == null) {
                    targetProvider = entity.world.provider;
                    targetDimId = currentDimId;
                    dimensionName = BiomeDimensionMapper.getDimensionName(currentDimId);
                }
            }
        } else {
            // No dimension found for these biomes - default to overworld for testing
            // (some mobs don't care about dimension, and unknown is invalid for testing)
            targetDimId = 0;
            targetProvider = BiomeDimensionMapper.getProviderForDimension(0);
            dimensionName = null; // Will display as "?" in GUI

            // Fall back to current dimension if overworld provider unavailable
            if (targetProvider == null) {
                targetProvider = entity.world.provider;
                targetDimId = currentDimId;
            }
        }

        SimulatedWorld simulatedWorld = SimulatedWorld.fromProvider(entity.world.getWorldInfo(), targetProvider);
        simulatedWorld.dimension = dimensionName != null ? dimensionName : "unknown";

        ConditionRefiner refiner = new ConditionRefiner(entity.getClass(), simulatedWorld);
        SpawnConditions result = refiner.findValidConditions(biomes, groundBlocks, lightLevels, PROBE_Y_LEVELS);

        if (result != null) {
            return new SpawnConditions(
                biomes, result.groundBlocks, result.lightLevels, result.yLevels, result.timeOfDay, result.weather, result.hints, dimensionName, targetDimId
            );
        }

        List<Integer> narrowedLight = refiner.refineLightLevels(lightLevels);
        List<Integer> yLevels = new ArrayList<>();
        List<String> timeOfDay = Arrays.asList("unknown");
        List<String> weather = Arrays.asList("unknown");

        return new SpawnConditions(biomes, groundBlocks, narrowedLight, yLevels, timeOfDay, weather, null, dimensionName, targetDimId);
    }

    public boolean hasResult() {
        return lastResult != null;
    }

    public List<String> getErrorHints() {
        List<String> hints = new ArrayList<>();
        if (lastResult == null) {
            if (lastError != null) {
                hints.add("Spawn analysis crashed");
            } else {
                hints.add("Entity has no living instance or could not be analyzed.");
            }
        }

        return hints;
    }
}
