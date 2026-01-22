package com.supermobtracker.drops;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Predicate;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.end.DragonFightManager;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.storage.loot.LootTableManager;

import sun.reflect.ReflectionFactory;

import com.supermobtracker.SuperMobTracker;


/**
 * A minimal fake WorldServer for drop simulation.
 * Created without calling constructors to avoid world initialization side effects.
 * Only implements the bare minimum needed for dropLoot to work.
 */
@SuppressWarnings("restriction")
public class DropSimulationWorld extends WorldServer {
    private List<ItemStack> collectedDrops = new ArrayList<>();
    private LootTableManager lootTableManager;
    private Scoreboard scoreboard;
    private Chunk fakeChunk;

    // Cached reflection objects for DragonFightManager nullification
    private static Field dragonFightField = null;
    private static boolean dragonFightFieldSearched = false;

    /**
     * Private constructor - use createInstance() instead.
     */
    private DropSimulationWorld() {
        // This constructor is never actually called - we use ReflectionFactory
        // to create an instance without invoking any constructor
        super(null, null, null, 0, null);
    }

    /**
     * Get the dragonFightManager field from WorldProviderEnd via reflection.
     */
    private static Field getDragonFightField() {
        if (dragonFightFieldSearched) return dragonFightField;

        dragonFightFieldSearched = true;

        try {
            // field_186064_g = dragonFightManager
            dragonFightField = WorldProviderEnd.class.getDeclaredField("field_186064_g");
            dragonFightField.setAccessible(true);

            return dragonFightField;
        } catch (NoSuchFieldException e) {
            // Try MCP name
            try {
                dragonFightField = WorldProviderEnd.class.getDeclaredField("dragonFightManager");
                dragonFightField.setAccessible(true);

                return dragonFightField;
            } catch (NoSuchFieldException e2) {
                SuperMobTracker.LOGGER.warn("Could not find dragonFightManager field - dragon simulation may cause respawn issues");

                return null;
            }
        }
    }

    /**
     * Create a DropSimulationWorld instance without triggering world initialization.
     * Uses ReflectionFactory to bypass constructors.
     */
    public static DropSimulationWorld createInstance(WorldServer realWorld) {
        try {
            // Use ReflectionFactory to create instance without calling constructor
            ReflectionFactory rf = ReflectionFactory.getReflectionFactory();
            Constructor<Object> objectConstructor = Object.class.getDeclaredConstructor();
            Constructor<?> constructor = rf.newConstructorForSerialization(DropSimulationWorld.class, objectConstructor);

            DropSimulationWorld instance = (DropSimulationWorld) constructor.newInstance();

            // Initialize our custom fields
            instance.lootTableManager = realWorld.getLootTableManager();

            // Initialize the collectedDrops list (it's final so we need reflection)
            // Field dropsField = DropSimulationWorld.class.getDeclaredField("collectedDrops");
            // dropsField.setAccessible(true);
            // dropsField.set(instance, new ArrayList<ItemStack>());
            instance.collectedDrops = new ArrayList<>();

            // Set essential World fields that dropLoot and entity construction need

            // Set the rand field (used for loot randomization)
            Field randField = World.class.getDeclaredField("field_73012_v"); // rand
            randField.setAccessible(true);
            randField.set(instance, new java.util.Random());

            // Set the provider field (Entity constructor accesses world.provider.getDimension())
            Field providerField = World.class.getDeclaredField("field_73011_w"); // provider
            providerField.setAccessible(true);
            providerField.set(instance, realWorld.provider);

            // Set profiler (may be accessed during entity construction)
            Field profilerField = World.class.getDeclaredField("field_72984_F"); // profiler
            profilerField.setAccessible(true);
            profilerField.set(instance, realWorld.profiler);

            // Set worldInfo (may be accessed for game rules, difficulty, etc.)
            Field worldInfoField = World.class.getDeclaredField("field_72986_A"); // worldInfo
            worldInfoField.setAccessible(true);
            worldInfoField.set(instance, realWorld.getWorldInfo());

            // Set scoreboard (accessed by EntityWither and others for team display)
            instance.scoreboard = realWorld.getScoreboard();

            // Set isRemote to false (server-side behavior for loot generation)
            Field isRemoteField = World.class.getDeclaredField("field_72995_K"); // isRemote
            isRemoteField.setAccessible(true);
            isRemoteField.set(instance, false);

            // Create a fake empty chunk to return from getChunk() methods
            // Some mods call World.getEntitiesWithinAABB which uses getChunk internally
            instance.fakeChunk = new EmptyChunk(instance, 0, 0);

            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DropSimulationWorld", e);
        }
    }

    /**
     * Get all collected drops and clear the list.
     */
    public List<ItemStack> collectAndClearDrops() {
        List<ItemStack> result = new ArrayList<>(collectedDrops);
        collectedDrops.clear();

        return result;
    }

