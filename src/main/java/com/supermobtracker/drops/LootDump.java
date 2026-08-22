package com.supermobtracker.drops;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.Deflater;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import net.minecraft.entity.EntityLiving;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.drops.DropSimulator.DropEntry;
import com.supermobtracker.drops.DropSimulator.DropSimulationResult;


/**
 * Compact, ZIP-compressed file handler for the results of a full mob-loot simulation.
 * <p>
 * The format uses short array fields for size efficiency:
 * <ul>
 *   <li> {@code v}: format version </li>
 *   <li> {@code s}: simulation count </li>
 *   <li> {@code i}: de-duplicated item dictionary </li>
 *   <li> {@code m}: mob records </li>
 * </ul>
 * <p>
 * Each mob record is {@code [entityId, [[itemIndex, count], ...]]}.
 * Dictionary entries are {@code [itemId]}, {@code [itemId, metadata]}, or
 * {@code [itemId, metadata, nbt]}.
 */
public final class LootDump {
    public static final String FILE_NAME = "mob_loot.zip";
    private static final String JSON_ENTRY_NAME = "mob_loot.json";
    private static final int FORMAT_VERSION = 1;

    private static final int ITEM_ENTRY_SIZE = 3;
    private static final int ID_INDEX = 0;
    private static final int METADATA_INDEX = 1;
    private static final int NBT_INDEX = 2;

    private static final int MOB_ENTRY_SIZE = 2;
    private static final int DROPS_INDEX = 1;

    private static final int DROPS_ENTRY_SIZE = 2;
    private static final int DROPS_ITEM_INDEX = 0;
    private static final int DROPS_COUNT_INDEX = 1;

    private static List<MobLoot> cachedMobs = Collections.emptyList();
    private static Map<ResourceLocation, MobLoot> cachedById = Collections.emptyMap();
    private static Map<DumpItemKey, List<MobLoot>> cachedByItem = Collections.emptyMap();
    private static boolean cacheInitialized = false;

    private LootDump() {}

    /**
     * Gets the support-file location shared by the dump command and JEI category.
     */
    public static File getFile() {
        return new File(ModConfig.getSupportDirectory(), FILE_NAME);
    }

    /**
     * Writes all successfully simulated mobs as minified JSON inside one ZIP file.
     */
    public static DumpWriteResult write(Map<ResourceLocation, DropSimulationResult> results, int simulationCount)
            throws IOException {
        List<ResourceLocation> entityIds = new ArrayList<>(results.keySet());
        entityIds.sort(Comparator.comparing(ResourceLocation::toString));

        Map<DumpItemKey, Integer> itemIndexes = new LinkedHashMap<>();
        List<DumpItem> items = new ArrayList<>();
        List<SerializedMob> mobs = new ArrayList<>();
        int dropTypeCount = 0;

        for (ResourceLocation entityId : entityIds) {
            DropSimulationResult result = results.get(entityId);
            if (result == null || result.drops == null || result.drops.isEmpty()) continue;

            List<SerializedDrop> drops = new ArrayList<>();
            for (DropEntry entry : result.drops) {
                if (entry == null || entry.stack == null || entry.stack.isEmpty() || entry.totalCount <= 0) continue;

                DumpItem dumpItem = DumpItem.from(entry.stack);
                if (dumpItem == null) continue;

                DumpItemKey key = DumpItemKey.from(entry.stack);
                if (key == null) continue;

                Integer itemIndex = itemIndexes.get(key);
                if (itemIndex == null) {
                    itemIndex = items.size();
                    itemIndexes.put(key, itemIndex);
                    items.add(dumpItem);
                }

                drops.add(new SerializedDrop(itemIndex, entry.totalCount));
            }

            if (drops.isEmpty()) continue;
            mobs.add(new SerializedMob(entityId, drops));
            dropTypeCount += drops.size();
        }

        File output = getFile();
        File directory = output.getParentFile();
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create loot dump directory " + directory.getAbsolutePath());
        }

