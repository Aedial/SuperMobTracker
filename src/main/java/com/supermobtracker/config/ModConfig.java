package com.supermobtracker.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.File;
import java.util.List;

import com.supermobtracker.SuperMobTracker;

import java.util.Collection;

import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

public class ModConfig {
    private static Configuration config;

    // HUD position enum
    public enum HudPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    public static boolean clientEnableTracking = true; // can disable tracking entirely
    public static double clientDetectionRange = 64.0; // range for spawn event consideration client side
    public static boolean clientI18nNames = true; // localize names in UI/HUD
    public static int clientSpawnCheckRetries = 100; // retry count for random spawn checks
    public static List<String> clientTrackedEntityIds = new ArrayList<>();
    public static String clientLastSelectedEntity = ""; // last selected entity in mob tracker GUI
    public static String clientFilterText = ""; // last filter text in mob tracker GUI
    public static HudPosition clientHudPosition = HudPosition.TOP_LEFT; // HUD overlay position
    public static int clientHudPaddingExternal = 4; // padding from screen edge
    public static int clientHudPaddingInternal = 2; // padding inside the box
    public static int clientHudLineSpacing = 2; // spacing between lines
    public static boolean clientHudEnabled = true; // whether to show the HUD overlay

    private static final String enableTrackingDesc = I18n.translateToLocal("config.supermobtracker.client.enableTracking.desc");
    private static final String detectionRangeDesc = I18n.translateToLocal("config.supermobtracker.client.detectionRange.desc");
    private static final String i18nNamesDesc = I18n.translateToLocal("config.supermobtracker.client.i18nNames.desc");
    private static final String spawnCheckRetriesDesc = I18n.translateToLocal("config.supermobtracker.client.spawnCheckRetries.desc");
    private static final String trackedEntityIdsDesc = I18n.translateToLocal("config.supermobtracker.client.trackedEntityIds.desc");
    private static final String lastSelectedEntityDesc = I18n.translateToLocal("config.supermobtracker.client.lastSelectedEntity.desc");
    private static final String filterTextDesc = I18n.translateToLocal("config.supermobtracker.client.filterText.desc");
    private static final String hudPositionDesc = I18n.translateToLocal("config.supermobtracker.client.hudPosition.desc");
    private static final String hudPaddingExternalDesc = I18n.translateToLocal("config.supermobtracker.client.hudPaddingExternal.desc");
    private static final String hudPaddingInternalDesc = I18n.translateToLocal("config.supermobtracker.client.hudPaddingInternal.desc");
    private static final String hudLineSpacingDesc = I18n.translateToLocal("config.supermobtracker.client.hudLineSpacing.desc");
    private static final String hudEnabledDesc = I18n.translateToLocal("config.supermobtracker.client.hudEnabled.desc");

    private static final List<String> hiddenConfigs = Arrays.asList(
        "i18nNames",
        "trackedEntityIds",
        "lastSelectedEntity",
        "filterText",
        "hudPosition"
    );

    public static void loadConfigs(File configFile) {
        if (config == null) config = new Configuration(configFile);

        syncFromFile();
    }

    public static void syncFromFile() {
        config.load();
        syncFromConfig();
    }

