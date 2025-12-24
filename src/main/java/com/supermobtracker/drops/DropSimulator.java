package com.supermobtracker.drops;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.Loader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraft.world.storage.loot.LootTableManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import com.mojang.authlib.GameProfile;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.util.LogMuter;


/**
 * Simulates mob kills to collect drop statistics.
 * Uses a fake WorldServer that only implements loot-related functionality,
 * avoiding any interaction with the real game world.
 */
public class DropSimulator {

    // Fixed UUID for fake player - allows FakePlayerFactory to cache the player
    private static final UUID FAKE_PLAYER_UUID = UUID.randomUUID();

    // Only one simulation can run at a time, and we only keep one result
    private static SimulationTask activeTask = null;
    private static ResourceLocation activeEntityId = null;
    private static DropSimulationResult lastResult = null;

    // Reflection cache for dropLoot method
    private static Method dropLootMethod = null;
    private static boolean dropLootMethodSearched = false;

    // Reflection cache for getLootTable method
    private static Method getLootTableMethod = null;
    private static boolean getLootTableMethodSearched = false;

    // Reflection cache for attackingPlayer field (needed for killed_by_player loot condition)
    private static Field attackingPlayerField = null;
    private static boolean attackingPlayerFieldSearched = false;

    // Reflection cache for SpellBook check
    private static boolean isWizardryLoaded = false;
    private static boolean isSpellBookChecked = false;
    private static Class <?> spellBookClass = null;

    // Cached fake player for simulations
    private static EntityPlayer fakePlayer = null;

    /**
     * Start or get an existing simulation for the given entity.
     * Only one simulation/result is kept at a time to minimize memory usage.
     * @param entityId The entity to simulate kills for
     * @return The simulation task (may be in progress or completed)
     */
    public static synchronized SimulationTask getOrStartSimulation(ResourceLocation entityId) {
        // If we have a completed result for THIS entity, return it
        if (entityId.equals(activeEntityId) && lastResult != null) {
            SimulationTask completed = new SimulationTask(entityId);
            completed.result = lastResult;
            completed.completed = true;
            completed.progress.set(lastResult.simulationCount);
            completed.total = lastResult.simulationCount;

            return completed;
        }

        // If there's an active task for THIS entity, return it
        if (activeTask != null && entityId.equals(activeEntityId) && !activeTask.completed) return activeTask;

        // Different entity selected - cancel any running task and clear previous result
        if (activeTask != null) activeTask.cancel();
        lastResult = null;
        activeEntityId = entityId;

        // Start a new simulation
        SimulationTask task = new SimulationTask(entityId);
        activeTask = task;

        Thread simulationThread = new Thread(() -> {
            try {
                task.run();
            } finally {
                synchronized (DropSimulator.class) {
                    // Only store result if this is still the active entity
                    if (entityId.equals(activeEntityId) && task.result != null) lastResult = task.result;
                }
            }
        }, "DropSimulator-" + entityId);
        simulationThread.setDaemon(true);
        simulationThread.start();

        return task;
    }

    /**
     * Check if a simulation is in progress for the given entity.
     */
    public static synchronized boolean isSimulationInProgress(ResourceLocation entityId) {
        return activeTask != null && entityId.equals(activeEntityId) && !activeTask.completed;
    }

    /**
     * Get the cached result for an entity, or null if not available.
     */
    public static synchronized DropSimulationResult getCachedResult(ResourceLocation entityId) {
        if (entityId.equals(activeEntityId)) return lastResult;

        return null;
    }

    /**
     * Clear the result cache for a specific entity.
     */
    public static synchronized void clearCache(ResourceLocation entityId) {
        if (entityId.equals(activeEntityId)) lastResult = null;
    }

    /**
     * Clear all cached results.
     */
    public static synchronized void clearAllCaches() {
        if (activeTask != null) activeTask.cancel();

        activeTask = null;
        activeEntityId = null;
        lastResult = null;
    }

    /**
     * Get the dropLoot method via reflection.
     */
    private static Method getDropLootMethod() {
        if (dropLootMethodSearched) return dropLootMethod;

        dropLootMethodSearched = true;

        try {
            // Try obfuscated name first (func_184610_a in 1.12.2)
            dropLootMethod = EntityLiving.class.getDeclaredMethod("func_184610_a", boolean.class, int.class, DamageSource.class);
            dropLootMethod.setAccessible(true);

            return dropLootMethod;
        } catch (NoSuchMethodException e) {
            // Try deobfuscated name
            try {
                dropLootMethod = EntityLiving.class.getDeclaredMethod("dropLoot", boolean.class, int.class, DamageSource.class);
                dropLootMethod.setAccessible(true);

                return dropLootMethod;
            } catch (NoSuchMethodException e2) {
                SuperMobTracker.LOGGER.error("Could not find dropLoot method", e2);

                return null;
            }
        }
    }

