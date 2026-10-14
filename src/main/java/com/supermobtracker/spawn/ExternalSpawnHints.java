package com.supermobtracker.spawn;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;


/**
 * Loads externally defined spawn hints that replace selected spawn conditions (if any).
 */
public final class ExternalSpawnHints {

    private static final Gson GSON = new Gson();
    private static final String FILE_NAME = "spawn_hints.json";
    private static final String DEFAULT_RESOURCE_PATH = "assets/supermobtracker/spawn_hints.defaults.json";
    private static final int MIN_LIGHT_LEVEL = 0;
    private static final int MAX_LIGHT_LEVEL = 15;
    private static final int MIN_TIME_OF_DAY = 0;
    private static final int MAX_TIME_OF_DAY = 23999;
    private static final Map<String, BiomeDictionary.Type> BIOME_TYPES_BY_NAME = buildBiomeTypeMap();
    private static final Set<String> VALID_WEATHERS = new LinkedHashSet<>(Arrays.asList("clear", "rain", "thunder"));
    private static final Pattern TRANSLATION_KEY_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    private static Map<ResourceLocation, HintEntry> cachedEntries = Collections.emptyMap();
    private static long lastLoadedTimestamp = Long.MIN_VALUE;
    private static boolean cacheInitialized = false;

    private ExternalSpawnHints() {}

    @Nullable
    public static SpawnConditionAnalyzer.SpawnConditions applySpawnHints(ResourceLocation entityId,
            @Nullable SpawnConditionAnalyzer.SpawnConditions spawnConditions,
            boolean aquatic, boolean flying) {
        reloadIfNeeded();

        HintEntry entry = cachedEntries.get(entityId);
        if (entry == null) return spawnConditions;

        if (spawnConditions == null || spawnConditions.analysisFailed) {
            return entry.toSpawnConditions(aquatic, flying);
        }

        return entry.applyTo(spawnConditions, aquatic, flying);
    }

    private static void reloadIfNeeded() {
        File file = getHintsFile();
        long lastModified = file.exists() ? file.lastModified() : -1L;

        if (cacheInitialized && lastModified == lastLoadedTimestamp) return;

        cacheInitialized = true;
        lastLoadedTimestamp = lastModified;
        cachedEntries = loadEntries(file);
    }

    private static File getHintsFile() {
        return new File(ModConfig.getSupportDirectory(), FILE_NAME);
    }

    private static Map<ResourceLocation, HintEntry> loadEntries(File file) {
        Map<ResourceLocation, HintEntry> entries = loadBundledEntries();
        if (!file.exists()) return entries;

        try (Reader reader = new FileReader(file)) {
            loadEntriesFromReader(reader, file.getAbsolutePath(), entries);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.error(
                "Failed to load external spawn hints from {}", file.getAbsolutePath(), e);
        }

        return entries;
    }

    private static Map<ResourceLocation, HintEntry> loadBundledEntries() {
        Map<ResourceLocation, HintEntry> entries = new LinkedHashMap<>();

        try (InputStream stream = ExternalSpawnHints.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (stream == null) return entries;

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                loadEntriesFromReader(reader, DEFAULT_RESOURCE_PATH, entries);
            }
        } catch (Exception e) {
            SuperMobTracker.LOGGER.error(
                "Failed to load bundled spawn hints from {}", DEFAULT_RESOURCE_PATH, e);
        }

