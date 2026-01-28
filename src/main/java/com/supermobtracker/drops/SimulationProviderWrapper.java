package com.supermobtracker.drops;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;

import com.supermobtracker.SuperMobTracker;


/**
 * A wrapper around a WorldProvider that delegates most calls to the wrapped provider.
 * This wrapper isolates the simulation world from the real world's provider state,
 * preventing issues like the DragonFightManager being modified during simulation.
 *
 * Key difference from the real provider:
 * - DragonFightManager is never initialized (prevents simulated Ender Dragons from
 *   registering with the real fight manager)
 * - No chunk generation or weather updates
 * FIXME: does not fix the Ender Dragon corruption issue.
 *        Nullifying the DragonFightManager in real provider seemed to do the trick,
 *        but could interfere with real dragon fights.
 */
public class SimulationProviderWrapper extends WorldProvider {

    private final WorldProvider wrapped;

    // Reflection cache for world field
    private static Field worldField = null;
    private static boolean worldFieldSearched = false;

    private static Field getWorldField() {
        if (worldFieldSearched) return worldField;

        worldFieldSearched = true;

        try {
            worldField = WorldProvider.class.getDeclaredField("field_76579_a"); // world
            worldField.setAccessible(true);

            return worldField;
        } catch (NoSuchFieldException e) {
            try {
                worldField = WorldProvider.class.getDeclaredField("world");
                worldField.setAccessible(true);

                return worldField;
            } catch (NoSuchFieldException e2) {
                SuperMobTracker.LOGGER.warn("Could not find WorldProvider.world field");

                return null;
            }
        }
    }

    public SimulationProviderWrapper(WorldProvider wrapped) {
        this.wrapped = wrapped;

        // Copy the world reference from the wrapped provider
        Field field = getWorldField();
        if (field != null) {
            try {
                field.set(this, field.get(wrapped));
            } catch (IllegalAccessException e) {
                SuperMobTracker.LOGGER.warn("Could not copy world field to wrapper", e);
            }
        }
    }

    /**
     * Attach this wrapper to a simulation world.
     * This sets the world field to the simulation world while keeping other
     * behavior delegating to the wrapped provider.
     */
    public void attachToWorld(DropSimulationWorld simWorld) {
        Field field = getWorldField();
        if (field != null) {
            try {
                field.set(this, simWorld);
            } catch (IllegalAccessException e) {
                SuperMobTracker.LOGGER.warn("Could not attach wrapper to simulation world", e);
            }
        }
    }

    // === Core dimension info - delegate to wrapped ===

    @Override
    public DimensionType getDimensionType() {
        return wrapped.getDimensionType();
    }

    @Override
    @Nullable
    public String getSaveFolder() {
        return wrapped.getSaveFolder();
    }

    // === Biome and terrain - delegate to wrapped ===

    @Override
    public BiomeProvider getBiomeProvider() {
        return wrapped.getBiomeProvider();
    }

    @Override
    public Biome getBiomeForCoords(BlockPos pos) {
        return wrapped.getBiomeForCoords(pos);
    }

    @Override
    public boolean isSurfaceWorld() {
        return wrapped.isSurfaceWorld();
    }

    @Override
    public boolean canRespawnHere() {
        return wrapped.canRespawnHere();
    }

    @Override
    public float getCloudHeight() {
        return wrapped.getCloudHeight();
    }

    @Override
    public boolean hasSkyLight() {
        return wrapped.hasSkyLight();
    }

    // === Forge extensions - delegate to wrapped ===

    @Override
    public int getHeight() {
        return wrapped.getHeight();
    }

    @Override
    public int getActualHeight() {
        return wrapped.getActualHeight();
    }

    @Override
    public boolean isNether() {
        return wrapped.isNether();
    }

    @Override
    public double getMovementFactor() {
        return wrapped.getMovementFactor();
    }

    @Override
    public boolean doesWaterVaporize() {
        return wrapped.doesWaterVaporize();
    }

    // === Methods that should NOT do anything in simulation ===

    @Override
    public IChunkGenerator createChunkGenerator() {
        // Return null to prevent chunk generation in simulation world
        return null;
    }

    @Override
    public void onWorldSave() {
        // Don't save anything
    }

    @Override
    public void onWorldUpdateEntities() {
        // Don't update entities
    }

    @Override
    public void calculateInitialWeather() {
        // Don't calculate weather in simulation
    }

    @Override
    public void updateWeather() {
        // Don't update weather in simulation
    }

    @Override
    public boolean canDoLightning(Chunk chunk) {
        return false;
    }

    @Override
    public boolean canDoRainSnowIce(Chunk chunk) {
        return false;
    }

    @Override
    public BlockPos getRandomizedSpawnPoint() {
        // Return a safe default spawn point to avoid NPE when world fields are not fully initialized
        return BlockPos.ORIGIN;
    }

    @Override
    public BlockPos getSpawnPoint() {
        // Return a safe default spawn point
        return BlockPos.ORIGIN;
    }

    @Override
    public BlockPos getSpawnCoordinate() {
        // Return a safe spawn coordinate for dimensions that use it (like the End)
        return BlockPos.ORIGIN;
    }

    // Override init to prevent DragonFightManager creation for End dimension
    @Override
    protected void init() {
        // Copy essential fields from wrapped provider but don't call super.init()
        // which would create a DragonFightManager for the End dimension
        this.hasSkyLight = wrapped.hasSkyLight();
        this.biomeProvider = wrapped.getBiomeProvider();
    }
}