    /**
     * Get the attackingPlayer field via reflection.
     * This field must be set for killed_by_player loot conditions to work.
     */
    private static Field getAttackingPlayerField() {
        if (attackingPlayerFieldSearched) return attackingPlayerField;

        attackingPlayerFieldSearched = true;

        try {
            // Try obfuscated name first (field_70717_bb in 1.12.2)
            attackingPlayerField = EntityLivingBase.class.getDeclaredField("field_70717_bb");
            attackingPlayerField.setAccessible(true);

            return attackingPlayerField;
        } catch (NoSuchFieldException e) {
            // Try deobfuscated name
            try {
                attackingPlayerField = EntityLivingBase.class.getDeclaredField("attackingPlayer");
                attackingPlayerField.setAccessible(true);

                return attackingPlayerField;
            } catch (NoSuchFieldException e2) {
                SuperMobTracker.LOGGER.error("Could not find attackingPlayer field", e2);

                return null;
            }
        }
    }

    /**
     * Get the getLootTable method via reflection.
     */
    private static Method getLootTableMethod() {
        if (getLootTableMethodSearched) return getLootTableMethod;

        getLootTableMethodSearched = true;

        try {
            // Try obfuscated name first (func_184647_J in 1.12.2)
            getLootTableMethod = EntityLiving.class.getDeclaredMethod("func_184647_J");
            getLootTableMethod.setAccessible(true);

            return getLootTableMethod;
        } catch (NoSuchMethodException e) {
            // Try deobfuscated name
            try {
                getLootTableMethod = EntityLiving.class.getDeclaredMethod("getLootTable");
                getLootTableMethod.setAccessible(true);

                return getLootTableMethod;
            } catch (NoSuchMethodException e2) {
                SuperMobTracker.LOGGER.error("Could not find getLootTable method", e2);

                return null;
            }
        }
    }

    /**
     * Represents an ongoing or completed simulation task.
     */
    public static class SimulationTask {
        public final ResourceLocation entityId;
        public final AtomicInteger progress = new AtomicInteger(0);
        public int total;
        public volatile boolean completed = false;
        public volatile boolean cancelled = false;
        public volatile DropSimulationResult result = null;
        public volatile String errorMessage = null;

        SimulationTask(ResourceLocation entityId) {
            this.entityId = entityId;
            this.total = ModConfig.clientDropSimulationCount;
        }

        /**
         * Get the current progress percentage (0-100).
         */
        public int getProgressPercent() {
            if (total <= 0) return 0;

            return Math.min(100, (progress.get() * 100) / total);
        }

        /**
         * Cancel this simulation task.
         */
        public void cancel() {
            cancelled = true;
        }

        /**
         * Run the simulation (in background thread using fake world).
         */
        void run() {
            // Mute spammy loggers during simulation
            LogMuter.muteLoggers();
            try {
                runSimulation();
            } finally {
                LogMuter.restoreLoggers();
            }
        }