        File temporary = new File(directory, FILE_NAME + ".tmp");
        try (ZipOutputStream archive = new ZipOutputStream(new FileOutputStream(temporary))) {
            archive.setLevel(Deflater.BEST_COMPRESSION);
            archive.putNextEntry(new ZipEntry(JSON_ENTRY_NAME));
            JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(archive, StandardCharsets.UTF_8)));
            writer.beginObject();
            writer.name("v").value(FORMAT_VERSION);
            writer.name("s").value(simulationCount);
            writer.name("i");

            writer.beginArray();
            for (DumpItem item : items) writeItem(writer, item);
            writer.endArray();

            writer.name("m");

            writer.beginArray();
            for (SerializedMob mob : mobs) writeMob(writer, mob);
            writer.endArray();

            writer.endObject();
            writer.flush();
            archive.closeEntry();
        }

        try {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        invalidate();
        return new DumpWriteResult(output, mobs.size(), items.size(), dropTypeCount);
    }

    /**
     * Returns every valid mob records from the dump.
     */
    public static List<MobLoot> getMobs() {
        reloadIfNeeded();
        return cachedMobs;
    }

    /**
     * Returns a single valid mob record, or {@code null} when it is absent from the dump.
     */
    @Nullable
    public static MobLoot getMob(ResourceLocation entityId) {
        if (entityId == null) return null;

        reloadIfNeeded();
        return cachedById.get(entityId);
    }

    /**
     * Finds dump records which contain the exact item and NBT variant supplied by JEI.
     * The reverse lookup is built once as part of loading the dump.
     */
    public static List<MobLoot> getMobsForItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Collections.emptyList();

        reloadIfNeeded();
        DumpItemKey key = DumpItemKey.from(stack);
        if (key == null) return Collections.emptyList();
        List<MobLoot> matches = cachedByItem.get(key);
        return matches != null ? matches : Collections.emptyList();
    }

    /**
     * Forces the next lookup to read a newly written dump. It is not expected
     * to be called in normal operation, as it is a fairly expensive operation.
     */
    public static void invalidate() {
        cacheInitialized = false;
    }

    private static void reloadIfNeeded() {
        if (cacheInitialized) return;

        cacheInitialized = true;
        cachedMobs = Collections.emptyList();
        cachedById = Collections.emptyMap();
        cachedByItem = Collections.emptyMap();
        File file = getFile();
        if (!file.exists()) return;

        long startTime = System.currentTimeMillis();

        try (ZipInputStream archive = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry archiveEntry;
            do {
                archiveEntry = archive.getNextEntry();
            } while (archiveEntry != null && !JSON_ENTRY_NAME.equals(archiveEntry.getName()));
            if (archiveEntry == null) return;

            BufferedReader reader = new BufferedReader(new InputStreamReader(archive, StandardCharsets.UTF_8));
            JsonElement rootElement = new JsonParser().parse(reader);
            if (rootElement == null || !rootElement.isJsonObject()) return;

            JsonObject root = rootElement.getAsJsonObject();
            if (getInt(root.get("v"), -1) != FORMAT_VERSION) return;
            int simulationCount = getInt(root.get("s"), 0);
            JsonArray itemArray = getArray(root.get("i"));
            JsonArray mobArray = getArray(root.get("m"));
            if (simulationCount <= 0 || itemArray == null || mobArray == null) return;

            List<ItemStack> items = new ArrayList<>(itemArray.size());
            for (JsonElement rawItem : itemArray) items.add(readItem(getArray(rawItem)));

            List<MobLoot> mobs = new ArrayList<>();
            Map<ResourceLocation, MobLoot> byId = new LinkedHashMap<>();
            Map<DumpItemKey, List<MobLoot>> byItem = new LinkedHashMap<>();
            for (JsonElement rawMob : mobArray) {
                MobLoot mob = readMob(getArray(rawMob), items, simulationCount);
                if (mob == null || byId.containsKey(mob.entityId)) continue;

                mobs.add(mob);
                byId.put(mob.entityId, mob);
                for (LootEntry drop : mob.drops) {
                    DumpItemKey key = DumpItemKey.from(drop.stack);
                    if (key == null) continue;
                    List<MobLoot> itemMobs = byItem.get(key);
                    if (itemMobs == null) {
                        itemMobs = new ArrayList<>();
                        byItem.put(key, itemMobs);
                    }
                    itemMobs.add(mob);
                }
            }

            cachedMobs = Collections.unmodifiableList(mobs);
            cachedById = Collections.unmodifiableMap(byId);
            Map<DumpItemKey, List<MobLoot>> immutableByItem = new LinkedHashMap<>();
            for (Map.Entry<DumpItemKey, List<MobLoot>> entry : byItem.entrySet()) {
                immutableByItem.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }
            cachedByItem = Collections.unmodifiableMap(immutableByItem);

            long elapsed = System.currentTimeMillis() - startTime;
            SuperMobTracker.LOGGER.info("Loaded {} mob loot records from {} in {} ms", mobs.size(), file, elapsed);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn("Ignoring unreadable mob loot dump {}", file.getAbsolutePath(), e);
        }
    }

    @Nullable
    private static MobLoot readMob(@Nullable JsonArray rawMob, List<ItemStack> items, int simulationCount) {
        if (rawMob == null || rawMob.size() != MOB_ENTRY_SIZE || !rawMob.get(ID_INDEX).isJsonPrimitive()) return null;

        ResourceLocation entityId;
        try {
            entityId = new ResourceLocation(rawMob.get(ID_INDEX).getAsString());
        } catch (Exception ignored) {
            return null;
        }

        EntityEntry entityEntry = ForgeRegistries.ENTITIES.getValue(entityId);
        if (entityEntry == null || !EntityLiving.class.isAssignableFrom(entityEntry.getEntityClass())) return null;

        JsonArray rawDrops = getArray(rawMob.get(DROPS_INDEX));
        if (rawDrops == null) return null;

        List<LootEntry> drops = new ArrayList<>();
        for (JsonElement rawDrop : rawDrops) {
            JsonArray drop = getArray(rawDrop);
            if (drop == null || drop.size() != DROPS_ENTRY_SIZE) continue;

            int itemIndex = getInt(drop.get(DROPS_ITEM_INDEX), -1);
            int totalCount = getInt(drop.get(DROPS_COUNT_INDEX), 0);
            if (itemIndex < 0 || itemIndex >= items.size() || totalCount <= 0) continue;

            ItemStack stack = items.get(itemIndex);
            if (stack.isEmpty()) continue;

            drops.add(new LootEntry(stack.copy(), totalCount, simulationCount));
        }

        if (drops.isEmpty()) return null;
        return new MobLoot(entityId, drops);
    }

    private static ItemStack readItem(@Nullable JsonArray rawItem) {
        if (rawItem == null || rawItem.size() < 1 || rawItem.size() > ITEM_ENTRY_SIZE
                || !rawItem.get(ID_INDEX).isJsonPrimitive()) {
            return ItemStack.EMPTY;
        }

        try {
            ResourceLocation itemId = new ResourceLocation(rawItem.get(ID_INDEX).getAsString());
            Item item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) return ItemStack.EMPTY;

            int metadata = rawItem.size() > METADATA_INDEX ? getInt(rawItem.get(METADATA_INDEX), 0) : 0;
            ItemStack stack = new ItemStack(item, 1, metadata);

            if (rawItem.size() > NBT_INDEX && rawItem.get(NBT_INDEX).isJsonPrimitive()) {
                NBTTagCompound tag = JsonToNBT.getTagFromJson(rawItem.get(NBT_INDEX).getAsString());
                stack.setTagCompound(tag);
            }

            return stack;
        } catch (Exception ignored) {
            // Missing items and invalid NBT are ignored so a dump can survive mod changes
            return ItemStack.EMPTY;
        }
    }

    private static void writeItem(JsonWriter writer, DumpItem item) throws IOException {
        writer.beginArray();
        writer.value(item.itemId.toString());
        if (item.metadata != 0 || item.nbt != null) writer.value(item.metadata);
        if (item.nbt != null) writer.value(item.nbt);
        writer.endArray();
    }

    private static void writeMob(JsonWriter writer, SerializedMob mob) throws IOException {
        // [entityId, [[itemIndex, totalCount], ...]]
        writer.beginArray();
        writer.value(mob.entityId.toString());

        writer.beginArray();
        for (SerializedDrop drop : mob.drops) {
            writer.beginArray();
            writer.value(drop.itemIndex);
            writer.value(drop.totalCount);
            writer.endArray();
        }
        writer.endArray();

        writer.endArray();
    }

    @Nullable
    private static JsonArray getArray(@Nullable JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static int getInt(@Nullable JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * A single mob record for the JEI recipe wrapper.
     */
    public static final class MobLoot {
        public final ResourceLocation entityId;
        public final List<LootEntry> drops;

        private MobLoot(ResourceLocation entityId, List<LootEntry> drops) {
            this.entityId = entityId;
            this.drops = Collections.unmodifiableList(drops);
        }
    }

    /**
     * An aggregated item drop, retaining its exact simulation chance.
     */
    public static final class LootEntry {
        public final ItemStack stack;
        public final int totalCount;
        public final int simulationCount;

        private LootEntry(ItemStack stack, int totalCount, int simulationCount) {
            this.stack = stack;
            this.totalCount = totalCount;
            this.simulationCount = simulationCount;
        }

        public double getPercent() {
            return simulationCount > 0 ? totalCount * 100.0D / simulationCount : 0.0D;
        }
    }

    /**
     * Summary of a dump write, used for the command feedback.
     */
    public static final class DumpWriteResult {
        public final File file;
        public final int mobCount;
        public final int uniqueItemCount;
        public final int dropTypeCount;

        private DumpWriteResult(File file, int mobCount, int uniqueItemCount, int dropTypeCount) {
            this.file = file;
            this.mobCount = mobCount;
            this.uniqueItemCount = uniqueItemCount;
            this.dropTypeCount = dropTypeCount;
        }
    }

    /**
     * A single mob record in the JSON dump, with de-duplicated item indexes.
     */
    private static final class SerializedMob {
        private final ResourceLocation entityId;
        private final List<SerializedDrop> drops;

        private SerializedMob(ResourceLocation entityId, List<SerializedDrop> drops) {
            this.entityId = entityId;
            this.drops = drops;
        }
    }

    /**
     * A single item drop in the JSON dump, pointing to a de-duplicated item dictionary entry.
     */
    private static final class SerializedDrop {
        private final int itemIndex;
        private final int totalCount;

        private SerializedDrop(int itemIndex, int totalCount) {
            this.itemIndex = itemIndex;
            this.totalCount = totalCount;
        }
    }

    /**
     * A de-duplicated item dictionary entry in the JSON dump.
     */
    private static final class DumpItem {
        private final ResourceLocation itemId;
        private final int metadata;
        @Nullable
        private final String nbt;

        private DumpItem(ResourceLocation itemId, int metadata, @Nullable String nbt) {
            this.itemId = itemId;
            this.metadata = metadata;
            this.nbt = nbt;
        }

        @Nullable
        private static DumpItem from(ItemStack stack) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) return null;

            NBTTagCompound normalizedTag = normalizeLookupTag(stack.getTagCompound());
            String nbt = normalizedTag != null ? normalizedTag.toString() : null;
            return new DumpItem(itemId, normalizeLookupMetadata(stack), nbt);
        }
    }

    private static final class DumpItemKey {
        private final ResourceLocation itemId;
        private final int metadata;
        @Nullable
        private final NBTTagCompound nbt;
        private final int hashCode;

        private DumpItemKey(ResourceLocation itemId, int metadata, @Nullable NBTTagCompound nbt) {
            this.itemId = itemId;
            this.metadata = metadata;
            this.nbt = nbt;
            this.hashCode = computeHashCode();
        }

        @Nullable
        private static DumpItemKey from(ItemStack stack) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) return null;

            return new DumpItemKey(itemId, normalizeLookupMetadata(stack), normalizeLookupTag(stack.getTagCompound()));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DumpItemKey)) return false;

            DumpItemKey that = (DumpItemKey) other;
            if (metadata != that.metadata || !itemId.equals(that.itemId)) return false;
            return nbt == null ? that.nbt == null : nbt.equals(that.nbt);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        private int computeHashCode() {
            int result = itemId.hashCode();
            result = 31 * result + metadata;
            result = 31 * result + (nbt != null ? nbt.hashCode() : 0);
            return result;
        }
    }

    private static int normalizeLookupMetadata(ItemStack stack) {
        return stack.getItem().isDamageable() ? 0 : stack.getMetadata();
    }

    @Nullable
    private static NBTTagCompound normalizeLookupTag(@Nullable NBTTagCompound tag) {
        if (tag == null || tag.isEmpty()) return null;

        NBTTagCompound normalizedTag = tag.copy();
        normalizedTag.removeTag("Damage");
        normalizedTag.removeTag("ench");
        normalizedTag.removeTag("StoredEnchantments");
        normalizedTag.removeTag("RepairCost");
        normalizedTag.removeTag("HideFlags");
        return normalizedTag.isEmpty() ? null : normalizedTag;
    }
}