    /**
     * Sync static fields from the in-memory Configuration object.
     * Unlike syncFromFile(), this does not reload from disk.
     */
    public static void syncFromConfig() {
        Property prop;

        // Client settings
        prop = config.get("client", "enableTracking", clientEnableTracking, enableTrackingDesc);
        prop.setLanguageKey("config.supermobtracker.client.enableTracking");
        clientEnableTracking = prop.getBoolean();

        prop = config.get("client", "detectionRange", (float) clientDetectionRange, detectionRangeDesc, 8f, 256f);
        prop.setLanguageKey("config.supermobtracker.client.detectionRange");
        clientDetectionRange = prop.getDouble();

        prop = config.get("client", "spawnCheckRetries", clientSpawnCheckRetries, spawnCheckRetriesDesc, 1, 1000);
        prop.setLanguageKey("config.supermobtracker.client.spawnCheckRetries");
        clientSpawnCheckRetries = prop.getInt();

        prop = config.get("client", "hudPosition", clientHudPosition.name(), hudPositionDesc);
        prop.setLanguageKey("config.supermobtracker.client.hudPosition");
        String hudPosStr = prop.getString();
        try {
            clientHudPosition = HudPosition.valueOf(hudPosStr);
        } catch (IllegalArgumentException e) {
            clientHudPosition = HudPosition.TOP_LEFT;
        }

        prop = config.get("client", "hudPaddingExternal", clientHudPaddingExternal, hudPaddingExternalDesc, 0, 100);
        prop.setLanguageKey("config.supermobtracker.client.hudPaddingExternal");
        clientHudPaddingExternal = prop.getInt();

        prop = config.get("client", "hudPaddingInternal", clientHudPaddingInternal, hudPaddingInternalDesc, 0, 50);
        prop.setLanguageKey("config.supermobtracker.client.hudPaddingInternal");
        clientHudPaddingInternal = prop.getInt();

        prop = config.get("client", "hudLineSpacing", clientHudLineSpacing, hudLineSpacingDesc, 0, 20);
        prop.setLanguageKey("config.supermobtracker.client.hudLineSpacing");
        clientHudLineSpacing = prop.getInt();

        prop = config.get("client", "hudEnabled", clientHudEnabled, hudEnabledDesc);
        prop.setLanguageKey("config.supermobtracker.client.hudEnabled");
        clientHudEnabled = prop.getBoolean();

        // TODO: add mob blacklist/whitelist here (whitelist takes priority over blacklist)

        // Hidden configs (still set language keys for consistency)
        prop = config.get("client", "i18nNames", clientI18nNames, i18nNamesDesc);
        prop.setLanguageKey("config.supermobtracker.client.i18nNames");
        clientI18nNames = prop.getBoolean();

        prop = config.get("client", "lastSelectedEntity", clientLastSelectedEntity, lastSelectedEntityDesc);
        prop.setLanguageKey("config.supermobtracker.client.lastSelectedEntity");
        clientLastSelectedEntity = prop.getString();

        prop = config.get("client", "filterText", clientFilterText, filterTextDesc);
        prop.setLanguageKey("config.supermobtracker.client.filterText");
        clientFilterText = prop.getString();

        prop = config.get("client", "trackedEntityIds", new String[0], trackedEntityIdsDesc);
        prop.setLanguageKey("config.supermobtracker.client.trackedEntityIds");
        clientTrackedEntityIds = new ArrayList<>();
        for (String s : prop.getStringList()) {
            if (s != null && !s.trim().isEmpty()) clientTrackedEntityIds.add(s.trim());
        }

        if (config.hasChanged()) config.save();
    }

    public static Configuration getConfig() {
        return config;
    }

    public static boolean isConfigHidden(String name) {
        return hiddenConfigs.contains(name);
    }

    public static void setClientI18nNames(boolean value) {
        clientI18nNames = value;
        if (config != null) {
            config.get("client", "i18nNames", clientI18nNames).set(value);
            config.save();
        }
    }

    public static List<String> getClientTrackedIds() {
        return new ArrayList<>(clientTrackedEntityIds);
    }

    public static void setClientTrackedIds(Collection<String> ids) {
        clientTrackedEntityIds = new ArrayList<>(ids);
        if (config != null) {
            config.get("client", "trackedEntityIds", new String[0]).set(clientTrackedEntityIds.toArray(new String[0]));
            config.save();
        }
    }

    public static String getClientLastSelectedEntity() {
        return clientLastSelectedEntity;
    }

    public static void setClientLastSelectedEntity(String entityId) {
        clientLastSelectedEntity = entityId != null ? entityId : "";
        if (config != null) {
            config.get("client", "lastSelectedEntity", "").set(clientLastSelectedEntity);
            config.save();
        }
    }

    public static String getClientFilterText() {
        return clientFilterText;
    }

    public static void setClientFilterText(String text) {
        clientFilterText = text != null ? text : "";
        if (config != null) {
            config.get("client", "filterText", "").set(clientFilterText);
            config.save();
        }
    }

    public static HudPosition getClientHudPosition() {
        return clientHudPosition;
    }

    public static void setClientHudPosition(HudPosition position) {
        clientHudPosition = position != null ? position : HudPosition.TOP_LEFT;
        if (config != null) {
            config.get("client", "hudPosition", HudPosition.TOP_LEFT.name()).set(clientHudPosition.name());
            config.save();
        }
    }

    public static int getClientHudPaddingExternal() {
        return clientHudPaddingExternal;
    }

    public static int getClientHudPaddingInternal() {
        return clientHudPaddingInternal;
    }

    public static int getClientHudLineSpacing() {
        return clientHudLineSpacing;
    }

    public static boolean isClientHudEnabled() {
        return clientHudEnabled;
    }
}