    /**
     * Clear collected drops.
     */
    public void clearDrops() {
        collectedDrops.clear();
    }

    // === Override methods that dropLoot and entity spawning might call ===

    @Override
    public boolean spawnEntity(Entity entityIn) {
        // Intercept EntityItem spawns and collect their ItemStacks
        if (entityIn instanceof EntityItem) {
            EntityItem itemEntity = (EntityItem) entityIn;
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty()) collectedDrops.add(stack.copy());
        }

        // Don't actually spawn anything
        return false;
    }

    @Override
    public LootTableManager getLootTableManager() {
        return lootTableManager;
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
    public boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return true;
    }

    @Override
    public boolean isAreaLoaded(BlockPos from, BlockPos to, boolean allowEmpty) {
        return true;
    }

    @Override
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        // Return air for all block queries - entities aren't in the real world
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public Chunk getChunk(int chunkX, int chunkZ) {
        // Return a fake empty chunk to prevent NPE in World.getEntitiesWithinAABB
        return fakeChunk;
    }

    @Override
    public Chunk getChunk(BlockPos pos) {
        return fakeChunk;
    }

    @Override
    public boolean handleMaterialAcceleration(AxisAlignedBB bb, Material material, Entity entity) {
        // Entity is never in water/lava/etc in our fake world
        return false;
    }

    @Override
    public boolean containsAnyLiquid(AxisAlignedBB bb) {
        return false;
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return true;
    }

    // === Light-related overrides (needed by some entity AI classes) ===

    @Override
    public int getLightFor(EnumSkyBlock type, BlockPos pos) {
        // Return daylight level (max sky light, no block light)
        return type == EnumSkyBlock.SKY ? 15 : 0;
    }

    @Override
    public int getLightFromNeighbors(BlockPos pos) {
        return 15; // Full daylight
    }

    @Override
    public int getLight(BlockPos pos) {
        return 15; // Full light
    }

    @Override
    public int getLight(BlockPos pos, boolean checkNeighbors) {
        return 15;
    }

    @Override
    public float getLightBrightness(BlockPos pos) {
        return 1.0f; // Full brightness
    }

    @Override
    public int getLightFromNeighborsFor(EnumSkyBlock type, BlockPos pos) {
        return type == EnumSkyBlock.SKY ? 15 : 0;
    }

    @Override
    public int getSkylightSubtracted() {
        return 0; // No skylight subtraction (full daylight)
    }

    // === Entity query overrides (needed by some dropFewItems implementations) ===

    @Override
    public <T extends Entity> List<T> getEntitiesWithinAABB(Class<? extends T> classEntity, AxisAlignedBB bb) {
        // Return empty list - no other entities exist in our simulation world
        return Collections.emptyList();
    }

    @Override
    public <T extends Entity> List<T> getEntitiesWithinAABB(Class<? extends T> clazz, AxisAlignedBB aabb, @Nullable Predicate<? super T> filter) {
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getEntitiesInAABBexcluding(@Nullable Entity entityIn, AxisAlignedBB bb, @Nullable Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }

    @Override
    public <T extends Entity> List<T> getEntities(Class<? extends T> entityType, Predicate<? super T> filter) {
        return Collections.emptyList();
    }

    @Override
    public List<Entity> getLoadedEntityList() {
        return Collections.emptyList();
    }

    @Override
    public void setEntityState(Entity entityIn, byte state) {
        // Ignore entity state changes - prevents particles and sounds in fake world
    }

    // === DragonFightManager protection for End dimension ===

    // Store the original DragonFightManager when nullified
    private DragonFightManager savedDragonFightManager = null;
    private boolean dragonFightManagerNulled = false;

    /**
     * Temporarily nullify the DragonFightManager on the world provider to prevent
     * the Ender Dragon from interacting with the real fight manager during simulation.
     * Call restoreDragonFightManager() when done.
     */
    public void nullifyDragonFightManager() {
        if (dragonFightManagerNulled || !(this.provider instanceof WorldProviderEnd)) return;

        Field field = getDragonFightField();
        if (field == null) return;

        try {
            savedDragonFightManager = (DragonFightManager) field.get(this.provider);
            field.set(this.provider, null);
            dragonFightManagerNulled = true;
        } catch (IllegalAccessException e) {
            SuperMobTracker.LOGGER.warn("Failed to nullify dragonFightManager", e);
        }
    }

    /**
     * Restore the previously nullified DragonFightManager.
     */
    public void restoreDragonFightManager() {
        if (!dragonFightManagerNulled || !(this.provider instanceof WorldProviderEnd)) return;

        Field field = getDragonFightField();
        if (field == null) return;

        try {
            field.set(this.provider, savedDragonFightManager);
            savedDragonFightManager = null;
            dragonFightManagerNulled = false;
        } catch (IllegalAccessException e) {
            SuperMobTracker.LOGGER.warn("Failed to restore dragonFightManager", e);
        }
    }
}
