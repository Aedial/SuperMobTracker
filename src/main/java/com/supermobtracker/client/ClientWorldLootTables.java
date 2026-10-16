package com.supermobtracker.client;

import java.lang.reflect.Field;
import java.util.function.Supplier;

import net.minecraft.world.World;
import net.minecraft.world.storage.loot.LootTableManager;

import com.supermobtracker.util.ReflectionUtils;


/**
 * Shared loot tables used while client-world entities run operations.
 */
public final class ClientWorldLootTables {
    private static final LootTableManager LOOT_TABLE_MANAGER = new LazyLootTableManager();
    private static final Field LOOT_TABLE_FIELD = ReflectionUtils.getDeclaredField(
        World.class, "field_184151_B", "lootTable");

    private ClientWorldLootTables() {}

    public static LootTableManager getLootTableManager() {
        return LOOT_TABLE_MANAGER;
    }

    /**
     * Runs the operation with a temporary manager when the world has none.
     */
    public static <T> T withLootTableManager(World world, Supplier<T> operation) {
        LootTableManager original = world.getLootTableManager();
        if (original != null) return operation.get();

        setLootTableManager(world, LOOT_TABLE_MANAGER);
        try {
            return operation.get();
        } finally {
            setLootTableManager(world, original);
        }
    }

    private static void setLootTableManager(World world, LootTableManager manager) {
        if (LOOT_TABLE_FIELD == null) {
            throw new IllegalStateException("Could not find World loot table field");
        }

        try {
            LOOT_TABLE_FIELD.set(world, manager);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set World loot table manager", e);
        }
    }

    private static class LazyLootTableManager extends LootTableManager {
        LazyLootTableManager() {
            super(null);
        }

        @Override
        public void reloadLootTables() {
            // Resolve tables only when an entity requests them
        }
    }
}