        /**
         * Internal simulation logic.
         */
        private void runSimulation() {
            Method dropLoot = getDropLootMethod();
            if (dropLoot == null) {
                errorMessage = "Could not access dropLoot method";
                completed = true;

                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            IntegratedServer integratedServer = mc.getIntegratedServer();
            if (integratedServer == null) {
                // Multiplayer - can't simulate drops (no access to loot tables)
                errorMessage = "gui.mobtracker.drops.serverSideOnly";
                completed = true;

                return;
            }

            // Get the real server world to extract loot table manager
            int dimension = mc.world != null ? mc.world.provider.getDimension() : 0;
            WorldServer realWorld = integratedServer.getWorld(dimension);
            if (realWorld == null || realWorld.getLootTableManager() == null) {
                errorMessage = "gui.mobtracker.drops.serverSideOnly";
                completed = true;

                return;
            }

            EntityEntry entry = ForgeRegistries.ENTITIES.getValue(entityId);
            if (entry == null || !EntityLiving.class.isAssignableFrom(entry.getEntityClass())) {
                errorMessage = "Invalid entity";
                completed = true;

                return;
            }

            // Check if entity is unstable for simulation (corrupts global state)
            if (ModConfig.isUnstableSimulationEntity(entityId.toString())) {
                errorMessage = I18n.translateToLocal("gui.mobtracker.drops.unstableSimulation");
                completed = true;

                return;
            }

            // Create fake world for simulation - bypasses constructor to avoid side effects
            DropSimulationWorld simWorld;
            try {
                simWorld = DropSimulationWorld.createInstance(realWorld);
            } catch (Exception e) {
                SuperMobTracker.LOGGER.error("Failed to create simulation world for " + entityId, e);
                errorMessage = "Failed to create simulation world";
                completed = true;

                return;
            }

            // Create a fake player for "killed_by_player" loot conditions
            // This makes loot tables with killed_by_player condition work properly
            if (fakePlayer == null) {
                fakePlayer = FakePlayerFactory.get(
                    realWorld,
                    new GameProfile(FAKE_PLAYER_UUID, "[SuperMobTracker]")
                );
                // Set creative mode to bypass mod skill checks (e.g., AoA Hunter levels)
                fakePlayer.capabilities.isCreativeMode = true;
            }
            DamageSource playerDamage = DamageSource.causePlayerDamage(fakePlayer);

            // Accumulate drops across all simulations
            Map<DropKey, DropAccumulator> dropMap = new HashMap<>();

            // Pre-test: Try to create one entity to check if it works
            Entity testEntity;
            try {
                testEntity = EntityList.createEntityByIDFromName(entry.getRegistryName(), simWorld);
                if (!(testEntity instanceof EntityLiving)) {
                    errorMessage = I18n.translateToLocal("gui.mobtracker.drops.entityNotLiving");
                    completed = true;

                    return;
                }
            } catch (Exception e) {
                SuperMobTracker.LOGGER.warn("Cannot simulate drops for " + entityId + " - entity construction failed", e);
                errorMessage = I18n.translateToLocal("gui.mobtracker.drops.entityConstructionFailed");
                completed = true;

                return;
            }

            for (int i = progress.get(); i < total && !cancelled; i++) {
                try {
                    // Create entity in our fake world
                    Entity rawEntity = EntityList.createEntityByIDFromName(entry.getRegistryName(), simWorld);
                    if (!(rawEntity instanceof EntityLiving)) {
                        progress.incrementAndGet();
                        continue;
                    }

                    EntityLiving entity = (EntityLiving) rawEntity;

                    // Set attackingPlayer field for killed_by_player loot conditions
                    // This is the field that loot table conditions actually check
                    Field attackingPlayerField = getAttackingPlayerField();
                    if (attackingPlayerField != null) {
                        try {
                            attackingPlayerField.set(entity, fakePlayer);
                        } catch (IllegalAccessException e) {
                            SuperMobTracker.LOGGER.warn("Failed to set attackingPlayer field", e);
                        }
                    }

                    // Clear previous drops
                    simWorld.clearDrops();

                     // Call dropLoot - items will be collected by our spawnEntity override
                    dropLoot.invoke(entity, true, 0, playerDamage);

                    // Collect the base drops from loot tables
                    List<ItemStack> baseDrops = simWorld.collectAndClearDrops();

                    // Convert ItemStacks to EntityItems for LivingDropsEvent
                    List<EntityItem> entityItems = new ArrayList<>();
                    for (ItemStack stack : baseDrops) {
                        if (!stack.isEmpty()) {
                            EntityItem entityItem = new EntityItem(simWorld, 0, 0, 0, stack.copy());
                            entityItems.add(entityItem);
                        }
                    }

                    // Fire LivingDropsEvent to let mods add/modify drops
                    // This is how mods like AoA, AA, MA, etc. add their drops
                    LivingDropsEvent event = new LivingDropsEvent(entity, playerDamage, entityItems, 0, true);
                    MinecraftForge.EVENT_BUS.post(event);

                    // Collect any items that mods spawned directly via entityDropItem()
                    // Some mods (like AoA) use entity.entityDropItem() instead of adding to event.getDrops()
                    List<ItemStack> spawnedDuringEvent = simWorld.collectAndClearDrops();

                    // Collect final drops (if event wasn't cancelled)
                    if (!event.isCanceled()) {
                        // First, collect drops from the event's drop list
                        // Make a defensive copy to avoid ConcurrentModificationException
                        List<EntityItem> eventDrops = new ArrayList<>(event.getDrops());
                        for (EntityItem entityItem : eventDrops) {
                            ItemStack stack = entityItem.getItem();
                            if (!stack.isEmpty()) {
                                DropKey key = new DropKey(stack);
                                dropMap.computeIfAbsent(key, k -> new DropAccumulator(stack)).addDrop(stack.getCount());
                            }
                        }

                        // Then, collect any items spawned directly during event handling
                        for (ItemStack stack : spawnedDuringEvent) {
                            if (!stack.isEmpty()) {
                                DropKey key = new DropKey(stack);
                                dropMap.computeIfAbsent(key, k -> new DropAccumulator(stack)).addDrop(stack.getCount());
                            }
                        }
                    }
                } catch (Exception e) {
                    // If we get here after the pre-test passed, something else is wrong
                    // Log once and abort
                    SuperMobTracker.LOGGER.warn("Error during drop simulation for " + entityId, e);
                    errorMessage = I18n.translateToLocal("gui.mobtracker.drops.simulationFailed");
                    completed = true;

                    return;
                }

                progress.incrementAndGet();
            }

            if (cancelled) return;

            // Build result
            List<DropEntry> entries = new ArrayList<>();
            for (DropAccumulator acc : dropMap.values()) entries.add(new DropEntry(acc.representativeStack, acc.totalCount, total));

            // Sort by drops per kill (descending)
            entries.sort((a, b) -> Double.compare(b.dropsPerKill, a.dropsPerKill));

            result = new DropSimulationResult(entityId, entries, total);
            completed = true;
        }
    }

