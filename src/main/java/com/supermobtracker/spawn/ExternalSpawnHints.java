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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.client.resources.I18n;
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
    private static final Pattern INVALID_TRANSLATION_KEY_CHARACTER_PATTERN = Pattern.compile("[^A-Za-z0-9_.-]");

    private static Map<ResourceLocation, HintEntry> cachedEntries = Collections.emptyMap();
    private static final Set<ResourceLocation> MISSING_SPAWN_REASON_WARNINGS =
        Collections.synchronizedSet(new HashSet<>());
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

        if (spawnConditions == null) {
            if (entry.spawnReason == null) {
                warnMissingSpawnReason(entry);
                return null;
            }

            return entry.toSpawnConditions(aquatic, flying);
        }

        if (entry.spawnReason == null && spawnConditions.spawnReason == null) {
            warnMissingSpawnReason(entry);
            return spawnConditions;
        }

        return entry.applyTo(spawnConditions, aquatic, flying);
    }

    private static void reloadIfNeeded() {
        File file = getHintsFile();
        long lastModified = file.exists() ? file.lastModified() : -1L;

        if (cacheInitialized && lastModified == lastLoadedTimestamp) return;

        cacheInitialized = true;
        lastLoadedTimestamp = lastModified;
        MISSING_SPAWN_REASON_WARNINGS.clear();
        cachedEntries = loadEntries(file);
    }

    private static void warnMissingSpawnReason(HintEntry entry) {
        if (!MISSING_SPAWN_REASON_WARNINGS.add(entry.entityId)) return;

        SuperMobTracker.LOGGER.warn(
            "Ignoring spawn hint for {} from {} because neither the hint nor native analysis provides a spawn reason.",
            entry.entityId, entry.sourceName);
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

        List<HintEntrySource> sourceEntryList = new ArrayList<>();
        Map<ResourceLocation, HintEntrySource> sourceEntries = new LinkedHashMap<>();
        Map<ResourceLocation, HintEntry> resolvedEntries = new LinkedHashMap<>();

        for (int i = 0; i < rawEntries.size(); i++) {
            JsonElement rawEntry = rawEntries.get(i);
            if (!rawEntry.isJsonObject()) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring spawn hint entry {} in {} because it is not an object: {}.",
                    i, sourceName, rawEntry);
                continue;
            }

            JsonObject entryObject = rawEntry.getAsJsonObject();
            ResourceLocation entityId = parseEntryId(entryObject, i, sourceName);
            if (entityId == null || !isNamespaceLoaded(entityId.getNamespace())) continue;

            if (sourceEntries.containsKey(entityId)) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring duplicate spawn hint for {} in {}. Keeping the first entry: {}.",
                    entityId, sourceName, entryObject);
                continue;
            }

            HintEntrySource sourceEntry = new HintEntrySource(entityId, entryObject);
            sourceEntryList.add(sourceEntry);
            sourceEntries.put(entityId, sourceEntry);
        }

        for (HintEntrySource sourceEntry : sourceEntryList) {
            HintEntry entry = parseEntry(sourceEntry, sourceName, entries, sourceEntries, resolvedEntries,
                new LinkedHashSet<>());
            if (entry == null) continue;

            entries.put(entry.entityId, entry);
        }
    }

    @Nullable
    private static ResourceLocation parseEntryId(JsonObject object, int index, String sourceName) {
        String entityIdText = getString(object, "entityId");
        if (entityIdText == null || entityIdText.trim().isEmpty()) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hint entry {} in {} because 'entityId' must be a non-empty string: {}.",
                index, sourceName, object);
            return null;
        }

        ResourceLocation entityId;
        try {
            entityId = new ResourceLocation(entityIdText);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hint entry {} in {} because '{}' is not a valid entity ID: {}.",
                index, sourceName, entityIdText, object);
            return null;
        }

        return entityId;
    }

    @Nullable
    private static HintEntry parseEntry(HintEntrySource rawEntry,
                                        String sourceName,
                                        Map<ResourceLocation, HintEntry> loadedEntries,
                                        Map<ResourceLocation, HintEntrySource> sourceEntries,
                                        Map<ResourceLocation, HintEntry> resolvedEntries,
                                        Set<ResourceLocation> resolutionStack) {
        HintEntry cachedEntry = resolvedEntries.get(rawEntry.entityId);
        if (cachedEntry != null) return cachedEntry;

        if (!resolutionStack.add(rawEntry.entityId)) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hint for {} in {} because it has a circular parent chain.",
                rawEntry.entityId, sourceName);
            return null;
        }

        JsonObject object = rawEntry.object;
        String parentText = getString(object, "parent");
        if (object.has("parent") && (parentText == null || parentText.trim().isEmpty())) {
            warnInvalidFieldType(rawEntry, sourceName, "parent", "a non-empty string");
        }

        String spawnReasonText = getString(object, "spawnReason");
        if (object.has("spawnReason") && spawnReasonText == null) {
            warnInvalidFieldType(rawEntry, sourceName, "spawnReason", "a string");
        }

        String spawnReason = normalizeSpawnReason(spawnReasonText);
        HintEntry entry;

        if (parentText != null && !parentText.trim().isEmpty()) {
            ResourceLocation parentId = parseResourceLocation(parentText, "parent entity", rawEntry, sourceName);
            if (parentId == null || !isNamespaceLoaded(parentId.getNamespace())) {
                resolutionStack.remove(rawEntry.entityId);
                return null;
            }

            HintEntry parentEntry = resolveParentEntry(parentId, sourceName, loadedEntries, sourceEntries,
                resolvedEntries, resolutionStack);
            if (parentEntry == null) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring spawn hint for {} in {} because parent '{}' could not be resolved.",
                    rawEntry.entityId, sourceName, parentText);
                resolutionStack.remove(rawEntry.entityId);
                return null;
            }

            entry = new HintEntry(parentEntry, rawEntry.entityId, spawnReason, sourceName);
        } else {
            entry = new HintEntry(rawEntry.entityId, spawnReason, sourceName);
        }

        applyEntryData(entry, object, rawEntry, sourceName);
        resolvedEntries.put(entry.entityId, entry);
        resolutionStack.remove(rawEntry.entityId);

        return entry;
    }

    @Nullable
    private static HintEntry resolveParentEntry(ResourceLocation parentId,
                                                String sourceName,
                                                Map<ResourceLocation, HintEntry> loadedEntries,
                                                Map<ResourceLocation, HintEntrySource> sourceEntries,
                                                Map<ResourceLocation, HintEntry> resolvedEntries,
                                                Set<ResourceLocation> resolutionStack) {
        HintEntry cachedEntry = resolvedEntries.get(parentId);
        if (cachedEntry != null) return cachedEntry;

        HintEntrySource sourceEntry = sourceEntries.get(parentId);
        if (sourceEntry != null) {
            return parseEntry(sourceEntry, sourceName, loadedEntries, sourceEntries, resolvedEntries, resolutionStack);
        }

        return loadedEntries.get(parentId);
    }

    private static void applyEntryData(HintEntry entry, JsonObject object,
                                       HintEntrySource sourceEntry, String sourceName) {
        JsonObject biomeObject = getObject(object, "biomes");
        if (biomeObject != null) {
            entry.providedFields.add(HintField.BIOMES);
            entry.biomeIds.clear();
            entry.biomeTypes.clear();
            entry.requiredBiomeTypes.clear();

            for (String biomeIdText : getStringList(biomeObject, "ids", sourceEntry, sourceName)) {
                ResourceLocation biomeId;

                try {
                    biomeId = new ResourceLocation(biomeIdText);
                } catch (Exception e) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring invalid biome ID '{}' in spawn hint for {} from {}.",
                        biomeIdText, sourceEntry.entityId, sourceName);
                    continue;
                }

                if (!isNamespaceLoaded(biomeId.getNamespace())) continue;

                Biome biome = ForgeRegistries.BIOMES.getValue(biomeId);
                if (biome == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown biome ID '{}' in spawn hint for {} from {}.",
                        biomeIdText, sourceEntry.entityId, sourceName);
                    continue;
                }

                entry.biomeIds.add(biome.getRegistryName().toString());
            }

            for (String biomeTypeText : getStringList(biomeObject, "types", sourceEntry, sourceName)) {
                BiomeDictionary.Type biomeType = BIOME_TYPES_BY_NAME.get(biomeTypeText.trim().toUpperCase(Locale.ROOT));
                if (biomeType == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown biome type '{}' in spawn hint for {} from {}.",
                        biomeTypeText, sourceEntry.entityId, sourceName);
                    continue;
                }

                entry.biomeTypes.add(biomeType);
            }

            for (String biomeTypeText : getStringList(biomeObject, "allTypes", sourceEntry, sourceName)) {
                BiomeDictionary.Type biomeType = BIOME_TYPES_BY_NAME.get(biomeTypeText.trim().toUpperCase(Locale.ROOT));
                if (biomeType == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown required biome type '{}' in spawn hint for {} from {}.",
                        biomeTypeText, sourceEntry.entityId, sourceName);
                    continue;
                }

                entry.requiredBiomeTypes.add(biomeType);
            }
        } else if (object.has("biomes")) {
            warnInvalidFieldType(sourceEntry, sourceName, "biomes", "an object");
        }

        Integer dimensionId = getInteger(object, "dimensionId");
        if (dimensionId != null) {
            entry.providedFields.add(HintField.DIMENSION_ID);
            entry.dimensionId = dimensionId;
        } else if (object.has("dimensionId")) {
            warnInvalidFieldType(sourceEntry, sourceName, "dimensionId", "an integer");
        }

        String dimensionName = getString(object, "dimensionName");
        if (dimensionName != null) {
            if (validateTranslationKey(dimensionName, "dimensionName", sourceEntry, sourceName)) {
                entry.providedFields.add(HintField.DIMENSION_NAME);
                entry.dimensionName = dimensionName;
            }
        } else if (object.has("dimensionName")) {
            warnInvalidFieldType(sourceEntry, sourceName, "dimensionName", "a string");
        }

        JsonArray groundBlocks = getArray(object, "groundBlocks");
        if (groundBlocks != null) {
            entry.providedFields.add(HintField.GROUND_BLOCKS);
            entry.groundBlocks.clear();
            entry.groundBlocks.addAll(getStringList(groundBlocks, "groundBlocks", sourceEntry, sourceName));
        } else if (object.has("groundBlocks")) {
            warnInvalidFieldType(sourceEntry, sourceName, "groundBlocks", "an array");
        }

        if (getObject(object, "lightLevels") != null) {
            entry.providedFields.add(HintField.LIGHT_LEVELS);
            IntRange lightLevels = parseRange(object, "lightLevels", MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL,
                sourceEntry, sourceName);
            entry.lightMin = lightLevels != null ? lightLevels.min : null;
            entry.lightMax = lightLevels != null ? lightLevels.max : null;
        } else if (object.has("lightLevels")) {
            warnInvalidFieldType(sourceEntry, sourceName, "lightLevels", "an object");
        }

        if (getObject(object, "yLevels") != null) {
            entry.providedFields.add(HintField.Y_LEVELS);
            IntRange yLevels = parseRange(object, "yLevels", null, null, sourceEntry, sourceName);
            entry.yMin = yLevels != null ? yLevels.min : null;
            entry.yMax = yLevels != null ? yLevels.max : null;
        } else if (object.has("yLevels")) {
            warnInvalidFieldType(sourceEntry, sourceName, "yLevels", "an object");
        }

        if (getArray(object, "timeOfDay") != null) {
            entry.providedFields.add(HintField.TIME_OF_DAY);
            entry.timeOfDay.clear();
            entry.timeOfDay.addAll(parseTimeRanges(object, "timeOfDay", sourceEntry, sourceName));
        } else if (object.has("timeOfDay")) {
            warnInvalidFieldType(sourceEntry, sourceName, "timeOfDay", "an array");
        }

        if (getArray(object, "weather") != null) {
            entry.providedFields.add(HintField.WEATHER);
            entry.weather.clear();
            entry.weather.addAll(parseWeatherList(object, "weather", sourceEntry, sourceName));
        } else if (object.has("weather")) {
            warnInvalidFieldType(sourceEntry, sourceName, "weather", "an array");
        }

        Boolean requiresSky = getBoolean(object, "requiresSky");
        if (requiresSky != null) {
            entry.providedFields.add(HintField.REQUIRES_SKY);
            entry.requiresSky = requiresSky;
        } else if (object.has("requiresSky")) {
            warnInvalidFieldType(sourceEntry, sourceName, "requiresSky", "a boolean");
        }

        if (getArray(object, "moonPhases") != null) {
            entry.providedFields.add(HintField.MOON_PHASES);
            entry.moonPhases.clear();
            entry.moonPhases.addAll(parseMoonPhases(object, sourceEntry, sourceName));
        } else if (object.has("moonPhases")) {
            warnInvalidFieldType(sourceEntry, sourceName, "moonPhases", "an array");
        }

        Boolean requiresSlimeChunk = getBoolean(object, "requiresSlimeChunk");
        if (requiresSlimeChunk != null) {
            entry.providedFields.add(HintField.REQUIRES_SLIME_CHUNK);
            entry.requiresSlimeChunk = requiresSlimeChunk;
        } else if (object.has("requiresSlimeChunk")) {
            warnInvalidFieldType(sourceEntry, sourceName, "requiresSlimeChunk", "a boolean");
        }

        Boolean requiresNether = getBoolean(object, "requiresNether");
        if (requiresNether != null) {
            entry.providedFields.add(HintField.REQUIRES_NETHER);
            entry.requiresNether = requiresNether;
        } else if (object.has("requiresNether")) {
            warnInvalidFieldType(sourceEntry, sourceName, "requiresNether", "a boolean");
        }

        if (getObject(object, "summon") != null) {
            SpawnConditionAnalyzer.SummonInfo summon = parseSummonInfo(object, sourceEntry, sourceName);
            if (summon != null) {
                entry.providedFields.add(HintField.SUMMON);
                entry.summon = summon;
            }
        } else if (object.has("summon")) {
            warnInvalidFieldType(sourceEntry, sourceName, "summon", "an object");
        }

        JsonArray hints = getArray(object, "hints");
        if (hints != null) {
            entry.providedFields.add(HintField.HINTS);
            entry.hints.clear();

            for (String hintKey : getStringList(hints, "hints", sourceEntry, sourceName)) {
                if (validateHintTranslationKey(hintKey, sourceEntry, sourceName)) {
                    entry.hints.add(hintKey);
                }
            }
        } else if (object.has("hints")) {
            warnInvalidFieldType(sourceEntry, sourceName, "hints", "an array");
        }
    }

    private static String normalizeSpawnReason(String spawnReason) {
        if (spawnReason == null) return null;

        String normalized = spawnReason.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean validateHintTranslationKey(String value, HintEntrySource sourceEntry,
                                                      String sourceName) {
        if (!value.equals(value.toLowerCase(Locale.ROOT))) {
            SuperMobTracker.LOGGER.error(
                "Ignoring hint '{}' in spawn hint for {} from {} because Super Mob Tracker hint keys "
                    + "cannot contain uppercase characters.",
                value, sourceEntry.entityId, sourceName);
            return false;
        }

        return validateTranslationKey(value, "hint", sourceEntry, sourceName);
    }

    private static boolean validateTranslationKey(String value, String field,
                                                  HintEntrySource sourceEntry, String sourceName) {
        if (value.isEmpty()) {
            SuperMobTracker.LOGGER.error(
                "Ignoring {} in spawn hint for {} from {} because translation keys cannot be empty.",
                field, sourceEntry.entityId, sourceName);
            return false;
        }

        if (INVALID_TRANSLATION_KEY_CHARACTER_PATTERN.matcher(value).find()) {
            SuperMobTracker.LOGGER.error(
                "Ignoring {} '{}' in spawn hint for {} from {} because translation key " +
                "contains invalid characters.",
                field, value, sourceEntry.entityId, sourceName);
            return false;
        }

        if (!I18n.hasKey(value)) {
            SuperMobTracker.LOGGER.warn(
                "Spawn hint for {} from {} uses {} '{}' without an active translation.",
                sourceEntry.entityId, sourceName, field, value);
        }

        return true;
    }

    private static boolean isNamespaceLoaded(String namespace) {
        if (namespace == null || namespace.isEmpty()) return true;
        if ("minecraft".equals(namespace) || "forge".equals(namespace)) return true;

        return Loader.isModLoaded(namespace);
    }

    @Nullable
    private static SpawnConditionAnalyzer.SummonInfo parseSummonInfo(JsonObject entryObject,
                                                                     HintEntrySource sourceEntry,
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
                        "Ignoring non-object summon item {} in spawn hint for {} from {}.",
                        itemIndex, sourceEntry.entityId, sourceName);
                    continue;
                }

                JsonObject itemObject = rawItem.getAsJsonObject();
                ResourceLocation itemId = parseResourceLocation(getString(itemObject, "id"), "summon item",
                    sourceEntry, sourceName);
                if (itemId == null || !isNamespaceLoaded(itemId.getNamespace())) continue;

                Item item = ForgeRegistries.ITEMS.getValue(itemId);
                if (item == null) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring unknown summon item '{}' in spawn hint for {} from {}.",
                        itemId, sourceEntry.entityId, sourceName);
                    continue;
                }

                Integer metadata = getInteger(itemObject, "metadata");
                Integer count = getInteger(itemObject, "count");
                int resolvedMetadata = metadata == null ? 0 : metadata;
                int resolvedCount = count == null ? 1 : count;
                if (resolvedMetadata < 0 || resolvedCount < 1) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring summon item '{}' in spawn hint for {} from {} because metadata must be non-negative and count must be positive.",
                        itemId, sourceEntry.entityId, sourceName);
                    continue;
                }

                items.add(new SpawnConditionAnalyzer.SummonItem(itemId.toString(), resolvedMetadata, resolvedCount));
            }
        }

        if (items.isEmpty()) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring summon metadata in spawn hint for {} from {} because it has no valid items.",
                sourceEntry.entityId, sourceName);
            return null;
        }

        String onBlock = parseKnownBlockId(getString(summonObject, "onBlock"), sourceEntry, sourceName);
        String onEntity = parseKnownEntityId(getString(summonObject, "onEntity"), sourceEntry, sourceName);
        if (onBlock != null && onEntity != null) {
            SuperMobTracker.LOGGER.warn(
                "Spawn hint for {} from {} specifies both summon onBlock and onEntity; using onBlock.",
                sourceEntry.entityId, sourceName);
            onEntity = null;
        }

        return new SpawnConditionAnalyzer.SummonInfo(items, onBlock, onEntity);
    }

    @Nullable
    private static String parseKnownBlockId(String text, HintEntrySource sourceEntry, String sourceName) {
        ResourceLocation blockId = parseResourceLocation(text, "summon target block", sourceEntry, sourceName);
        if (blockId == null || !isNamespaceLoaded(blockId.getNamespace())) return null;

        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        if (block == null) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring unknown summon target block '{}' in spawn hint for {} from {}.",
                blockId, sourceEntry.entityId, sourceName);
            return null;
        }

        return blockId.toString();
    }

    @Nullable
    private static String parseKnownEntityId(String text, HintEntrySource sourceEntry, String sourceName) {
        ResourceLocation entityId = parseResourceLocation(text, "summon target entity", sourceEntry, sourceName);
        if (entityId == null || !isNamespaceLoaded(entityId.getNamespace())) return null;

        if (ForgeRegistries.ENTITIES.getValue(entityId) == null) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring unknown summon target entity '{}' in spawn hint for {} from {}.",
                entityId, sourceEntry.entityId, sourceName);
            return null;
        }

        return entityId.toString();
    }

    @Nullable
    private static ResourceLocation parseResourceLocation(String text,
                                                          String label,
                                                          HintEntrySource sourceEntry,
                                                          String sourceName) {
        if (text == null || text.trim().isEmpty()) return null;

        try {
            return new ResourceLocation(text);
        } catch (Exception e) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring invalid {} '{}' in spawn hint for {} from {}.",
                label, text, sourceEntry.entityId, sourceName);
            return null;
        }
    }

    private static JsonObject getObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return null;
        return object.getAsJsonObject(key);
    }

    private static void warnInvalidFieldType(HintEntrySource sourceEntry, String sourceName,
                                             String field, String expectedType) {
        SuperMobTracker.LOGGER.warn(
            "Ignoring {} in spawn hint for {} from {} because it must be {}.",
            field, sourceEntry.entityId, sourceName, expectedType);
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
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isString()) return null;
        return object.get(key).getAsString();
    }

    private static Integer getInteger(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isNumber()) return null;

        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean getBoolean(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isBoolean()) return null;

        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> getStringList(JsonObject object, String key,
                                              HintEntrySource sourceEntry, String sourceName) {
        return getStringList(getArray(object, key), key, sourceEntry, sourceName);
    }

    private static List<String> getStringList(JsonArray array, String key,
                                              HintEntrySource sourceEntry, String sourceName) {
        List<String> values = new ArrayList<>();
        if (array == null) return values;

        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring non-string {}[{}] in spawn hint for {} from {}.",
                    key, index, sourceEntry.entityId, sourceName);
                continue;
            }

            String value = element.getAsString();
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring empty {}[{}] in spawn hint for {} from {}.",
                    key, index, sourceEntry.entityId, sourceName);
                continue;
            }

            values.add(trimmed);
        }

        return values;
    }

    private static List<Integer> parseMoonPhases(JsonObject object,
                                                 HintEntrySource sourceEntry, String sourceName) {
        JsonArray array = getArray(object, "moonPhases");
        List<Integer> values = new ArrayList<>();
        if (array == null) return values;

        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring non-integer moonPhases[{}] in spawn hint for {} from {}.",
                    index, sourceEntry.entityId, sourceName);
                continue;
            }

            try {
                int phase = element.getAsInt();
                if (phase < 0 || phase > 7) {
                    SuperMobTracker.LOGGER.warn(
                        "Ignoring out-of-range moonPhases[{}] in spawn hint for {} from {}. Expected 0-7 but got {}.",
                        index, sourceEntry.entityId, sourceName, phase);
                    continue;
                }

                values.add(phase);
            } catch (Exception e) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring non-integer moonPhases[{}] in spawn hint for {} from {}.",
                    index, sourceEntry.entityId, sourceName);
            }
        }

        return values;
    }

    private static IntRange parseRange(JsonObject object,
                                       String key,
                                       Integer minAllowed,
                                       Integer maxAllowed,
                                       HintEntrySource sourceEntry,
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
                "Ignoring out-of-range {} in spawn hint for {} from {}. Expected {}-{} but got {}-{}.",
                key,
                sourceEntry.entityId,
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

    private static List<int[]> parseTimeRanges(JsonObject object, String key,
                                               HintEntrySource sourceEntry, String sourceName) {
        JsonArray array = getArray(object, key);
        List<int[]> ranges = new ArrayList<>();
        if (array == null) return ranges;

        for (int rangeIndex = 0; rangeIndex < array.size(); rangeIndex++) {
            JsonElement element = array.get(rangeIndex);
            if (!element.isJsonObject()) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring non-object {}[{}] in spawn hint for {} from {}.",
                    key, rangeIndex, sourceEntry.entityId, sourceName);
                continue;
            }

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
                    "Ignoring out-of-range {}[{}] in spawn hint for {} from {}. Expected {}-{} but got {}-{}.",
                    key,
                    rangeIndex,
                    sourceEntry.entityId,
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

    private static List<String> parseWeatherList(JsonObject object, String key,
                                                 HintEntrySource sourceEntry, String sourceName) {
        List<String> values = new ArrayList<>();

        for (String rawWeather : getStringList(object, key, sourceEntry, sourceName)) {
            String weather = rawWeather.trim().toLowerCase(Locale.ROOT);
            if (!VALID_WEATHERS.contains(weather)) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring unsupported weather '{}' in spawn hint for {} from {}. Supported values are {}.",
                    rawWeather,
                    sourceEntry.entityId,
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

    private static class HintEntrySource {
        final ResourceLocation entityId;
        final JsonObject object;

        HintEntrySource(ResourceLocation entityId, JsonObject object) {
            this.entityId = entityId;
            this.object = object;
        }
    }

    private static class HintEntry {
        final ResourceLocation entityId;
        final String sourceName;
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

        HintEntry(ResourceLocation entityId, @Nullable String spawnReason, String sourceName) {
            this.entityId = entityId;
            this.spawnReason = spawnReason;
            this.sourceName = sourceName;
        }

        HintEntry(HintEntry parentEntry, ResourceLocation entityId, @Nullable String spawnReason,
                  String sourceName) {
            this.entityId = entityId;
            this.spawnReason = spawnReason != null ? spawnReason : parentEntry.spawnReason;
            this.sourceName = sourceName;
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
            String resolvedSpawnReason = spawnReason != null ? spawnReason : spawnConditions.spawnReason;

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
                resolvedSpawnReason,
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
