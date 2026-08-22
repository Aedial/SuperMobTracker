package com.supermobtracker.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.init.Blocks;
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
import com.supermobtracker.util.Utils;


/**
 * Analyzes spawn conditions for entities by testing various environmental factors.
 * Uses a FakeWorld to simulate spawn checks without affecting the real world.
 */
public class SpawnConditionAnalyzer {

    // All biomes from Forge registry
    private static final List<Biome> ALL_BIOMES = new ArrayList<>(ForgeRegistries.BIOMES.getValuesCollection());

    // Ground blocks list to be sampled (full registry names)
    private static final List<String> GROUND_BLOCKS = Arrays.asList(
        "minecraft:stone", "minecraft:grass", "minecraft:dirt", "minecraft:cobblestone",
        "minecraft:sand", "minecraft:ice", "minecraft:gravel", "minecraft:planks",
        "minecraft:netherrack", "minecraft:end_stone", "minecraft:soul_sand",
        "minecraft:mycelium", "minecraft:clay"
    );

    public static final String NATURAL_SPAWN_REASON = "natural";

    // Entity id to CreatureType mapping cache
    private static final Map<ResourceLocation, EnumCreatureType> CREATURE_TYPE_CACHE = buildCreatureTypeCache();

    private static Map<ResourceLocation, EnumCreatureType> buildCreatureTypeCache() {
        Map<ResourceLocation, EnumCreatureType> cache = new HashMap<>();
        for (EntityEntry entry : ForgeRegistries.ENTITIES.getValuesCollection()) {
            Arrays.stream(EnumCreatureType.values()).filter(
                    t -> t.getCreatureClass()
                          .isAssignableFrom(entry.getEntityClass()))
                          .findFirst()
                          .ifPresent(type -> cache.put(entry.getRegistryName(), type));
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
    private final Map<ResourceLocation, EntityLiving> entityInstanceCache = new HashMap<>();

    // Lazily initialized entity instances for display helpers.
    private final Map<ResourceLocation, EntityLiving> initializedEntityInstanceCache = new HashMap<>();

    // SimulatedWorld cache per dimension ID (avoids initializing a new world each time)
    private static final Map<Integer, SimulatedWorld> simulatedWorldCache = new HashMap<>();

    // Last computed result for GUI helpers
    private SpawnConditions lastResult;

    // Last error during analysis (if any)
    private Throwable lastError;

    // Whether the last analyzed entity had usable biome candidates.
    // This includes native spawn-table biomes and explicit fallbacks for known worldgen-only mobs.
    private boolean lastHadNativeBiomes;

    /**
     * Returns whether the last analyzed entity had usable biome candidates.
     * Useful for distinguishing "no biome data" from "analysis failed".
     */
    public boolean hasNativeBiomes() { return lastHadNativeBiomes; }

    public static List<Integer> buildIntRange(int startInclusive, int endInclusive) {
        List<Integer> values = new ArrayList<>();

        for (int value = startInclusive; value <= endInclusive; value++) values.add(value);

        return values;
    }

    /**
     * Returns the last error that occurred during analysis, or null if none.
     */
    public Throwable getLastError() { return lastError; }

    /**
     * Result of spawn condition analysis.
     */
    public static class SpawnConditions {
        public final List<String> biomes;
        public final List<String> groundBlocks;    // null = doesn't matter, else list of valid ground blocks
        public final List<Integer> lightLevels;
        public final List<Integer> yLevels;
        public final List<int[]> timeOfDay;        // null = doesn't matter, else list of valid time ranges [start, end] in ticks
        public final List<String> weather;         // null = doesn't matter, else list of valid weathers
        public final List<String> hints;
        public final Boolean requiresSky;          // null = doesn't matter, true = requires sky, false = requires no sky
        public final List<Integer> moonPhases;     // null = doesn't matter, else list of valid moon phases (0-7)
        public final Boolean requiresSlimeChunk;   // null = doesn't matter, true = requires slime chunk, false = excludes slime chunk
        public final Boolean requiresNether;       // null = doesn't matter, true = requires nether-like, false = excludes nether-like
        public final String dimension;
        public final int dimensionId;
        public final String spawnReason;

        public SpawnConditions(@Nonnull List<String> biomes,
                               @Nullable List<String> groundBlocks,
                               @Nonnull List<Integer> lightLevels,
                               @Nonnull List<Integer> yLevels,
                               @Nullable List<int[]> timeOfDay,
                               @Nullable List<String> weather,
                               @Nonnull List<String> hints,
                               @Nullable Boolean requiresSky,
                               @Nullable String dimension,
                               int dimensionId) {
            this(biomes, groundBlocks, lightLevels, yLevels, timeOfDay, weather, hints,
                 requiresSky, null, null, null, dimension, dimensionId, NATURAL_SPAWN_REASON);
        }

        public SpawnConditions(@Nonnull List<String> biomes,
                               @Nullable List<String> groundBlocks,
                               @Nonnull List<Integer> lightLevels,
                               @Nonnull List<Integer> yLevels,
                               @Nullable List<int[]> timeOfDay,
                               @Nullable List<String> weather,
                               @Nonnull List<String> hints,
                               @Nullable Boolean requiresSky,
                               @Nullable List<Integer> moonPhases,
                               @Nullable Boolean requiresSlimeChunk,
                               @Nullable Boolean requiresNether,
                               @Nullable String dimension,
                               int dimensionId) {
              this(biomes, groundBlocks, lightLevels, yLevels, timeOfDay, weather, hints,
                  requiresSky, moonPhases, requiresSlimeChunk, requiresNether, dimension, dimensionId, NATURAL_SPAWN_REASON);
           }

           public SpawnConditions(@Nonnull List<String> biomes,
                                  @Nullable List<String> groundBlocks,
                                  @Nonnull List<Integer> lightLevels,
                                  @Nonnull List<Integer> yLevels,
                                  @Nullable List<int[]> timeOfDay,
                                  @Nullable List<String> weather,
                                  @Nonnull List<String> hints,
                                  @Nullable Boolean requiresSky,
                                  @Nullable List<Integer> moonPhases,
                                  @Nullable Boolean requiresSlimeChunk,
                                  @Nullable Boolean requiresNether,
                                  @Nullable String dimension,
                                  int dimensionId,
                                  @Nullable String spawnReason) {
            this.biomes = biomes;
            this.groundBlocks = groundBlocks;
            this.lightLevels = lightLevels;
            this.yLevels = yLevels;
            this.timeOfDay = timeOfDay;
            this.weather = weather;
            this.hints = hints == null ? new ArrayList<>() : hints;
            this.requiresSky = requiresSky;
            this.moonPhases = moonPhases;
            this.requiresSlimeChunk = requiresSlimeChunk;
            this.requiresNether = requiresNether;
            this.dimension = dimension;
            this.dimensionId = dimensionId;
            this.spawnReason = spawnReason;
        }

        private boolean hasNonNaturalSpawnReason() {
            return spawnReason != null && !NATURAL_SPAWN_REASON.equals(spawnReason);
        }

        /**
         * Check if the analysis failed (no valid first sample found).
         */
        public boolean failed() {
            if (hasNonNaturalSpawnReason()) return false;

            boolean hasBiomes = biomes != null && !biomes.isEmpty() && !biomes.get(0).equals("unknown");
            boolean hasLightLevels = !lightLevels.isEmpty();
            boolean hasYLevels = !yLevels.isEmpty();
            boolean hasGroundBlocks = groundBlocks != null && !groundBlocks.isEmpty() && !groundBlocks.get(0).equals("unknown");
            boolean hasTimeOfDay = timeOfDay != null && !timeOfDay.isEmpty();
            boolean hasWeather = weather != null && !weather.isEmpty() && !weather.get(0).equals("unknown");

            return !(hasBiomes || hasLightLevels || hasYLevels || hasGroundBlocks || hasTimeOfDay || hasWeather);
        }

        /**
         * Check if the spawn conditions are sparse (multiple ranges, which might indicate incomplete analysis).
         */
        public boolean isSparse() {
            if (hasNonNaturalSpawnReason()) return false;

            boolean hasBiomes = biomes != null && !biomes.isEmpty() && !biomes.get(0).equals("unknown");
            boolean hasOtherConditions = !lightLevels.isEmpty() || !yLevels.isEmpty() ||
                                         groundBlocks != null && !groundBlocks.isEmpty() && !groundBlocks.get(0).equals("unknown") ||
                                         timeOfDay != null && !timeOfDay.isEmpty() ||
                                         weather != null && !weather.isEmpty() && !weather.get(0).equals("unknown");
            if (hasBiomes && !hasOtherConditions) return false;

            return Utils.formatRangeFromList(lightLevels, ",").contains(",") ||
                   Utils.formatRangeFromList(yLevels, ",").contains(",") ||
                   (lightLevels.isEmpty() || yLevels.isEmpty()) && !failed();
        }
    }

    /**
     * A fake world used to simulate spawn condition checks.
     */
    public static class SimulatedWorld extends World {
        private static final ResourceLocation PLAINS = new ResourceLocation("minecraft", "plains");

        public int lightLevel = 15;
        public String groundBlock = "minecraft:grass";
        public String biomeId = "minecraft:plains";
        public String dimension = "overworld";
        public long worldTime = 1000;     // Time in ticks (0-24000), default to day
        public String weather = "clear";
        public boolean canSeeSky = true;
        public int moonPhase = 0;             // Moon phase 0-7 (0 = full moon, 4 = new moon)
        public boolean isSlimeChunk = true;   // Whether the chunk is a slime chunk
        public boolean isNether = false;      // Whether the world is Nether-like (doesWaterVaporize)

        private final Map<String, Boolean> queriedConditions = new HashMap<>();

        static class SimulatedProvider extends WorldProvider {
            private final WorldProvider baseProvider;
            private Map<String, Boolean> queriedConditions;
            private SimulatedWorld simulatedWorld;
            private final String dimension;

            private SimulatedProvider(WorldProvider provider) {
                this.baseProvider = provider;
                this.dimension = provider.getDimensionType().getName().toLowerCase();
                // Set the dimension field directly so getDimension() returns the correct value
                this.setDimension(provider.getDimensionType().getId());
            }

            public void bindQueryTracker(Map<String, Boolean> queriedConditions, SimulatedWorld world) {
                this.queriedConditions = queriedConditions;
                this.simulatedWorld = world;
            }

            @Override
            @Nonnull
            public DimensionType getDimensionType() {
                if (this.queriedConditions != null) this.queriedConditions.put("dimension", true);
                return baseProvider.getDimensionType();
            }

            @Override
            public int getDimension() {
                if (this.queriedConditions != null) this.queriedConditions.put("dimension", true);
                return baseProvider.getDimensionType().getId();
            }

            @Override
            public boolean doesWaterVaporize() {
                if (this.queriedConditions != null) this.queriedConditions.put("isNether", true);
                return simulatedWorld != null ? simulatedWorld.isNether : baseProvider.doesWaterVaporize();
            }

            @Override
            public boolean isNether() {
                if (this.queriedConditions != null) this.queriedConditions.put("isNether", true);
                return simulatedWorld != null ? simulatedWorld.isNether : baseProvider.isNether();
            }
        }

        public static SimulatedWorld fromReal(World world) {
            return new SimulatedWorld(null, world.getWorldInfo(), new SimulatedProvider(world.provider), new Profiler(), true);
        }

        public static SimulatedWorld fromProvider(WorldInfo worldInfo, WorldProvider provider) {
            return new SimulatedWorld(null, worldInfo, new SimulatedProvider(provider), new Profiler(), true);
        }

        /**
         * Reset the SimulatedWorld to default state for reuse.
         */
        public void reset() {
            this.lightLevel = 15;
            this.groundBlock = "minecraft:grass";
            this.biomeId = "minecraft:plains";
            this.dimension = "overworld";
            this.worldTime = 1000;
            this.weather = "clear";
            this.canSeeSky = true;
            this.moonPhase = 0;
            this.isSlimeChunk = true;
            this.isNether = false;
            this.queriedConditions.clear();
        }

        private SimulatedWorld(ISaveHandler saveHandler, WorldInfo info, WorldProvider provider,
                Profiler profiler, boolean isClient) {
            super(saveHandler, info, provider, profiler, isClient);
            this.provider.setWorld(this);
            ((SimulatedProvider) provider).bindQueryTracker(queriedConditions, this);

            if (this.chunkProvider == null) this.chunkProvider = this.createChunkProvider();

            this.queriedConditions.put("dimension", false);
            this.queriedConditions.put("lightLevel", false);
            this.queriedConditions.put("pos", false);
            this.queriedConditions.put("groundBlock", false);
            this.queriedConditions.put("biome", false);
            this.queriedConditions.put("timeOfDay", false);
            this.queriedConditions.put("weather", false);
            this.queriedConditions.put("canSeeSky", false);
            this.queriedConditions.put("moonPhase", false);
            this.queriedConditions.put("isSlimeChunk", false);
            this.queriedConditions.put("isNether", false);
        }

        public Map<String, Boolean> getAndResetQueriedConditions() {
            Map<String, Boolean> flags = new HashMap<>(queriedConditions);
            queriedConditions.clear();

            return flags;
        }

        @Override
        public int getLight(@Nonnull BlockPos pos) {
            queriedConditions.put("lightLevel", true);
            return lightLevel;
        }

        @Override
        public int getLightFromNeighbors(@Nonnull BlockPos pos) {
            queriedConditions.put("lightLevel", true);
            return lightLevel;
        }

        @Override
        @Nonnull
        public EnumDifficulty getDifficulty() {
            return EnumDifficulty.NORMAL;
        }

        @Override
        @Nonnull
        public Biome getBiome(@Nonnull BlockPos pos) {
            queriedConditions.put("biome", true);
            Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(biomeId));

            // Fall back to plains if biome not found (should not happen with valid biomeId)
            return biome != null ? biome : ForgeRegistries.BIOMES.getValue(PLAINS);
        }

        @Override
        public long getWorldTime() {
            queriedConditions.put("timeOfDay", true);
            return worldTime;
        }

        @Override
        public boolean isDaytime() {
            queriedConditions.put("timeOfDay", true);
            // Daytime is 0-12000 (6:00-18:00), nighttime is 12000-24000
            long normalizedTime = worldTime % 24000;
            return normalizedTime >= 0 && normalizedTime <= 12500;
        }

        @Override
        public float getCurrentMoonPhaseFactor() {
            queriedConditions.put("moonPhase", true);
            // Convert moon phase to factor: 0 (full) = 1.0, 4 (new) = 0.0
            // Phases: 0=full(1.0), 1=waning gibbous(0.75), 2=third quarter(0.5), 3=waning crescent(0.25),
            //         4=new(0.0), 5=waxing crescent(0.25), 6=first quarter(0.5), 7=waxing gibbous(0.75)
            float[] factors = {1.0f, 0.75f, 0.5f, 0.25f, 0.0f, 0.25f, 0.5f, 0.75f};
            return factors[moonPhase % 8];
        }

        @Override
        public int getMoonPhase() {
            queriedConditions.put("moonPhase", true);
            return moonPhase;
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
        public boolean containsAnyLiquid(@Nonnull AxisAlignedBB bb) {
            // Simulates no liquid in the entity's bounding box
            return false;
        }

        @Override
        public boolean checkNoEntityCollision(@Nonnull AxisAlignedBB aabb) {
            // Simulates no entity collision
            return true;
        }

        @Override
        @Nonnull
        public IBlockState getBlockState(@Nonnull BlockPos pos) {
            queriedConditions.put("groundBlock", true);

            // If groundBlock contains ":", it's a full registry name; otherwise assume minecraft: namespace
            ResourceLocation blockId = groundBlock.contains(":")
                ? new ResourceLocation(groundBlock)
                : new ResourceLocation("minecraft", groundBlock);

            // If we have a biome set and the requested block is the biome's topBlock, return the actual topBlock state
            Biome currentBiome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(biomeId));
            if (currentBiome != null && currentBiome.topBlock != null) {
                ResourceLocation topBlockId = currentBiome.topBlock.getBlock().getRegistryName();
                if (topBlockId != null && topBlockId.equals(blockId)) return currentBiome.topBlock;
            }

            // Fall back to default state for other blocks
            Block block = ForgeRegistries.BLOCKS.getValue(blockId);
            return block != null ? block.getDefaultState() : Blocks.STONE.getDefaultState();
        }

        @Override
        public boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return true;
        }

        @Override
        @Nonnull
        public BlockPos getTopSolidOrLiquidBlock(@Nonnull BlockPos pos) {
            queriedConditions.put("pos", true);
            return pos;
        }

        @Override
        public boolean canSeeSky(@Nonnull BlockPos pos) {
            queriedConditions.put("canSeeSky", true);
            return canSeeSky;
        }

        @Override
        public boolean canBlockSeeSky(@Nonnull BlockPos pos) {
            queriedConditions.put("canSeeSky", true);
            return canSeeSky;
        }

        @Override
        public boolean isSideSolid(@Nonnull BlockPos pos, @Nonnull EnumFacing side) {
            queriedConditions.put("groundBlock", true);
            return !groundBlock.equals("minecraft:air") && !groundBlock.equals("air");
        }

        @Override
        public boolean isSideSolid(@Nonnull BlockPos pos, @Nonnull EnumFacing side, boolean _default) {
            queriedConditions.put("groundBlock", true);
            return !groundBlock.equals("minecraft:air") && !groundBlock.equals("air");
        }

        @Override
        @Nonnull
        public List<AxisAlignedBB> getCollisionBoxes(Entity entityIn, @Nonnull AxisAlignedBB aabb) {
            return new ArrayList<>();
        }

        @Override
        public boolean collidesWithAnyBlock(@Nonnull AxisAlignedBB bbox) {
            return false;
        }

        @Override
        @Nonnull
        public <T extends Entity> List<T> getEntitiesWithinAABB(
                @Nonnull Class<? extends T> clazz, @Nonnull AxisAlignedBB aabb) {
            return new ArrayList<>();
        }

        @Override
        @Nonnull
        public <T extends Entity> List<T> getEntitiesWithinAABB(
                @Nonnull Class<? extends T> classEntity, @Nonnull AxisAlignedBB bb, com.google.common.base.Predicate<? super T> filter) {
            return new ArrayList<>();
        }

        @Override
        public boolean isAnyPlayerWithinRangeAt(double x, double y, double z, double range) {
            return false;
        }

        @Override
        public boolean isBlockLoaded(@Nonnull BlockPos pos) {
            return true;
        }

        @Override
        public boolean isBlockLoaded(@Nonnull BlockPos pos, boolean allowEmpty) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(@Nonnull BlockPos center, int radius) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(@Nonnull BlockPos center, int radius, boolean allowEmpty) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(@Nonnull BlockPos from, @Nonnull BlockPos to) {
            return true;
        }

        @Override
        public boolean isAreaLoaded(@Nonnull BlockPos from, @Nonnull BlockPos to, boolean allowEmpty) {
            return true;
        }

        @Override
        @Nonnull
        protected IChunkProvider createChunkProvider() {
            SimulatedWorld outerWorld = this;

            return new IChunkProvider() {
                private SimulatedChunk simulatedChunk;

                @Override
                public Chunk getLoadedChunk(int x, int z) {
                    return provideChunk(x, z);
                }

                @Override
                @Nonnull
                public Chunk provideChunk(int x, int z) {
                    if (simulatedChunk == null) {
                        simulatedChunk = new SimulatedChunk(outerWorld, x, z);
                        simulatedChunk.setTerrainPopulated(true);
                        simulatedChunk.setLightPopulated(true);
                    }

                    return simulatedChunk;
                }

                @Override
                public boolean tick() { return false; }

                @Override
                @Nonnull
                public String makeString() { return "SimulatedChunkProvider"; }

                @Override
                public boolean isChunkGeneratedAt(int x, int z) { return true; }
            };
        }

        /**
         * Custom chunk that can simulate slime chunk behavior via getRandomWithSeed.
         * Slimes check: world.getChunkFromBlockCoords(pos).getRandomWithSeed(987234911L).nextInt(10) == 0
         */
        private class SimulatedChunk extends Chunk {
            SimulatedChunk(World worldIn, int x, int z) {
                super(worldIn, x, z);
            }

            @Override
            @Nonnull
            public Random getRandomWithSeed(long seed) {
                queriedConditions.put("isSlimeChunk", true);

                // Return a Random that will make nextInt(10) return 0 if isSlimeChunk is true, otherwise non-zero
                return new Random() {
                    @Override
                    public int nextInt(int bound) {
                        // Slimes check nextInt(10) == 0 for slime chunks
                        return isSlimeChunk ? 0 : 1;
                    }
                };
            }
        }
    }