    private static boolean isSpellBook(Item item) {
        isWizardryLoaded = Loader.isModLoaded("ebwizardry");
        if (!isWizardryLoaded) return false;

        if (!isSpellBookChecked) {
            try {
                spellBookClass = Class.forName("electroblob.wizardry.item.ItemSpellBook");
                isSpellBookChecked = true;
            } catch (ClassNotFoundException e) {
                isSpellBookChecked = true;
                SuperMobTracker.LOGGER.warn("Wizardry mod detected but ItemSpellBook class not found", e);
                return false;
            }
        }

        if (spellBookClass == null) return false;

        return spellBookClass.isInstance(item);
    }

    /**
     * Key for grouping drops - ignores durability but respects NBT otherwise.
     */
    private static class DropKey {
        private final int itemId;
        private final int metadata;
        private final NBTTagCompound nbt;

        DropKey(ItemStack stack) {
            this.itemId = Item.getIdFromItem(stack.getItem());
            // For items with durability or spell books, use 0 as metadata to group all
            Item item = stack.getItem();
            this.metadata = item.isDamageable() || isSpellBook(item) ? 0 : stack.getMetadata();

            // Copy NBT but remove durability and enchantment tags for grouping
            if (stack.hasTagCompound()) {
                NBTTagCompound tag = stack.getTagCompound().copy();
                // Remove durability/damage tags
                tag.removeTag("Damage");

                // Remove enchantment tags (items with different enchants should group together)
                tag.removeTag("ench");        // Regular enchantments
                tag.removeTag("StoredEnchantments"); // Enchanted books
                tag.removeTag("RepairCost");  // Anvil repair cost

                this.nbt = tag.isEmpty() ? null : tag;
            } else {
                this.nbt = null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DropKey)) return false;

            DropKey that = (DropKey) o;
            if (itemId != that.itemId) return false;
            if (metadata != that.metadata) return false;

            if (nbt == null) return that.nbt == null;

            return nbt.equals(that.nbt);
        }

        @Override
        public int hashCode() {
            int result = itemId;
            result = 31 * result + metadata;
            result = 31 * result + (nbt != null ? nbt.hashCode() : 0);

            return result;
        }
    }

    /**
     * Accumulates drop counts for a specific item type.
     */
    private static class DropAccumulator {
        final ItemStack representativeStack;
        int totalCount = 0;