        return entries;
    }

    private static void loadEntriesFromReader(Reader reader,
                                              String sourceName,
                                              Map<ResourceLocation, HintEntry> entries) {
        JsonElement rootElement = GSON.fromJson(reader, JsonElement.class);
        if (rootElement == null || !rootElement.isJsonObject()) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hints source {} because it does not contain a JSON object.", sourceName);
            return;
        }

        JsonArray rawEntries = getArray(rootElement.getAsJsonObject(), "entries");
        if (rawEntries == null) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hints source {} because it does not contain an 'entries' array.", sourceName);
            return;
        }

        List<RawHintEntry> sourceEntryList = new ArrayList<>();
        Map<ResourceLocation, RawHintEntry> sourceEntries = new LinkedHashMap<>();
        Map<ResourceLocation, HintEntry> resolvedEntries = new LinkedHashMap<>();

        for (int i = 0; i < rawEntries.size(); i++) {
            JsonElement rawEntry = rawEntries.get(i);
            if (!rawEntry.isJsonObject()) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring spawn hint entry {} in {} because it is not an object.", i, sourceName);
                continue;
            }

            JsonObject entryObject = rawEntry.getAsJsonObject();
            ResourceLocation entityId = parseEntryId(entryObject, i, sourceName);
            if (entityId == null || !isNamespaceLoaded(entityId.getNamespace())) continue;

            RawHintEntry sourceEntry = new RawHintEntry(entityId, entryObject, i);
            sourceEntryList.add(sourceEntry);
            sourceEntries.put(entityId, sourceEntry);
        }

        for (RawHintEntry sourceEntry : sourceEntryList) {
            HintEntry entry = parseEntry(sourceEntry, sourceName, entries, sourceEntries, resolvedEntries, new LinkedHashSet<>());
            if (entry == null) continue;

            entries.put(entry.entityId, entry);
        }
    }

    @Nullable
    private static ResourceLocation parseEntryId(JsonObject object, int index, String sourceName) {
        String entityIdText = getString(object, "entityId");
        if (entityIdText == null || entityIdText.trim().isEmpty()) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hint entry {} in {} because 'entityId' is missing.", index, sourceName);
            return null;
        }

        ResourceLocation entityId;
        try {
            entityId = new ResourceLocation(entityIdText);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hint entry {} in {} because '{}' is not a valid entity ID.",
                index, sourceName, entityIdText);
            return null;
        }

        return entityId;
    }

    @Nullable
    private static HintEntry parseEntry(RawHintEntry rawEntry,
                                        String sourceName,
                                        Map<ResourceLocation, HintEntry> loadedEntries,
                                        Map<ResourceLocation, RawHintEntry> sourceEntries,
                                        Map<ResourceLocation, HintEntry> resolvedEntries,
                                        Set<ResourceLocation> resolutionStack) {
        HintEntry cachedEntry = resolvedEntries.get(rawEntry.entityId);
        if (cachedEntry != null) return cachedEntry;

        if (!resolutionStack.add(rawEntry.entityId)) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hint entry {} in {} because it has a circular parent chain.",
                rawEntry.index,
                sourceName);
            return null;
        }

        JsonObject object = rawEntry.object;
        String parentText = getString(object, "parent");
        String spawnReason = normalizeSpawnReason(getString(object, "spawnReason"));
        HintEntry entry;

        if (parentText != null && !parentText.trim().isEmpty()) {
            ResourceLocation parentId = parseResourceLocation(parentText, "parent entity", rawEntry.index, sourceName);
            if (parentId == null || !isNamespaceLoaded(parentId.getNamespace())) {
                resolutionStack.remove(rawEntry.entityId);
                return null;
            }

            HintEntry parentEntry = resolveParentEntry(parentId, sourceName, loadedEntries, sourceEntries, resolvedEntries, resolutionStack);
            if (parentEntry == null) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring spawn hint entry {} in {} because parent '{}' could not be resolved.",
                    rawEntry.index,
                    sourceName,
                    parentText);
                resolutionStack.remove(rawEntry.entityId);
                return null;
            }

            entry = new HintEntry(parentEntry, rawEntry.entityId, spawnReason);
        } else {
            if (spawnReason == null) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring spawn hint entry {} in {} because neither 'spawnReason' nor 'parent' is present.",
                    rawEntry.index,
                    sourceName);
                resolutionStack.remove(rawEntry.entityId);
                return null;
            }

            entry = new HintEntry(rawEntry.entityId, spawnReason);
        }

        applyEntryData(entry, object, rawEntry.index, sourceName);
        resolvedEntries.put(entry.entityId, entry);
        resolutionStack.remove(rawEntry.entityId);

        return entry;
    }

    @Nullable
    private static HintEntry resolveParentEntry(ResourceLocation parentId,
                                                String sourceName,
                                                Map<ResourceLocation, HintEntry> loadedEntries,
                                                Map<ResourceLocation, RawHintEntry> sourceEntries,
                                                Map<ResourceLocation, HintEntry> resolvedEntries,
                                                Set<ResourceLocation> resolutionStack) {
        HintEntry cachedEntry = resolvedEntries.get(parentId);
        if (cachedEntry != null) return cachedEntry;

        RawHintEntry sourceEntry = sourceEntries.get(parentId);
        if (sourceEntry != null) {
            return parseEntry(sourceEntry, sourceName, loadedEntries, sourceEntries, resolvedEntries, resolutionStack);
        }

        return loadedEntries.get(parentId);
    }

    private static void applyEntryData(HintEntry entry, JsonObject object, int index, String sourceName) {
        JsonObject biomeObject = getObject(object, "biomes");
        if (object.has("biomes")) {
            entry.providedFields.add(HintField.BIOMES);
            entry.biomeIds.clear();
            entry.biomeTypes.clear();
            entry.requiredBiomeTypes.clear();
        }

        if (biomeObject != null) {
            for (String biomeIdText : getStringList(biomeObject, "ids")) {
                ResourceLocation biomeId;

                try {
                    biomeId = new ResourceLocation(biomeIdText);
                } catch (Exception e) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring invalid biome ID '{}' in spawn hint entry {} from {}.",
                        biomeIdText, index, sourceName);
                    continue;
                }

                if (!isNamespaceLoaded(biomeId.getNamespace())) continue;

                Biome biome = ForgeRegistries.BIOMES.getValue(biomeId);
                if (biome == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown biome ID '{}' in spawn hint entry {} from {}.",
                        biomeIdText, index, sourceName);
                    continue;
                }

                entry.biomeIds.add(biome.getRegistryName().toString());
            }

            for (String biomeTypeText : getStringList(biomeObject, "types")) {
                BiomeDictionary.Type biomeType = BIOME_TYPES_BY_NAME.get(biomeTypeText.trim().toUpperCase(Locale.ROOT));
                if (biomeType == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown biome type '{}' in spawn hint entry {} from {}.",
                        biomeTypeText, index, sourceName);
                    continue;
                }

                entry.biomeTypes.add(biomeType);
            }

            for (String biomeTypeText : getStringList(biomeObject, "allTypes")) {
                BiomeDictionary.Type biomeType = BIOME_TYPES_BY_NAME.get(biomeTypeText.trim().toUpperCase(Locale.ROOT));
                if (biomeType == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown required biome type '{}' in spawn hint entry {} from {}.",
                        biomeTypeText, index, sourceName);
                    continue;
                }

                entry.requiredBiomeTypes.add(biomeType);
            }
        }

        if (object.has("dimensionId")) {
            entry.providedFields.add(HintField.DIMENSION_ID);
            entry.dimensionId = getInteger(object, "dimensionId");
        }

        if (object.has("dimensionName")) {
            String dimensionName = getString(object, "dimensionName");
            if (dimensionName != null && !isTranslationKey(dimensionName)) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring non-localized dimensionName '{}' in spawn hint entry {} from {}.",
                    dimensionName,
                    index,
                    sourceName);
            } else {
                entry.providedFields.add(HintField.DIMENSION_NAME);
                entry.dimensionName = dimensionName;
            }
        }

        if (object.has("groundBlocks")) {
            entry.providedFields.add(HintField.GROUND_BLOCKS);
            entry.groundBlocks.clear();
            entry.groundBlocks.addAll(getStringList(object, "groundBlocks"));
        }

        if (object.has("lightLevels")) {
            entry.providedFields.add(HintField.LIGHT_LEVELS);
            IntRange lightLevels = parseRange(object, "lightLevels", MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL, index, sourceName);
            entry.lightMin = lightLevels != null ? lightLevels.min : null;
            entry.lightMax = lightLevels != null ? lightLevels.max : null;
        }

        if (object.has("yLevels")) {
            entry.providedFields.add(HintField.Y_LEVELS);
            IntRange yLevels = parseRange(object, "yLevels");
            entry.yMin = yLevels != null ? yLevels.min : null;
            entry.yMax = yLevels != null ? yLevels.max : null;
        }

        if (object.has("timeOfDay")) {
            entry.providedFields.add(HintField.TIME_OF_DAY);
            entry.timeOfDay.clear();
            entry.timeOfDay.addAll(parseTimeRanges(object, "timeOfDay", index, sourceName));
        }

        if (object.has("weather")) {
            entry.providedFields.add(HintField.WEATHER);
            entry.weather.clear();
            entry.weather.addAll(parseWeatherList(object, "weather", index, sourceName));
        }

        if (object.has("requiresSky")) {
            entry.providedFields.add(HintField.REQUIRES_SKY);
            entry.requiresSky = getBoolean(object, "requiresSky");
        }

        if (object.has("moonPhases")) {
            entry.providedFields.add(HintField.MOON_PHASES);
            entry.moonPhases.clear();
            entry.moonPhases.addAll(getIntegerList(object, "moonPhases"));
        }

        if (object.has("requiresSlimeChunk")) {
            entry.providedFields.add(HintField.REQUIRES_SLIME_CHUNK);
            entry.requiresSlimeChunk = getBoolean(object, "requiresSlimeChunk");
        }

        if (object.has("requiresNether")) {
            entry.providedFields.add(HintField.REQUIRES_NETHER);
            entry.requiresNether = getBoolean(object, "requiresNether");
        }

        if (object.has("summon")) {
            entry.providedFields.add(HintField.SUMMON);
            entry.summon = parseSummonInfo(object, index, sourceName);
        }

        if (object.has("hints")) {
            entry.providedFields.add(HintField.HINTS);
            entry.hints.clear();

            for (String hintKey : getStringList(object, "hints")) {
                if (!isTranslationKey(hintKey)) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring non-localized hint '{}' in spawn hint entry {} from {}.",
                        hintKey,
                        index,
                        sourceName);
                    continue;
                }

                entry.hints.add(hintKey);
            }
        }
    }

    private static String normalizeSpawnReason(String spawnReason) {
        if (spawnReason == null) return null;

        String normalized = spawnReason.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean isTranslationKey(String value) {
        return value != null && TRANSLATION_KEY_PATTERN.matcher(value).matches();
    }

    private static boolean isNamespaceLoaded(String namespace) {
        if (namespace == null || namespace.isEmpty()) return true;
        if ("minecraft".equals(namespace) || "forge".equals(namespace)) return true;

        return Loader.isModLoaded(namespace);
    }

    @Nullable
    private static SpawnConditionAnalyzer.SummonInfo parseSummonInfo(JsonObject entryObject,
                                                                       int index,
                                                                       String sourceName) {
        JsonObject summonObject = getObject(entryObject, "summon");
        if (summonObject == null) return null;

        List<SpawnConditionAnalyzer.SummonItem> items = new ArrayList<>();
        JsonArray itemArray = getArray(summonObject, "items");
        if (itemArray != null) {
            for (int itemIndex = 0; itemIndex < itemArray.size(); itemIndex++) {
                JsonElement rawItem = itemArray.get(itemIndex);
                if (!rawItem.isJsonObject()) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring non-object summon item {} in spawn hint entry {} from {}.",
                        itemIndex, index, sourceName);
                    continue;
                }

                JsonObject itemObject = rawItem.getAsJsonObject();
                ResourceLocation itemId = parseResourceLocation(getString(itemObject, "id"), "summon item", index, sourceName);
                if (itemId == null || !isNamespaceLoaded(itemId.getNamespace())) continue;

                Item item = ForgeRegistries.ITEMS.getValue(itemId);
                if (item == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown summon item '{}' in spawn hint entry {} from {}.",
                        itemId, index, sourceName);
                    continue;
                }

                Integer metadata = getInteger(itemObject, "metadata");
                Integer count = getInteger(itemObject, "count");
                int resolvedMetadata = metadata == null ? 0 : metadata;
                int resolvedCount = count == null ? 1 : count;
                if (resolvedMetadata < 0 || resolvedCount < 1) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring summon item '{}' in spawn hint entry {} from {} because metadata must be non-negative and count must be positive.",
                        itemId, index, sourceName);
                    continue;
                }

                items.add(new SpawnConditionAnalyzer.SummonItem(itemId.toString(), resolvedMetadata, resolvedCount));
            }
        }

        if (items.isEmpty()) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring summon metadata in spawn hint entry {} from {} because it has no valid items.", index, sourceName);
            return null;
        }

        String onBlock = parseKnownBlockId(getString(summonObject, "onBlock"), index, sourceName);
        String onEntity = parseKnownEntityId(getString(summonObject, "onEntity"), index, sourceName);
        if (onBlock != null && onEntity != null) {
            SuperMobTracker.LOGGER.warn(
                "Spawn hint entry {} in {} specifies both summon onBlock and onEntity; using onBlock.", index, sourceName);
            onEntity = null;
        }

        return new SpawnConditionAnalyzer.SummonInfo(items, onBlock, onEntity);
    }

    @Nullable
    private static String parseKnownBlockId(String text, int index, String sourceName) {
        ResourceLocation blockId = parseResourceLocation(text, "summon target block", index, sourceName);
        if (blockId == null || !isNamespaceLoaded(blockId.getNamespace())) return null;

        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        if (block == null) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring unknown summon target block '{}' in spawn hint entry {} from {}.", blockId, index, sourceName);
            return null;
        }

        return blockId.toString();
    }

    @Nullable
    private static String parseKnownEntityId(String text, int index, String sourceName) {
        ResourceLocation entityId = parseResourceLocation(text, "summon target entity", index, sourceName);
        if (entityId == null || !isNamespaceLoaded(entityId.getNamespace())) return null;

        if (ForgeRegistries.ENTITIES.getValue(entityId) == null) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring unknown summon target entity '{}' in spawn hint entry {} from {}.", entityId, index, sourceName);
            return null;
        }

        return entityId.toString();
    }

    @Nullable
    private static ResourceLocation parseResourceLocation(String text,
                                                          String label,
                                                          int index,
                                                          String sourceName) {
        if (text == null || text.trim().isEmpty()) return null;

        try {
            return new ResourceLocation(text);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring invalid {} '{}' in spawn hint entry {} from {}.", label, text, index, sourceName);
            return null;
        }
    }

    private static JsonObject getObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return null;
        return object.getAsJsonObject(key);
    }

    private static Map<String, BiomeDictionary.Type> buildBiomeTypeMap() {
        Map<String, BiomeDictionary.Type> biomeTypes = new LinkedHashMap<>();

        for (Field field : BiomeDictionary.Type.class.getFields()) {
            if (!BiomeDictionary.Type.class.equals(field.getType())) continue;

            try {
                BiomeDictionary.Type biomeType = (BiomeDictionary.Type) field.get(null);
                if (biomeType != null) biomeTypes.put(field.getName().toUpperCase(Locale.ROOT), biomeType);
            } catch (IllegalAccessException e) {
                // Ignore inaccessible constants.
            }
        }

        return biomeTypes;
    }

    private static JsonArray getArray(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) return null;
        return object.getAsJsonArray(key);
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return null;
        return object.get(key).getAsString();
    }

    private static Integer getInteger(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return null;

        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean getBoolean(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return null;

        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> getStringList(JsonObject object, String key) {
        JsonArray array = getArray(object, key);
        List<String> values = new ArrayList<>();
        if (array == null) return values;

        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) continue;

            String value = element.getAsString();
            if (value == null) continue;

            String trimmed = value.trim();
            if (!trimmed.isEmpty()) values.add(trimmed);
        }

        return values;
    }

    private static List<Integer> getIntegerList(JsonObject object, String key) {
        JsonArray array = getArray(object, key);
        List<Integer> values = new ArrayList<>();
        if (array == null) return values;

        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) continue;

            try {
                values.add(element.getAsInt());
            } catch (Exception e) {
                // Ignore invalid entry.
            }
        }

        return values;
    }

    private static IntRange parseRange(JsonObject object, String key) {
        return parseRange(object, key, null, null, -1, null);
    }

    private static IntRange parseRange(JsonObject object,
                                       String key,
                                       Integer minAllowed,
                                       Integer maxAllowed,
                                       int index,
                                       String sourceName) {
        JsonObject rangeObject = getObject(object, key);
        if (rangeObject == null) return null;

        Integer min = getInteger(rangeObject, "min");
        Integer max = getInteger(rangeObject, "max");
        if (min == null && max == null) return null;
        if (min == null) min = max;
        if (max == null) max = min;

        int resolvedMin = Math.min(min, max);
        int resolvedMax = Math.max(min, max);
        if ((minAllowed != null && resolvedMin < minAllowed) || (maxAllowed != null && resolvedMax > maxAllowed)) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring out-of-range {} in spawn hint entry {} from {}. Expected {}-{} but got {}-{}.",
                key,
                index,
                sourceName,
                minAllowed,
                maxAllowed,
                resolvedMin,
                resolvedMax
            );
            return null;
        }

        return new IntRange(resolvedMin, resolvedMax);
    }

    private static List<int[]> parseTimeRanges(JsonObject object, String key, int index, String sourceName) {
        JsonArray array = getArray(object, key);
        List<int[]> ranges = new ArrayList<>();
        if (array == null) return ranges;

        for (int rangeIndex = 0; rangeIndex < array.size(); rangeIndex++) {
            JsonElement element = array.get(rangeIndex);
            if (!element.isJsonObject()) continue;

            JsonObject rangeObject = element.getAsJsonObject();
            Integer start = getInteger(rangeObject, "start");
            Integer end = getInteger(rangeObject, "end");
            if (start == null && end == null) continue;
            if (start == null) start = end;
            if (end == null) end = start;

            int resolvedStart = Math.min(start, end);
            int resolvedEnd = Math.max(start, end);
            if (resolvedStart < MIN_TIME_OF_DAY || resolvedEnd > MAX_TIME_OF_DAY) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring out-of-range {}[{}] in spawn hint entry {} from {}. Expected {}-{} but got {}-{}.",
                    key,
                    rangeIndex,
                    index,
                    sourceName,
                    MIN_TIME_OF_DAY,
                    MAX_TIME_OF_DAY,
                    resolvedStart,
                    resolvedEnd
                );
                continue;
            }

            ranges.add(new int[]{resolvedStart, resolvedEnd});
        }

        return ranges;
    }

    private static List<String> parseWeatherList(JsonObject object, String key, int index, String sourceName) {
        List<String> values = new ArrayList<>();

        for (String rawWeather : getStringList(object, key)) {
            String weather = rawWeather.trim().toLowerCase(Locale.ROOT);
            if (!VALID_WEATHERS.contains(weather)) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring unsupported weather '{}' in spawn hint entry {} from {}. Supported values are {}.",
                    rawWeather,
                    index,
                    sourceName,
                    VALID_WEATHERS
                );
                continue;
            }

            values.add(weather);
        }

        return values;
    }

    private enum HintField {
        BIOMES,
        DIMENSION_ID,
        DIMENSION_NAME,
        GROUND_BLOCKS,
        LIGHT_LEVELS,
        Y_LEVELS,
        TIME_OF_DAY,
        WEATHER,
        REQUIRES_SKY,
        MOON_PHASES,
        REQUIRES_SLIME_CHUNK,
        REQUIRES_NETHER,
        SUMMON,
        HINTS
    }

    private static class IntRange {
        final int min;
        final int max;

        IntRange(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    private static class RawHintEntry {
        final ResourceLocation entityId;
        final JsonObject object;
        final int index;

        RawHintEntry(ResourceLocation entityId, JsonObject object, int index) {
            this.entityId = entityId;
            this.object = object;
            this.index = index;
        }
    }

    private static class HintEntry {
        final ResourceLocation entityId;
        final String spawnReason;
        final Set<HintField> providedFields = EnumSet.noneOf(HintField.class);
        final Set<String> biomeIds = new LinkedHashSet<>();
        final Set<BiomeDictionary.Type> biomeTypes = new LinkedHashSet<>();
        final Set<BiomeDictionary.Type> requiredBiomeTypes = new LinkedHashSet<>();
        final List<String> groundBlocks = new ArrayList<>();
        final List<int[]> timeOfDay = new ArrayList<>();
        final List<String> weather = new ArrayList<>();
        final List<Integer> moonPhases = new ArrayList<>();
        final List<String> hints = new ArrayList<>();

        Integer dimensionId;
        String dimensionName;
        Integer lightMin;
        Integer lightMax;
        Integer yMin;
        Integer yMax;
        Boolean requiresSky;
        Boolean requiresSlimeChunk;
        Boolean requiresNether;
        SpawnConditionAnalyzer.SummonInfo summon;

        HintEntry(ResourceLocation entityId, String spawnReason) {
            this.entityId = entityId;
            this.spawnReason = spawnReason;
        }

        HintEntry(HintEntry parentEntry, ResourceLocation entityId, @Nullable String spawnReason) {
            this.entityId = entityId;
            this.spawnReason = spawnReason != null ? spawnReason : parentEntry.spawnReason;
            this.providedFields.addAll(parentEntry.providedFields);
            this.biomeIds.addAll(parentEntry.biomeIds);
            this.biomeTypes.addAll(parentEntry.biomeTypes);
            this.requiredBiomeTypes.addAll(parentEntry.requiredBiomeTypes);
            this.groundBlocks.addAll(parentEntry.groundBlocks);

            for (int[] range : parentEntry.timeOfDay) {
                this.timeOfDay.add(new int[]{range[0], range[1]});
            }

            this.weather.addAll(parentEntry.weather);
            this.moonPhases.addAll(parentEntry.moonPhases);
            this.hints.addAll(parentEntry.hints);
            this.dimensionId = parentEntry.dimensionId;
            this.dimensionName = parentEntry.dimensionName;
            this.lightMin = parentEntry.lightMin;
            this.lightMax = parentEntry.lightMax;
            this.yMin = parentEntry.yMin;
            this.yMax = parentEntry.yMax;
            this.requiresSky = parentEntry.requiresSky;
            this.requiresSlimeChunk = parentEntry.requiresSlimeChunk;
            this.requiresNether = parentEntry.requiresNether;
            this.summon = copySummonInfo(parentEntry.summon);
        }

        SpawnConditionAnalyzer.SpawnConditions toSpawnConditions(boolean aquatic, boolean flying) {
            List<String> resolvedBiomes = resolveBiomes();
            int resolvedDimensionId = resolveDimensionId(resolvedBiomes);
            String resolvedDimensionName = resolveDimensionName(resolvedDimensionId);
            List<String> resolvedGroundBlocks = resolveGroundBlocks(aquatic, flying);
            List<Integer> resolvedLightLevels = resolveRange(lightMin, lightMax);
            List<Integer> resolvedYLevels = resolveRange(yMin, yMax);

            return new SpawnConditionAnalyzer.SpawnConditions(
                resolvedBiomes,
                resolvedGroundBlocks,
                resolvedLightLevels,
                resolvedYLevels,
                timeOfDay.isEmpty() ? null : copyTimeRanges(timeOfDay),
                weather.isEmpty() ? null : new ArrayList<>(weather),
                hints.isEmpty() ? null : new ArrayList<>(hints),
                requiresSky,
                moonPhases.isEmpty() ? null : new ArrayList<>(moonPhases),
                requiresSlimeChunk,
                requiresNether,
                resolvedDimensionName,
                resolvedDimensionId,
                spawnReason,
                copySummonInfo(summon),
                false
            );
        }

        SpawnConditionAnalyzer.SpawnConditions applyTo(SpawnConditionAnalyzer.SpawnConditions spawnConditions,
                                                        boolean aquatic,
                                                        boolean flying) {
            List<String> resolvedBiomes = provides(HintField.BIOMES) ? resolveBiomes() : spawnConditions.biomes;
            int resolvedDimensionId = spawnConditions.dimensionId;
            String resolvedDimensionName = spawnConditions.dimension;

            if (provides(HintField.DIMENSION_ID)) {
                resolvedDimensionId = resolveDimensionId(resolvedBiomes);
                resolvedDimensionName = resolveDimensionName(resolvedDimensionId);
            }

            if (provides(HintField.DIMENSION_NAME)) {
                resolvedDimensionName = resolveDimensionName(resolvedDimensionId);
            }

            List<String> resolvedGroundBlocks = provides(HintField.GROUND_BLOCKS)
                ? resolveGroundBlocks(aquatic, flying) : spawnConditions.groundBlocks;
            List<Integer> resolvedLightLevels = provides(HintField.LIGHT_LEVELS)
                ? resolveRange(lightMin, lightMax) : spawnConditions.lightLevels;
            List<Integer> resolvedYLevels = provides(HintField.Y_LEVELS)
                ? resolveRange(yMin, yMax) : spawnConditions.yLevels;
            List<int[]> resolvedTimeOfDay = provides(HintField.TIME_OF_DAY)
                ? (timeOfDay.isEmpty() ? null : copyTimeRanges(timeOfDay)) : spawnConditions.timeOfDay;
            List<String> resolvedWeather = provides(HintField.WEATHER)
                ? (weather.isEmpty() ? null : new ArrayList<>(weather)) : spawnConditions.weather;
            List<String> resolvedHints = provides(HintField.HINTS)
                ? new ArrayList<>(hints) : spawnConditions.hints;
            Boolean resolvedRequiresSky = provides(HintField.REQUIRES_SKY)
                ? requiresSky : spawnConditions.requiresSky;
            List<Integer> resolvedMoonPhases = provides(HintField.MOON_PHASES)
                ? (moonPhases.isEmpty() ? null : new ArrayList<>(moonPhases)) : spawnConditions.moonPhases;
            Boolean resolvedRequiresSlimeChunk = provides(HintField.REQUIRES_SLIME_CHUNK)
                ? requiresSlimeChunk : spawnConditions.requiresSlimeChunk;
            Boolean resolvedRequiresNether = provides(HintField.REQUIRES_NETHER)
                ? requiresNether : spawnConditions.requiresNether;
            SpawnConditionAnalyzer.SummonInfo resolvedSummon = provides(HintField.SUMMON)
                ? copySummonInfo(summon) : spawnConditions.summon;

            return new SpawnConditionAnalyzer.SpawnConditions(
                resolvedBiomes,
                resolvedGroundBlocks,
                resolvedLightLevels,
                resolvedYLevels,
                resolvedTimeOfDay,
                resolvedWeather,
                resolvedHints,
                resolvedRequiresSky,
                resolvedMoonPhases,
                resolvedRequiresSlimeChunk,
                resolvedRequiresNether,
                resolvedDimensionName,
                resolvedDimensionId,
                spawnReason,
                resolvedSummon,
                spawnConditions.analysisFailed
            );
        }

        private boolean provides(HintField field) {
            return providedFields.contains(field);
        }

        private List<String> resolveBiomes() {
            LinkedHashSet<String> resolved = new LinkedHashSet<>(biomeIds);

            for (Biome biome : ForgeRegistries.BIOMES.getValuesCollection()) {
                if (biome.getRegistryName() == null) continue;

                boolean hasAllRequiredTypes = true;
                for (BiomeDictionary.Type biomeType : requiredBiomeTypes) {
                    if (!BiomeDictionary.hasType(biome, biomeType)) {
                        hasAllRequiredTypes = false;
                        break;
                    }
                }

                if (!hasAllRequiredTypes) continue;
                if (biomeTypes.isEmpty() && requiredBiomeTypes.isEmpty()) continue;

                for (BiomeDictionary.Type biomeType : biomeTypes) {
                    if (BiomeDictionary.hasType(biome, biomeType)) {
                        resolved.add(biome.getRegistryName().toString());
                        break;
                    }
                }

                if (biomeTypes.isEmpty()) resolved.add(biome.getRegistryName().toString());
            }

            return new ArrayList<>(resolved);
        }

        private int resolveDimensionId(List<String> resolvedBiomes) {
            if (dimensionId != null) return dimensionId;

            if (!resolvedBiomes.isEmpty()) {
                int inferredDimensionId = BiomeDimensionMapper.findDimensionForBiomes(resolvedBiomes, Integer.MIN_VALUE);
                if (inferredDimensionId != Integer.MIN_VALUE) return inferredDimensionId;
            }

            // External hints should leave the dimension unknown unless they explicitly constrain it.
            return Integer.MIN_VALUE;
        }

        private String resolveDimensionName(int resolvedDimensionId) {
            if (dimensionName != null && !dimensionName.trim().isEmpty()) return dimensionName;

            if (resolvedDimensionId == Integer.MIN_VALUE) return null;

            return BiomeDimensionMapper.getDimensionName(resolvedDimensionId);
        }

        @Nullable
        private List<String> resolveGroundBlocks(boolean aquatic, boolean flying) {
            if (!groundBlocks.isEmpty()) return new ArrayList<>(groundBlocks);
            if (flying) return Collections.singletonList("air");
            if (aquatic) return Collections.singletonList("water");

            return null;
        }

        private List<Integer> resolveRange(Integer min, Integer max) {
            if (min == null && max == null) return new ArrayList<>();

            int resolvedMin = min != null ? min : max;
            int resolvedMax = max != null ? max : min;
            return SpawnConditionAnalyzer.buildIntRange(Math.min(resolvedMin, resolvedMax), Math.max(resolvedMin, resolvedMax));
        }

        private List<int[]> copyTimeRanges(List<int[]> source) {
            List<int[]> copy = new ArrayList<>();

            for (int[] range : source) copy.add(new int[]{range[0], range[1]});

            return copy;
        }

        @Nullable
        private SpawnConditionAnalyzer.SummonInfo copySummonInfo(@Nullable SpawnConditionAnalyzer.SummonInfo source) {
            if (source == null) return null;

            List<SpawnConditionAnalyzer.SummonItem> items = new ArrayList<>();
            for (SpawnConditionAnalyzer.SummonItem summonItem : source.items) {
                items.add(new SpawnConditionAnalyzer.SummonItem(summonItem.itemId, summonItem.metadata, summonItem.count));
            }

            return new SpawnConditionAnalyzer.SummonInfo(items, source.onBlock, source.onEntity);
        }
    }
}
