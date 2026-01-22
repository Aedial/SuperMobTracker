package com.supermobtracker.util;

import net.minecraft.block.Block;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;


/**
 * Utility class for translating game object names (blocks, biomes, dimensions, entities).
 */
public final class TranslationUtils {

    private TranslationUtils() {}

    /**
     * Translates a block registry name to its localized display name.
     * @param blockName The block registry name (e.g., "minecraft:grass")
     * @return The localized block name, or the registry name if not found
     */
    public static String translateBlockName(String blockName) {
        if (blockName == null || blockName.isEmpty()) return "";

        // Try resolving the Block instance to leverage its translation key or localized name
        Block blk = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(blockName));
        if (blk != null) {
            // In 1.12, many mods set a custom translation key based on a display-name-like identifier
            String transKey = blk.getTranslationKey();
            if (transKey != null && !transKey.isEmpty()) {
                String k1 = transKey.endsWith(".name") ? transKey : (transKey + ".name");
                if (I18n.hasKey(k1)) return I18n.format(k1);

                // Some resource packs/mods provide direct key without .name
                if (I18n.hasKey(transKey)) return I18n.format(transKey);
            }

            // If the block has a localized name available already, use it directly
            // getLocalizedName() triggers I18n lookup using the block's unlocalized key
            try {
                String localized = blk.getLocalizedName();
                if (localized != null && !localized.isEmpty()) return localized;
            } catch (Throwable ignored) {
                // Some blocks may throw during early GUI contexts; ignore and continue with fallbacks
            }
        }

        // Parse namespace and path from registry name strings like "minecraft:grass"
        String namespace = "minecraft";
        String path = blockName;
        int colonIdx = blockName.indexOf(":");
        if (colonIdx >= 0) {
            namespace = blockName.substring(0, colonIdx);
            path = blockName.substring(colonIdx + 1);
        }

        // Common 1.12 language key patterns used by vanilla and many mods
        // 1) tile.<namespace>.<path>.name (typical modded style)
        String key1 = "tile." + namespace + "." + path + ".name";
        if (I18n.hasKey(key1)) return I18n.format(key1);

        // 2) tile.<path>.name (older vanilla style)
        String key2 = "tile." + path + ".name";
        if (I18n.hasKey(key2)) return I18n.format(key2);

        // 3) tile.<namespace>:<path>.name (some mods use a colon in keys)
        String key3 = "tile." + namespace + ":" + path + ".name";
        if (I18n.hasKey(key3)) return I18n.format(key3);

        // 4) block.<namespace>.<path> (newer style seen in resource packs)
        String key4 = "block." + namespace + "." + path;
        if (I18n.hasKey(key4)) return I18n.format(key4);

        // 5) block.<path> (fallback variant)
        String key5 = "block." + path;
        if (I18n.hasKey(key5)) return I18n.format(key5);

        // 6) Direct registry string when mods register explicit keys
        if (I18n.hasKey(blockName)) return I18n.format(blockName);

        // Fallback: give up and return the raw registry name
        return blockName;
    }

    /**
     * Translates a biome registry name to its localized display name.
     * @param biomeRegistryName The biome registry name (e.g., "minecraft:plains")
     * @return The localized biome name, or the registry name if not found
     */
    public static String translateBiomeName(String biomeRegistryName) {
        Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(biomeRegistryName));
        if (biome == null) return biomeRegistryName;

        // Parse namespace and path from registry name string for translation lookup
        String namespace = "minecraft";
        String path = biomeRegistryName;
        int colonIdx = biomeRegistryName.indexOf(':');
        if (colonIdx >= 0) {
            namespace = biomeRegistryName.substring(0, colonIdx);
            path = biomeRegistryName.substring(colonIdx + 1);
        }

        // Try common translation key patterns used by mods
        // Pattern 1: biome.<namespace>.<path> (common modded pattern)
        String key1 = "biome." + namespace + "." + path;
        String translated1 = I18n.format(key1);
        if (!translated1.equals(key1)) return translated1;

        // Pattern 2: biome.<namespace>:<path> (alternate pattern)
        String key2 = "biome." + biomeRegistryName;
        String translated2 = I18n.format(key2);
        if (!translated2.equals(key2)) return translated2;

        // Pattern 3: biome.<path>.name (some mods use this)
        String key3 = "biome." + path + ".name";
        String translated3 = I18n.format(key3);
        if (!translated3.equals(key3)) return translated3;

        // Try the biome's internal name - works for most mods which set display names directly in BiomeProperties
        // It will, however, not be "localized"
        String biomeName = biome.getBiomeName();
        if (biomeName != null && !biomeName.isEmpty() && !biomeName.equals(biomeRegistryName) && !biomeName.contains(":") && !biomeName.contains("_")) {
            return biomeName;
        }

        return biomeRegistryName;
    }

    /**
     * Translates a dimension name to a localized display string.
     * Tries various translation key patterns used by Minecraft and mods.
     * @param dimName The dimension registry name (e.g., "minecraft:overworld")
     * @return The localized dimension name, or the registry name if not found
     */
    public static String translateDimensionName(String dimName) {
        if (dimName == null || dimName.isEmpty()) return "?";

        // Parse namespace and path
        String namespace = "minecraft";
        String path = dimName;
        int colonIdx = dimName.indexOf(':');
        if (colonIdx >= 0) {
            namespace = dimName.substring(0, colonIdx);
            path = dimName.substring(colonIdx + 1);
        }

        // Try common translation key patterns
        // Pattern 1: dimension.<namespace>.<path> (modded pattern)
        String key1 = "dimension." + namespace + "." + path;
        String translated1 = I18n.format(key1);
        if (!translated1.equals(key1)) return translated1;

        // Pattern 2: dimension.<path> (vanilla-style)
        String key2 = "dimension." + path;
        String translated2 = I18n.format(key2);
        if (!translated2.equals(key2)) return translated2;

        // Pattern 3: <namespace>.dimension.<path> (alternate modded)
        String key3 = namespace + ".dimension." + path;
        String translated3 = I18n.format(key3);
        if (!translated3.equals(key3)) return translated3;

        // Pattern 4: dimension.<namespace>:<path>
        String key4 = "dimension." + dimName;
        String translated4 = I18n.format(key4);
        if (!translated4.equals(key4)) return translated4;

        // No translation found - return raw name so user knows what key to add
        return dimName;
    }

    /**
     * Formats the entity name based on settings.
     * @param id Entity ResourceLocation
     * @param entity The entity instance (can be null)
     * @param applyI18n Whether to apply internationalization
     * @return Formatted entity name
     */
    public static String formatEntityName(ResourceLocation id, Entity entity, boolean applyI18n) {
        if (id == null) return "";
        if (!applyI18n) return id.toString();

        if (entity != null) return entity.getDisplayName().getUnformattedText();

        // Fallback for modded entities missing translation mapping
        String[] parts = id.toString().split(":", 2);
        String domain = parts.length > 0 ? parts[0] : "minecraft";
        String path = parts.length > 1 ? parts[1] : parts[0];
        String altKey = "entity." + domain + "." + path + ".name";
        if (I18n.hasKey(altKey)) return I18n.format(altKey);

        return id.toString();
    }
}