        DropAccumulator(ItemStack stack) {
            // Create a clean representative stack (single item, no enchants/durability/spell book metadata, etc)
            this.representativeStack = stack.copy();
            this.representativeStack.setCount(1);

            Item item = this.representativeStack.getItem();
            if (item.isDamageable() || isSpellBook(item)) this.representativeStack.setItemDamage(0);

            // Strip enchantments and repair cost from representative stack
            if (this.representativeStack.hasTagCompound()) {
                NBTTagCompound tag = this.representativeStack.getTagCompound();
                tag.removeTag("ench");
                tag.removeTag("StoredEnchantments");
                tag.removeTag("RepairCost");
                tag.removeTag("Damage");
                if (tag.isEmpty()) this.representativeStack.setTagCompound(null);
            }
        }

        void addDrop(int count) {
            totalCount += count;
        }
    }

    /**
     * Represents a single type of drop with its statistics.
     */
    public static class DropEntry {
        public final ItemStack stack;
        public final int totalCount;
        public final int simulationCount;
        public final double dropsPerKill;

        DropEntry(ItemStack stack, int totalCount, int simulationCount) {
            this.stack = stack;
            this.totalCount = totalCount;
            this.simulationCount = simulationCount;
            this.dropsPerKill = simulationCount > 0 ? (double) totalCount / simulationCount : 0;
        }

        public String stripTrailing(String s, char c) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == c) end--;