    /**
     * Get or create a cached SimulatedWorld for the given dimension.
     * Caching avoids re-triggering mod world initialization (e.g., BoP logs "Setting up landmass VANILLA").
     */
    private SimulatedWorld getOrCreateSimulatedWorld(int dimensionId, WorldInfo worldInfo, WorldProvider provider) {
        SimulatedWorld cached = simulatedWorldCache.get(dimensionId);

        if (cached != null) {
            cached.reset();
            return cached;
        }

        SimulatedWorld newWorld = SimulatedWorld.fromProvider(worldInfo, provider);
        simulatedWorldCache.put(dimensionId, newWorld);

        return newWorld;
    }

    /**
     * Get or create a cached entity instance for analysis.
     */
    public EntityLiving getEntityInstance(ResourceLocation entityId) {
        if (entityInstanceCache.containsKey(entityId)) return entityInstanceCache.get(entityId);

        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(entityId);
        if (entry == null || !(EntityLiving.class.isAssignableFrom(entry.getEntityClass()))) return null;

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
        } catch (Exception e) {
            if (ConditionUtils.shouldShowCrashes()) {
                SuperMobTracker.LOGGER.error("Error creating entity instance for {}", entityId, e);
            }
        }

        entityInstanceCache.put(entityId, null);
        return null;
    }

    public EntityLiving getInitializedEntityInstance(ResourceLocation entityId) {
        if (initializedEntityInstanceCache.containsKey(entityId)) return initializedEntityInstanceCache.get(entityId);

        EntityLiving entity = getEntityInstance(entityId);
        if (entity == null) {
            initializedEntityInstanceCache.put(entityId, null);
            return null;
        }

        initializeEntityForDisplay(entityId, entity);
        initializedEntityInstanceCache.put(entityId, entity);
        return entity;
    }

    private void initializeEntityForDisplay(ResourceLocation entityId, EntityLiving entity) {
        World world = entity.world;
        if (world == null) return;

        entity.setLocationAndAngles(0.5D, Math.max(1, world.getSeaLevel()), 0.5D, 0.0F, 0.0F);

        try {
            entity.onInitialSpawn(world.getDifficultyForLocation(new BlockPos(entity)), null);
        } catch (Throwable t) {
            if (ConditionUtils.shouldShowCrashes()) {
                SuperMobTracker.LOGGER.error("Error initializing display entity for {}", entityId, t);
            }
        }

        if (!(entity instanceof EntityAgeable)) return;

        try {
            ((EntityAgeable) entity).setScaleForAge(entity.isChild());
        } catch (Throwable t) {
            if (ConditionUtils.shouldShowCrashes()) {
                SuperMobTracker.LOGGER.error("Error applying age scale for display entity {}", entityId, t);
            }
        }
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
        EntityLiving entity = getInitializedEntityInstance(entityId);
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
        double startTime = (double) System.nanoTime();
        lastError = null;
        lastResult = null;
        ConditionUtils.resetSuccessfulSeed();

        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(entityId);
        if (entry == null || !(EntityLiving.class.isAssignableFrom(entry.getEntityClass()))) return null;

        try {
            EntityLiving entity = getEntityInstance(entityId);
            if (entity == null) return null;

            // Get native biomes from the entity spawn tables
            List<String> nativeBiomes = getNativeBiomes(entityId, entry.getEntityClass());
            lastHadNativeBiomes = !nativeBiomes.isEmpty();
            if (!lastHadNativeBiomes) {
                SpawnConditions fallback = ExternalSpawnHints.getSpawnConditions(entityId, entity, isAquatic(entityId), isFlying(entityId));
                if (fallback == null) return null;

                lastHadNativeBiomes = true;
                lastResult = fallback;
                return fallback;
            }

            // Find target dimension for this mob
            int currentDimId = entity.world.provider.getDimension();
            int targetDimId = BiomeDimensionMapper.findDimensionForBiomes(nativeBiomes, currentDimId);
            if (targetDimId == Integer.MIN_VALUE) targetDimId = currentDimId;

            List<String> groundBlocks;
            List<String> biomeGroundBlocks = new ArrayList<>();
            if (isFlying(entityId)) {
                groundBlocks = Collections.singletonList("air");
                biomeGroundBlocks.add("air");
            } else if (isAquatic(entityId)) {
                groundBlocks = Collections.singletonList("water");
                biomeGroundBlocks.add("water");
            } else {
                // Start with standard ground blocks
                groundBlocks = new ArrayList<>(GROUND_BLOCKS);

                // Add ground blocks from native biomes (topBlock and fillerBlock)
                // This helps with mobs that check for modded ground blocks
                Set<String> biomeGroundBlocksSet = BiomeDimensionMapper.getGroundBlocksForBiomes(nativeBiomes);

                // Prioritize minecraft namespace and limit to 20 for first-sample probing
                Map<String, Integer> positions = new HashMap<>();
                for (String blockId : groundBlocks) positions.put(blockId, positions.size());

                biomeGroundBlocks.addAll(biomeGroundBlocksSet);
                biomeGroundBlocks.sort((a, b) -> {
                    int inBaseA = GROUND_BLOCKS.contains(a) ? 0 : 1;
                    int inBaseB = GROUND_BLOCKS.contains(b) ? 0 : 1;
                    if (inBaseA != inBaseB) return inBaseA - inBaseB;

                    return positions.getOrDefault(a, 0) - positions.getOrDefault(b, 0);
                });
                if (biomeGroundBlocks.size() > 20) {
                    biomeGroundBlocks = new ArrayList<>(biomeGroundBlocks.subList(0, 20));
                }

                // Build combined list for expansion (standard + all biome ground blocks without limit)
                for (String blockId : biomeGroundBlocksSet) {
                    if (!groundBlocks.contains(blockId)) groundBlocks.add(blockId);
                }
            }

            SpawnConditions result = computeSpawnConditions(entity, nativeBiomes, groundBlocks, biomeGroundBlocks, PROBE_LIGHT_LEVELS);

            lastResult = result;

            if (ConditionUtils.isProfilingEnabled()) {
                double elapsed = ((double) System.nanoTime() - startTime) / 1_000_000.0;
                SuperMobTracker.LOGGER.info("Analysis of {} took {}ms", entityId, Math.round(elapsed * 100) / 100.0);

                if (result.failed()) SuperMobTracker.LOGGER.info(
                    "  Spawn conditions could not be determined, as no valid samples were found.");
            }

            return result;
        } catch (Throwable t) {
            lastError = t;

            if (ConditionUtils.isProfilingEnabled()) {
                double elapsed = (System.nanoTime() - startTime) / 1_000_000.0;
                SuperMobTracker.LOGGER.info(
                    "Analysis of {} crashed after {}ms", entityId, Math.round(elapsed * 100) / 100.0);
            }

            logAnalysisFailure(entityId, t);

            return null;
        }
    }

    private void logAnalysisFailure(ResourceLocation entityId, Throwable error) {
        if (ConditionUtils.shouldShowCrashes()) {
            SuperMobTracker.LOGGER.error("Error analyzing spawn conditions for {}", entityId, error);
            return;
        }

        String message = error.getMessage();
        String summary = error.getClass().getSimpleName();

        if (message != null && !message.trim().isEmpty()) summary += ": " + message;

        SuperMobTracker.LOGGER.warn(
            "Spawn analysis failed for {}: {}. Launch with -Dsupermobtracker.showcrashes=true for stack traces.",
            entityId,
            summary
        );
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
                                                   List<String> groundBlocksCombined,
                                                   List<String> biomeGroundBlocksLimited,
                                                   List<Integer> lightLevels) {
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

        SimulatedWorld simulatedWorld = getOrCreateSimulatedWorld(targetDimId, entity.world.getWorldInfo(), targetProvider);
        simulatedWorld.dimension = dimensionName != null ? dimensionName : "unknown";

        // Find valid conditions using inline refinement (replacing ConditionRefiner)
        SpawnConditions result = findValidConditions(entity.getClass(), simulatedWorld,
            biomes, biomeGroundBlocksLimited, groundBlocksCombined, lightLevels, PROBE_Y_LEVELS);

        if (result != null) {
            return new SpawnConditions(
                biomes, result.groundBlocks, result.lightLevels, result.yLevels, result.timeOfDay, result.weather,
                result.hints, result.requiresSky, result.moonPhases, result.requiresSlimeChunk, result.requiresNether,
                dimensionName, targetDimId
            );
        }

        List<String> groundBlocks = Collections.singletonList("unknown");
        List<Integer> narrowedLight = new ArrayList<>();
        List<Integer> yLevels = new ArrayList<>();
        List<int[]> timeOfDay = null;  // null indicates unknown/not determined
        List<String> weather = Collections.singletonList("unknown");

        return new SpawnConditions(biomes, groundBlocks, narrowedLight, yLevels, timeOfDay, weather, null, null, dimensionName, targetDimId);
    }

    /**
     * Find valid spawn conditions by probing the simulated world.
     */
    private SpawnConditions findValidConditions(Class<? extends EntityLiving> entityClass,
                                                SimulatedWorld world,
                                                List<String> candidateBiomes,
                                                List<String> groundBlocksFinder,
                                                List<String> groundBlocksExpander,
                                                List<Integer> lightProbe,
                                                List<Integer> yLevels) {
        if (candidateBiomes.isEmpty() || groundBlocksFinder.isEmpty() || groundBlocksExpander.isEmpty()) return null;

        SampleFinder sampleFinder = new SampleFinder(entityClass, world);
        SampleFinder.ValidSample sample = sampleFinder.find(candidateBiomes, groundBlocksFinder, lightProbe, yLevels);
        if (sample == null) return sampleFinder.buildFailureResult(lightProbe);

        ConditionExpander expander = new ConditionExpander(entityClass, world);
        ConditionExpander.ExpandedConditions expanded = expander.expandAll(sample, candidateBiomes, groundBlocksExpander);
        return expander.toSpawnConditions(expanded);
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
