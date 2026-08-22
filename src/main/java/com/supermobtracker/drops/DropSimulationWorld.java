package com.supermobtracker.drops;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import sun.reflect.ReflectionFactory;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.loot.LootTableManager;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.util.ReflectionUtils;


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
    private WorldBorder worldBorder;

    /**
     * Private constructor - use createInstance() instead.
     */
    private DropSimulationWorld() {
        // This constructor is never actually called - we use ReflectionFactory
        // to create an instance without invoking any constructor
        super(null, null, null, 0, null);
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

            instance.collectedDrops = new ArrayList<>();

            // Set essential World fields that dropLoot and entity construction need

            // Set the rand field (used for loot randomization)
            Field randField = ReflectionUtils.getDeclaredField(World.class, "field_73012_v", "rand");
            randField.set(instance, new Random());

            // Set the provider field using a wrapper to isolate from real world state.
            // This prevents issues like simulated Ender Dragons registering with the
            // real DragonFightManager during an actual dragon fight.
            Field providerField = ReflectionUtils.getDeclaredField(World.class, "field_73011_w", "provider");
            SimulationProviderWrapper providerWrapper = new SimulationProviderWrapper(realWorld.provider);
            providerField.set(instance, providerWrapper);
            providerWrapper.attachToWorld(instance);

            // Set profiler (may be accessed during entity construction)
            Field profilerField = ReflectionUtils.getDeclaredField(World.class, "field_72984_F", "profiler");
            profilerField.set(instance, realWorld.profiler);

            // Set worldInfo (may be accessed for game rules, difficulty, etc.)
            Field worldInfoField = ReflectionUtils.getDeclaredField(World.class, "field_72986_A", "worldInfo");
            worldInfoField.set(instance, realWorld.getWorldInfo());

            // Set scoreboard (accessed by EntityWither and others for team display)
            instance.scoreboard = realWorld.getScoreboard();

            // Set isRemote to false (server-side behavior for loot generation)
            Field isRemoteField = ReflectionUtils.getDeclaredField(World.class, "field_72995_K", "isRemote");
            isRemoteField.set(instance, false);

            // Optional Alfheim compatibility: constructor-bypassed worlds skip mixin field
            // initialization, so we need to populate the lighting engine field manually.
            initializeAlfheimLightingEngine(instance, realWorld);

            // Create a fake empty chunk to return from getChunk() methods
            // Some mods call World.getEntitiesWithinAABB which uses getChunk internally
            // Use our SimulationChunk instead of EmptyChunk (which is client-only)
            instance.fakeChunk = new SimulationChunk(instance, 0, 0);

            // Create a world border (needed by EntityPlayer constructor)
            instance.worldBorder = new WorldBorder();

            // Initialize playerEntities to empty list to prevent NPE when entities look for players
            Field playerEntitiesField = ReflectionUtils.getDeclaredField(World.class,
                () -> SuperMobTracker.LOGGER.warn("Could not find World.playerEntities field"),
                "field_73010_i", "playerEntities");
            playerEntitiesField.set(instance, new ArrayList<EntityPlayer>());

            // Initialize loadedEntityList to empty list to prevent iteration issues
            Field loadedEntityListField = ReflectionUtils.getDeclaredField(World.class,
                () -> SuperMobTracker.LOGGER.warn("Could not find World.loadedEntityList field"),
                "field_72996_f", "loadedEntityList");
            loadedEntityListField.set(instance, new ArrayList<Entity>());

            // Initialize eventListeners to empty list (used by sound/event notification systems)
            Field eventListenersField = ReflectionUtils.getDeclaredField(World.class,
                () -> SuperMobTracker.LOGGER.warn("Could not find World.eventListeners field"),
                "field_73021_x", "eventListeners");
            eventListenersField.set(instance, new ArrayList<>());

            // Initialize capturedBlockSnapshots (Forge field used during world modifications)
            Field capturedField = ReflectionUtils.getDeclaredField(World.class, "field_147484_aC", "capturedBlockSnapshots");
            if (capturedField != null) capturedField.set(instance, new ArrayList<>());

            // Initialize loadedTileEntityList to empty list
            Field tileEntityListField = ReflectionUtils.getDeclaredField(World.class, "field_147482_g", "loadedTileEntityList");
            if (tileEntityListField != null) tileEntityListField.set(instance, new ArrayList<>());

            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DropSimulationWorld", e);
        }
    }

    /**
     * Initialize Alfheim's optional world lighting-engine field when present.
     * ReflectionFactory bypasses constructors, so mixin-backed field initialization
     * does not run for this fake world.
     * Reflection is used to avoid a hard dependency on a specific Alfheim version.
     */
    private static void initializeAlfheimLightingEngine(DropSimulationWorld instance, WorldServer realWorld) {
        try {
            Method getLightingEngine = findMethodRecursive(realWorld.getClass(), "getAlfheim$lightingEngine");
            if (getLightingEngine == null) return;

            Object lightingEngine = getLightingEngine.invoke(realWorld);
            if (lightingEngine == null) return;

            // Prefer a dedicated engine tied to the simulation world.
            try {
                Constructor<?> lightingConstructor = lightingEngine.getClass().getDeclaredConstructor(World.class);
                lightingConstructor.setAccessible(true);
                lightingEngine = lightingConstructor.newInstance(instance);
            } catch (ReflectiveOperationException ignored) {
                // Fall back to the real world's engine when constructor isn't accessible.
            }

            Field alfheimLightingField = findFieldRecursive(instance.getClass(), "alfheim$lightingEngine");
            if (alfheimLightingField == null) return;

            alfheimLightingField.setAccessible(true);

            Class<?> fieldType = alfheimLightingField.getType();
            if (fieldType.isInstance(lightingEngine)) {
                alfheimLightingField.set(instance, lightingEngine);
                return;
            }

            if (AtomicReference.class.isAssignableFrom(fieldType)) {
                alfheimLightingField.set(instance, new AtomicReference<>(lightingEngine));
                return;
            }

            SuperMobTracker.LOGGER.debug(
                "Skipping Alfheim lighting engine setup due to incompatible field type: {}",
                fieldType.getName()
            );
        } catch (ReflectiveOperationException e) {
            SuperMobTracker.LOGGER.debug("Skipping Alfheim lighting engine setup for simulation world", e);
        }
    }

    @Nullable
    private static Method findMethodRecursive(Class<?> type, String methodName) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodName.equals(method.getName()) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }

        return null;
    }

    @Nullable
    private static Field findFieldRecursive(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (fieldName.equals(field.getName())) return field;
            }
            current = current.getSuperclass();
        }

        return null;
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
    public boolean spawnEntity(@Nonnull Entity entityIn) {
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
    @Nonnull
    public LootTableManager getLootTableManager() {
        return lootTableManager;
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
    public boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return true;
    }

    @Override
    public boolean isAreaLoaded(@Nonnull BlockPos from, @Nonnull BlockPos to, boolean allowEmpty) {
        return true;
    }

    @Override
    @Nonnull
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    @Override
    @Nonnull
    public WorldBorder getWorldBorder() {
        return worldBorder;
    }

    @Override
    @Nonnull
    public IBlockState getBlockState(@Nonnull BlockPos pos) {
        // Return air for all block queries - entities aren't in the real world
        return Blocks.AIR.getDefaultState();
    }

    @Override
    @Nonnull
    public Chunk getChunk(int chunkX, int chunkZ) {
        // Return a fake empty chunk to prevent NPE in World.getEntitiesWithinAABB
        return fakeChunk;
    }

    @Override
    @Nonnull
    public Chunk getChunk(@Nonnull BlockPos pos) {
        return fakeChunk;
    }

    @Override
    public boolean handleMaterialAcceleration(@Nonnull AxisAlignedBB bb, @Nonnull Material material,
                                              @Nonnull Entity entity) {
        // Entity is never in water/lava/etc in our fake world
        return false;
    }

    @Override
    @Nonnull
    public List<AxisAlignedBB> getCollisionBoxes(@Nullable Entity entityIn, @Nonnull AxisAlignedBB aabb) {
        // Return empty list - no collision in simulation world
        return Collections.emptyList();
    }

    @Override
    public boolean containsAnyLiquid(@Nonnull AxisAlignedBB bb) {
        return false;
    }

    @Override
    public boolean isAirBlock(@Nonnull BlockPos pos) {
        return true;
    }

    // === Light-related overrides (needed by some entity AI classes) ===

    @Override
    public int getLightFor(@Nonnull EnumSkyBlock type, @Nonnull BlockPos pos) {
        // Return daylight level (max sky light, no block light)
        return type == EnumSkyBlock.SKY ? 15 : 0;
    }

    @Override
    public int getLightFromNeighbors(@Nonnull BlockPos pos) {
        return 15; // Full daylight
    }

    @Override
    public int getLight(@Nonnull BlockPos pos) {
        return 15; // Full light
    }

    @Override
    public int getLight(@Nonnull BlockPos pos, boolean checkNeighbors) {
        return 15;
    }

    @Override
    public float getLightBrightness(@Nonnull BlockPos pos) {
        return 1.0f; // Full brightness
    }

    @Override
    public int getLightFromNeighborsFor(@Nonnull EnumSkyBlock type, @Nonnull BlockPos pos) {
        return type == EnumSkyBlock.SKY ? 15 : 0;
    }

    @Override
    public int getSkylightSubtracted() {
        return 0; // No skylight subtraction (full daylight)
    }

    // === Entity query overrides (needed by some dropFewItems implementations) ===

    @Override
    @Nonnull
    public <T extends Entity> List<T> getEntitiesWithinAABB(
            @Nonnull Class<? extends T> classEntity, @Nonnull AxisAlignedBB bb) {
        // Return empty list - no other entities exist in our simulation world
        return Collections.emptyList();
    }

    @Override
    @Nonnull
    public <T extends Entity> List<T> getEntitiesWithinAABB(
            @Nonnull Class<? extends T> clazz, @Nonnull AxisAlignedBB aabb, @Nullable Predicate<? super T> filter) {
        return Collections.emptyList();
    }

    @Override
    @Nonnull
    public List<Entity> getEntitiesInAABBexcluding(
            @Nullable Entity entityIn, @Nonnull AxisAlignedBB bb, @Nullable Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }

    @Override
    @Nonnull
    public <T extends Entity> List<T> getEntities(
            @Nonnull Class<? extends T> entityType, @Nonnull Predicate<? super T> filter) {
        return Collections.emptyList();
    }

    @Override
    @Nonnull
    public List<Entity> getLoadedEntityList() {
        return Collections.emptyList();
    }

    @Override
    public void setEntityState(@Nonnull Entity entityIn, byte state) {
        // Ignore entity state changes - prevents particles and sounds in fake world
    }

    // Network isolation - prevents packets from being sent ===

    @Override
    public MinecraftServer getMinecraftServer() {
        // Return null to prevent access to server networking infrastructure
        return null;
    }

    @Override
    public ChunkProviderServer getChunkProvider() {
        // Return null to prevent chunk loading/generation
        return null;
    }

    @Override
    public void onEntityAdded(@Nonnull Entity entityIn) {
        // No-op - prevents entityTracker.track() which would send spawn packets
    }

    @Override
    public void onEntityRemoved(@Nonnull Entity entityIn) {
        // No-op - prevents entityTracker.untrack() which would send despawn packets
    }

    @Override
    public void updateEntityWithOptionalForce(@Nonnull Entity entityIn, boolean forceUpdate) {
        // No-op - prevents entity tracking updates
    }

    // === Player isolation - prevents entities from finding/targeting real players ===

    @Override
    @Nullable
    public EntityPlayer getClosestPlayer(double x, double y, double z, double distance, boolean spectator) {
        return null;
    }

    @Override
    @Nullable
    public EntityPlayer getClosestPlayerToEntity(@Nonnull Entity entityIn, double distance) {
        return null;
    }

    @Override
    @Nullable
    public EntityPlayer getNearestPlayerNotCreative(@Nonnull Entity entityIn, double distance) {
        return null;
    }

    @Override
    @Nullable
    public EntityPlayer getPlayerEntityByUUID(@Nonnull UUID uuid) {
        return null;
    }

    @Override
    @Nullable
    public EntityPlayer getPlayerEntityByName(@Nonnull String name) {
        return null;
    }

    @Override
    public boolean isAnyPlayerWithinRangeAt(double x, double y, double z, double range) {
        return false;
    }

    @Override
    @Nullable
    public EntityPlayer getNearestAttackablePlayer(double posX, double posY, double posZ, double maxXZDistance,
            double maxYDistance, @Nullable Function<EntityPlayer, Double> playerToDouble,
            @Nullable Predicate<EntityPlayer> predicate) {
        return null;
    }

    @Override
    @Nullable
    public EntityPlayer getNearestAttackablePlayer(@Nonnull Entity entityIn, double maxXZDistance,
                                                   double maxYDistance) {
        return null;
    }

    @Override
    @Nullable
    public EntityPlayer getNearestAttackablePlayer(@Nonnull BlockPos pos, double maxXZDistance, double maxYDistance) {
        return null;
    }

    // === Task scheduling isolation ===

    @Override
    @Nonnull
    public ListenableFuture<Object> addScheduledTask(@Nonnull Runnable task) {
        // Don't execute the task, return an already-completed future
        return Futures.immediateFuture(null);
    }

    @Override
    public boolean isCallingFromMinecraftThread() {
        // Pretend we're always on the main thread to avoid thread checks
        return true;
    }

    // === Sound/Effect isolation - prevents sound/particle packets ===

    @Override
    public void playSound(@Nullable EntityPlayer player, double x, double y, double z,
                          @Nonnull SoundEvent sound, @Nonnull SoundCategory category, float volume, float pitch) {
        // No-op - don't send sound packets
    }

    @Override
    public void playSound(double x, double y, double z, @Nonnull SoundEvent sound,
                          @Nonnull SoundCategory category, float volume, float pitch, boolean distanceDelay) {
        // No-op - don't send sound packets
    }

    @Override
    public void playEvent(@Nullable EntityPlayer player, int type, @Nonnull BlockPos pos, int data) {
        // No-op - don't send event packets
    }

    @Override
    public void playEvent(int type, @Nonnull BlockPos pos, int data) {
        // No-op - don't send event packets
    }

    @Override
    public void spawnParticle(@Nonnull EnumParticleTypes particleType, double xCoord, double yCoord, double zCoord,
                              int numberOfParticles, double xOffset, double yOffset, double zOffset,
                              double particleSpeed, @Nonnull int... particleArguments) {
        // No-op - don't send particle packets
    }

    @Override
    public void spawnParticle(@Nonnull EnumParticleTypes particleType, boolean longDistance,
                              double xCoord, double yCoord, double zCoord, int numberOfParticles,
                              double xOffset, double yOffset, double zOffset, double particleSpeed,
                              @Nonnull int... particleArguments) {
        // No-op - don't send particle packets
    }

    // === Block update isolation - prevents block update packets ===

    @Override
    public void notifyBlockUpdate(@Nonnull BlockPos pos, @Nonnull IBlockState oldState,
                                  @Nonnull IBlockState newState, int flags) {
        // No-op - don't send block update packets
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        // No-op - don't trigger render updates
    }

    @Override
    public void notifyNeighborsRespectDebug(@Nonnull BlockPos pos, @Nonnull Block blockType, boolean updateObservers) {
        // No-op - don't notify neighbors
    }

    @Override
    public void notifyNeighborsOfStateChange(@Nonnull BlockPos pos, @Nonnull Block blockType,
                                             boolean updateObservers) {
        // No-op - don't notify neighbors
    }
}