            return s.substring(0, end);
        }

        public double round(double value, int decimals) {
            double scale = Math.pow(10, decimals);
            return Math.round(value * scale) / scale;
        }

        /**
         * Format the drops per kill for display.
         * - >= 100: "X" (no decimals)
         * - >= 10: "X.X" (one decimal)
         * - >= 1: "X.XX" (two decimals)
         * - < 1 and >= 0.001: "0.XXX" (three decimals)
         * - < 1: "0.XXXX" (four decimals)
         */
        public String formatDropsPerKill() {
            int decimals;
            if (dropsPerKill >= 100.0)      decimals = 0;
            else if (dropsPerKill >= 10.0)  decimals = 1;
            else if (dropsPerKill >= 1.0)   decimals = 2;
            else if (dropsPerKill > 0.001)  decimals = 3;
            else if (dropsPerKill > 0)      decimals = 4;
            else return "0";

            String s = String.format("%." + decimals + "f", round(dropsPerKill, decimals));
            if (s.contains(".")) s = stripTrailing(stripTrailing(s, '0'), '.');

            return s;
        }
    }

    /**
     * Complete result of a drop simulation.
     */
    public static class DropSimulationResult {
        public final ResourceLocation entityId;
        public final List<DropEntry> drops;
        public final int simulationCount;

        DropSimulationResult(ResourceLocation entityId, List<DropEntry> drops, int simulationCount) {
            this.entityId = entityId;
            this.drops = drops;
            this.simulationCount = simulationCount;
        }

        /**
         * Returns true if this result contains no drops.
         */
        public boolean hasNoDrops() {
            return drops == null || drops.isEmpty();
        }
    }

    /**
     * Result of a profiling operation for a single entity.
     * Tracks status (success, no drops, crashed, etc.) and timing information.
     */
    public static class ProfileResult {
        public enum Status {
            /** Successfully simulated drops and got results */
            SUCCESS,
            /** Successfully simulated but entity has no drops */
            NO_DROPS,
            /** Entity is not a valid living entity */
            INVALID_ENTITY,
            /** Multiplayer, but no server side - cannot simulate drops */
            SERVER_SIDE_ONLY,
            /** Failed to create simulation world */
            WORLD_CREATION_FAILED,
            /** Entity construction failed */
            ENTITY_CONSTRUCTION_FAILED,
            /** An exception occurred during simulation */
            CRASHED
        }

        public final ResourceLocation entityId;
        public final Status status;
        public final DropSimulationResult result;
        public final String error;
        public final long durationNanos;

        ProfileResult(ResourceLocation entityId, Status status, DropSimulationResult result, String error, long durationNanos) {
            this.entityId = entityId;
            this.status = status;
            this.result = result;
            this.error = error;
            this.durationNanos = durationNanos;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean hasDrops() {
            return result != null && !result.hasNoDrops();
        }
    }

    // Cached resources for batch profiling (reused across multiple profileEntity calls)
    private static DropSimulationWorld cachedProfileWorld = null;
    private static EntityPlayer cachedProfilePlayer = null;
    private static DamageSource cachedProfileDamage = null;
    private static int cachedProfileDimension = Integer.MIN_VALUE;
    private static int profileEntityCount = 0;

    // Recreate resources every N entities to prevent accumulation of state from mods
    private static final int PROFILE_CACHE_REFRESH_INTERVAL = 50;

    /**
     * Profile a single entity's drop simulation.
     * This is a synchronous operation that runs on the calling thread.
     * Used for batch analysis/profiling in CommandAnalyze.
     *
     * @param entityId The entity to profile
     * @param simulationCount Number of kills to simulate
     * @return ProfileResult with status and timing information
     */
    public static ProfileResult profileEntity(ResourceLocation entityId, int simulationCount) {
        // Mute spammy loggers during simulation
        LogMuter.muteLoggers();
        try {
            return profileEntityInternal(entityId, simulationCount);
        } finally {
            LogMuter.restoreLoggers();
        }
    }

    /**
     * Clear cached profiling resources.
     * Should be called after a batch of profileEntity calls is complete.
     */
    public static void clearProfileCache() {
        cachedProfileWorld = null;
        cachedProfilePlayer = null;
        cachedProfileDamage = null;
        cachedProfileDimension = Integer.MIN_VALUE;
        profileEntityCount = 0;
    }

    /**
     * Internal profiling logic.
     */
    private static ProfileResult profileEntityInternal(ResourceLocation entityId, int simulationCount) {
        long startTime = System.nanoTime();

        Method dropLoot = getDropLootMethod();
        if (dropLoot == null) {
            return new ProfileResult(entityId, ProfileResult.Status.CRASHED,
                null, "Could not access dropLoot method", System.nanoTime() - startTime);
        }

        Minecraft mc = Minecraft.getMinecraft();
        IntegratedServer integratedServer = mc.getIntegratedServer();
        if (integratedServer == null) {
            return new ProfileResult(entityId, ProfileResult.Status.SERVER_SIDE_ONLY,
                null, "gui.mobtracker.drops.serverSideOnly", System.nanoTime() - startTime);
        }

        int dimension = mc.world != null ? mc.world.provider.getDimension() : 0;
        WorldServer realWorld = integratedServer.getWorld(dimension);
        if (realWorld == null || realWorld.getLootTableManager() == null) {
            return new ProfileResult(entityId, ProfileResult.Status.SERVER_SIDE_ONLY,
                null, "gui.mobtracker.drops.serverSideOnly", System.nanoTime() - startTime);
        }

        EntityEntry entry = ForgeRegistries.ENTITIES.getValue(entityId);
        if (entry == null || !EntityLiving.class.isAssignableFrom(entry.getEntityClass())) {
            return new ProfileResult(entityId, ProfileResult.Status.INVALID_ENTITY,
                null, "Invalid entity", System.nanoTime() - startTime);
        }

        // Check if entity is unstable for simulation (corrupts global state)
        if (ModConfig.isUnstableSimulationEntity(entityId.toString())) {
            return new ProfileResult(entityId, ProfileResult.Status.INVALID_ENTITY,
                null, "gui.mobtracker.drops.unstableSimulation", System.nanoTime() - startTime);
        }

        // Reuse simulation world and fake player across batch calls for performance
        // Only recreate if dimension changed (different loot table manager) or forced refresh
        if (cachedProfileWorld == null || cachedProfileDimension != dimension) {
            try {
                cachedProfileWorld = DropSimulationWorld.createInstance(realWorld);
                cachedProfileDimension = dimension;
            } catch (Exception e) {
                SuperMobTracker.LOGGER.error("Failed to create simulation world for " + entityId, e);
                return new ProfileResult(entityId, ProfileResult.Status.WORLD_CREATION_FAILED,
                    null, "Failed to create simulation world: " + e.getMessage(), System.nanoTime() - startTime);
            }

            // Create a fake player for "killed_by_player" loot conditions
            // Use a fixed UUID to allow FakePlayerFactory to cache the player
            cachedProfilePlayer = FakePlayerFactory.get(
                realWorld,
                new GameProfile(FAKE_PLAYER_UUID, "[SuperMobTracker]")
            );

            // Set creative mode to bypass mod skill checks (e.g., AoA Hunter levels)
            if (cachedProfilePlayer != null && cachedProfilePlayer.capabilities != null) {
                cachedProfilePlayer.capabilities.isCreativeMode = true;
            }
            cachedProfileDamage = DamageSource.causePlayerDamage(cachedProfilePlayer);
        }

        DropSimulationWorld simWorld = cachedProfileWorld;
        EntityPlayer fakePlayer = cachedProfilePlayer;
        DamageSource playerDamage = cachedProfileDamage;
        Map<DropKey, DropAccumulator> dropMap = new HashMap<>();

        // Pre-test: Try to create one entity to check if it works
        Entity testEntity;
        try {
            testEntity = EntityList.createEntityByIDFromName(entry.getRegistryName(), simWorld);
            if (!(testEntity instanceof EntityLiving)) {
                return new ProfileResult(entityId, ProfileResult.Status.INVALID_ENTITY,
                    null, "Entity is not a living entity", System.nanoTime() - startTime);
            }
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn("Cannot simulate drops for " + entityId + " - entity construction failed", e);
            return new ProfileResult(entityId, ProfileResult.Status.ENTITY_CONSTRUCTION_FAILED,
                null, "Entity construction failed: " + e.getMessage(), System.nanoTime() - startTime);
        }

        long iterationStart = System.nanoTime();

        try {
            for (int i = 0; i < simulationCount; i++) {
                Entity rawEntity = EntityList.createEntityByIDFromName(entry.getRegistryName(), simWorld);
                if (!(rawEntity instanceof EntityLiving)) continue;

                EntityLiving entity = (EntityLiving) rawEntity;

                // Set attackingPlayer field for killed_by_player loot conditions
                Field attackingPlayerField = getAttackingPlayerField();
                if (attackingPlayerField != null) {
                    try {
                        attackingPlayerField.set(entity, fakePlayer);
                    } catch (IllegalAccessException e) {
                        SuperMobTracker.LOGGER.warn("Failed to set attackingPlayer field", e);
                    }
                }

                simWorld.clearDrops();
                dropLoot.invoke(entity, true, 0, playerDamage);

                // Collect the base drops from loot tables
                List<ItemStack> baseDrops = simWorld.collectAndClearDrops();

                // Convert ItemStacks to EntityItems for LivingDropsEvent
                List<EntityItem> entityItems = new ArrayList<>();
                for (ItemStack stack : baseDrops) {
                    if (!stack.isEmpty()) {
                        EntityItem entityItem = new EntityItem(simWorld, 0, 0, 0, stack.copy());
                        entityItems.add(entityItem);
                    }
                }

                // Fire LivingDropsEvent to let mods add/modify drops
                LivingDropsEvent event = new LivingDropsEvent(entity, playerDamage, entityItems, 0, true);
                MinecraftForge.EVENT_BUS.post(event);

                // Collect any items that mods spawned directly via entityDropItem()
                List<ItemStack> spawnedDuringEvent = simWorld.collectAndClearDrops();

                // Collect final drops (if event wasn't cancelled)
                if (!event.isCanceled()) {
                    // First, collect drops from the event's drop list
                    // Make a defensive copy to avoid ConcurrentModificationException
                    List<EntityItem> eventDrops = new ArrayList<>(event.getDrops());
                    for (EntityItem entityItem : eventDrops) {
                        ItemStack stack = entityItem.getItem();
                        if (!stack.isEmpty()) {
                            DropKey key = new DropKey(stack);
                            dropMap.computeIfAbsent(key, k -> new DropAccumulator(stack)).addDrop(stack.getCount());
                        }
                    }

                    // Then, collect any items spawned directly during event handling
                    for (ItemStack stack : spawnedDuringEvent) {
                        if (!stack.isEmpty()) {
                            DropKey key = new DropKey(stack);
                            dropMap.computeIfAbsent(key, k -> new DropAccumulator(stack)).addDrop(stack.getCount());
                        }
                    }
                }
            }
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn("Error during drop simulation for " + entityId, e);
            return new ProfileResult(entityId, ProfileResult.Status.CRASHED,
                null, e.getClass().getSimpleName() + ": " + e.getMessage(), System.nanoTime() - startTime);
        }



        // Build result
        List<DropEntry> entries = new ArrayList<>();
        for (DropAccumulator acc : dropMap.values()) entries.add(new DropEntry(acc.representativeStack, acc.totalCount, simulationCount));

        entries.sort((a, b) -> Double.compare(b.dropsPerKill, a.dropsPerKill));

        DropSimulationResult simResult = new DropSimulationResult(entityId, entries, simulationCount);
        long duration = System.nanoTime() - startTime;

        if (entries.isEmpty()) return new ProfileResult(entityId, ProfileResult.Status.NO_DROPS, simResult, null, duration);

        return new ProfileResult(entityId, ProfileResult.Status.SUCCESS, simResult, null, duration);
    }
}
