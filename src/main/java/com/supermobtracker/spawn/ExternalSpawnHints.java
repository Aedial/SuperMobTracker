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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLiving;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.config.ModConfig;


/**
 * Loads externally defined spawn hints for entities that do not use normal biome spawn tables.
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

    public static SpawnConditionAnalyzer.SpawnConditions getSpawnConditions(ResourceLocation entityId,
                                                                            EntityLiving entity,
                                                                            boolean aquatic,
                                                                            boolean flying) {
        reloadIfNeeded();

        HintEntry entry = cachedEntries.get(entityId);
        if (entry == null) return null;

        return entry.toSpawnConditions(entity, aquatic, flying);
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

        for (int i = 0; i < rawEntries.size(); i++) {
            JsonElement rawEntry = rawEntries.get(i);
            if (!rawEntry.isJsonObject()) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring spawn hint entry {} in {} because it is not an object.", i, sourceName);
                continue;
            }

            HintEntry entry = parseEntry(rawEntry.getAsJsonObject(), i, sourceName);
            if (entry == null) continue;

            entries.put(entry.entityId, entry);
        }
    }

    private static HintEntry parseEntry(JsonObject object, int index, String sourceName) {
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

        if (!isNamespaceLoaded(entityId.getNamespace())) return null;

        String spawnReason = normalizeSpawnReason(getString(object, "spawnReason"));
        if (spawnReason == null) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring spawn hint entry {} in {} because 'spawnReason' is missing.", index, sourceName);
            return null;
        }

        HintEntry entry = new HintEntry(entityId, spawnReason);

        JsonObject biomeObject = getObject(object, "biomes");
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
        }

        entry.dimensionId = getInteger(object, "dimensionId");
        String dimensionName = getString(object, "dimensionName");
        if (dimensionName != null && !isTranslationKey(dimensionName)) {
            SuperMobTracker.LOGGER.warn(
                "Ignoring non-localized dimensionName '{}' in spawn hint entry {} from {}.",
            dimensionName, index, sourceName);
        } else {
            entry.dimensionName = dimensionName;
        }
        entry.groundBlocks.addAll(getStringList(object, "groundBlocks"));

        IntRange lightLevels = parseRange(object, "lightLevels", MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL, index, sourceName);
        if (lightLevels != null) {
            entry.lightMin = lightLevels.min;
            entry.lightMax = lightLevels.max;
        }

        IntRange yLevels = parseRange(object, "yLevels");
        if (yLevels != null) {
            entry.yMin = yLevels.min;
            entry.yMax = yLevels.max;
        }

        entry.timeOfDay.addAll(parseTimeRanges(object, "timeOfDay", index, sourceName));
        entry.weather.addAll(parseWeatherList(object, "weather", index, sourceName));
        entry.requiresSky = getBoolean(object, "requiresSky");
        entry.moonPhases.addAll(getIntegerList(object, "moonPhases"));
        entry.requiresSlimeChunk = getBoolean(object, "requiresSlimeChunk");
        entry.requiresNether = getBoolean(object, "requiresNether");
        for (String hintKey : getStringList(object, "hints")) {
            if (!isTranslationKey(hintKey)) {
                SuperMobTracker.LOGGER.warn(
                    "Ignoring non-localized hint '{}' in spawn hint entry {} from {}.",
                    hintKey, index, sourceName);
                continue;
            }

            entry.hints.add(hintKey);
        }

        return entry;
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

    private static class IntRange {
        final int min;
        final int max;

        IntRange(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    private static class HintEntry {
        final ResourceLocation entityId;
        final String spawnReason;
        final Set<String> biomeIds = new LinkedHashSet<>();
        final Set<BiomeDictionary.Type> biomeTypes = new LinkedHashSet<>();
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

        HintEntry(ResourceLocation entityId, String spawnReason) {
            this.entityId = entityId;
            this.spawnReason = spawnReason;
        }

        SpawnConditionAnalyzer.SpawnConditions toSpawnConditions(EntityLiving entity, boolean aquatic, boolean flying) {
            List<String> resolvedBiomes = resolveBiomes();
            int currentDimensionId = entity.world != null ? entity.world.provider.getDimension() : 0;
            int resolvedDimensionId = resolveDimensionId(currentDimensionId, resolvedBiomes);
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
                spawnReason
            );
        }

        private List<String> resolveBiomes() {
            LinkedHashSet<String> resolved = new LinkedHashSet<>(biomeIds);

            for (Biome biome : ForgeRegistries.BIOMES.getValuesCollection()) {
                if (biome.getRegistryName() == null) continue;

                for (BiomeDictionary.Type biomeType : biomeTypes) {
                    if (BiomeDictionary.hasType(biome, biomeType)) {
                        resolved.add(biome.getRegistryName().toString());
                        break;
                    }
                }
            }

            return new ArrayList<>(resolved);
        }

        private int resolveDimensionId(int currentDimensionId, List<String> resolvedBiomes) {
            if (dimensionId != null) return dimensionId;
            if (!resolvedBiomes.isEmpty()) {
                int inferredDimensionId = BiomeDimensionMapper.findDimensionForBiomes(resolvedBiomes, currentDimensionId);
                if (inferredDimensionId != Integer.MIN_VALUE) return inferredDimensionId;
            }

            return currentDimensionId;
        }

        private String resolveDimensionName(int resolvedDimensionId) {
            if (dimensionName != null && !dimensionName.trim().isEmpty()) return dimensionName;
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
    }
}
