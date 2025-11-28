package com.supermobtracker.spawn;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.WorldInfo;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.EntityEntry;


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
    private static final List<Integer> PROBE_Y_LEVELS = Arrays.asList(1, 10, 32, 63, 64, 96, 128, 150);

    // Light levels to probe for finding first valid sample
    private static final List<Integer> PROBE_LIGHT_LEVELS = Arrays.asList(0, 1, 7, 12, 14, 15);

    // Entity instance cache
    private Map<ResourceLocation, EntityLiving> entityInstanceCache = new HashMap<>();

    // Last computed result for GUI helpers
    private SpawnConditions lastResult;

    // Last error during analysis (if any)
    private Throwable lastError;

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

        public SpawnConditions(List<String> biomes,
                               List<String> groundBlocks,
                               List<Integer> lightLevels,
                               List<Integer> yLevels,
                               List<String> timeOfDay,
                               List<String> weather,
                               List<String> hints) {
            this.biomes = biomes;
            this.groundBlocks = groundBlocks;
            this.lightLevels = lightLevels;
            this.yLevels = yLevels;
            this.timeOfDay = timeOfDay;
            this.weather = weather;
            this.hints = hints == null ? new ArrayList<>() : hints;
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
            }

            public void bindQueryTracker(Map<String, Boolean> queriedConditions) {
                this.queriedConditions = queriedConditions;
            }

            @Override
            public DimensionType getDimensionType() {
                this.queriedConditions.put("dimension", true);
                return baseProvider.getDimensionType();
            }
        }

        public static SimulatedWorld fromReal(World world) {
            return new SimulatedWorld(null, world.getWorldInfo(), new SimulatedProvider(world.provider), new Profiler(), true);
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
        return false; // FIXME: 1.12 lacks direct neutral type
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
        return false; // FIXME: implement
    }

    public boolean isTameable(ResourceLocation entityId) {
        EntityLiving entity = getEntityInstance(entityId);
        return entity != null && EntityTameable.class.isAssignableFrom(entity.getClass());
    }

    public Vec3d getEntitySize(ResourceLocation entityId) {
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
        lastError = null;
        lastResult = null;

        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(entityId);
        if (entry == null || !(EntityLiving.class.isAssignableFrom(entry.getEntityClass()))) return null;

        try {
            EntityLiving entity = getEntityInstance(entityId);
            if (entity == null) return null;

            // Try to get native biomes from the entity
            List<String> nativeBiomes = getNativeBiomes(entityId, entry.getEntityClass());
            boolean hasNativeBiomes = nativeBiomes != null && !nativeBiomes.isEmpty();

            List<String> biomes = hasNativeBiomes ? nativeBiomes : ALL_BIOME_NAMES;

            List<String> groundBlocks;
            if (isFlying(entityId)) {
                groundBlocks = Arrays.asList("air");
            } else if (isAquatic(entityId)) {
                groundBlocks = Arrays.asList("water");
            } else {
                groundBlocks = new ArrayList<>(GROUND_BLOCKS);
            }

            SpawnConditions result = computeSpawnConditions(entity, biomes, hasNativeBiomes, groundBlocks, PROBE_LIGHT_LEVELS);
            if (result == null) {
                List<Integer> yLevels = Arrays.asList(PROBE_Y_LEVELS.get(0), PROBE_Y_LEVELS.get(PROBE_Y_LEVELS.size() - 1));
                List<String> timeOfDay = isHostile(entityId) ? Arrays.asList("night") : Arrays.asList("day");
                List<String> weather = Arrays.asList("clear");

                result = new SpawnConditions(
                    hasNativeBiomes ? biomes : Arrays.asList("unknown"),
                    groundBlocks, PROBE_LIGHT_LEVELS, yLevels, timeOfDay, weather, null
                );
            }

            lastResult = result;

            return result;
        } catch (Throwable t) {
            lastError = t;
            return null;
        }
    }

    /**
     * Get native spawn biomes for an entity from the biome spawn lists.
     */
    private List<String> getNativeBiomes(ResourceLocation entityId, Class<?> entityClass) {
        List<String> nativeBiomes = new ArrayList<>();
        EnumCreatureType creatureType = CREATURE_TYPE_CACHE.get(entityId);

        if (creatureType == null) return null;

        // TODO: maintain a static cache of native biomes per entity to avoid recomputing every time
        for (Biome biome : ALL_BIOMES) {
            List<Biome.SpawnListEntry> spawnList = biome.getSpawnableList(creatureType);
            for (Biome.SpawnListEntry spawnEntry : spawnList) {
                if (spawnEntry.entityClass.equals(entityClass)) {
                    nativeBiomes.add(biome.getRegistryName().toString());
                    break;
                }
            }
        }

        return nativeBiomes.isEmpty() ? null : nativeBiomes;
    }

    /**
     * Compute spawn conditions for an entity.
     */
    private SpawnConditions computeSpawnConditions(EntityLiving entity, List<String> biomes, boolean nativeBiomes,
                                                   List<String> groundBlocks, List<Integer> lightLevels) {
        SimulatedWorld simulatedWorld = SimulatedWorld.fromReal(entity.world);
        simulatedWorld.dimension = entity.world.provider.getDimensionType().getName().toLowerCase();

        ConditionRefiner refiner = new ConditionRefiner(entity.getClass(), simulatedWorld);
        SpawnConditions result = refiner.findValidConditions(biomes, nativeBiomes, groundBlocks, lightLevels, PROBE_Y_LEVELS);

        if (result != null) {
            if (nativeBiomes) {
                return new SpawnConditions(
                    biomes, result.groundBlocks, result.lightLevels, result.yLevels, result.timeOfDay, result.weather, result.hints
                );
            }

            return result;
        }

        List<Integer> narrowedLight = refiner.refineLightLevels(lightLevels);
        List<Integer> yLevels = new ArrayList<>();
        List<String> timeOfDay = Arrays.asList("unknown");
        List<String> weather = Arrays.asList("unknown");
        List<String> resultBiomes = nativeBiomes ? biomes : Arrays.asList("unknown");

        return new SpawnConditions(resultBiomes, groundBlocks, narrowedLight, yLevels, timeOfDay, weather, null);
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
