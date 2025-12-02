package com.supermobtracker.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.File;
import java.util.List;
import java.util.Collection;

import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.common.config.Configuration;

public class ModConfig {
    private static Configuration config;

    // HUD position enum
    public enum HudPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    public static boolean serverEnableTracking = true; // can disable tracking server-wide
    public static double clientDetectionRange = 64.0; // range for spawn event consideration client side
    public static boolean clientI18nNames = true; // localize names in UI/HUD
    public static int clientSpawnCheckRetries = 100; // retry count for random spawn checks
    public static List<String> clientTrackedEntityIds = new ArrayList<>();
    public static String clientLastSelectedEntity = ""; // last selected entity in mob tracker GUI
    public static String clientFilterText = ""; // last filter text in mob tracker GUI
    public static HudPosition clientHudPosition = HudPosition.TOP_LEFT; // HUD overlay position
    public static int clientHudPaddingExternal = 4; // padding from screen edge
    public static int clientHudPaddingInternal = 4; // padding inside the box
    public static int clientHudLineSpacing = 2; // spacing between lines

    private static final String enableTrackingDesc = I18n.translateToLocal("config.supermobtracker.server.enableTracking.desc");
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

        // Server settings
        serverEnableTracking = config.getBoolean("enableTracking", "server", serverEnableTracking, enableTrackingDesc);

        // Client settings
        clientDetectionRange = config.getFloat("detectionRange", "client", (float) clientDetectionRange, 8f, 256f, detectionRangeDesc);
        clientSpawnCheckRetries = config.getInt("spawnCheckRetries", "client", clientSpawnCheckRetries, 1, 1000, spawnCheckRetriesDesc);

        String hudPosStr = config.getString("hudPosition", "client", clientHudPosition.name(), hudPositionDesc);
        try {
            clientHudPosition = HudPosition.valueOf(hudPosStr);
        } catch (IllegalArgumentException e) {
            clientHudPosition = HudPosition.TOP_LEFT;
        }
        clientHudPaddingExternal = config.getInt("hudPaddingExternal", "client", clientHudPaddingExternal, 0, 100, hudPaddingExternalDesc);
        clientHudPaddingInternal = config.getInt("hudPaddingInternal", "client", clientHudPaddingInternal, 0, 50, hudPaddingInternalDesc);
        clientHudLineSpacing = config.getInt("hudLineSpacing", "client", clientHudLineSpacing, 0, 20, hudLineSpacingDesc);

        // TODO: add a config to hide HUD entirely?

        // Hidden configs
        clientI18nNames = config.getBoolean("i18nNames", "client", clientI18nNames, i18nNamesDesc);
        clientLastSelectedEntity = config.getString("lastSelectedEntity", "client", clientLastSelectedEntity, lastSelectedEntityDesc);
        clientFilterText = config.getString("filterText", "client", clientFilterText, filterTextDesc);

        clientTrackedEntityIds = new ArrayList<>();
        for (String s : config.getStringList("trackedEntityIds", "client", new String[0], trackedEntityIdsDesc)) {
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
}
