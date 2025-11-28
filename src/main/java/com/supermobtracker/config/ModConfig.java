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

    public static boolean serverEnableTracking = true; // can disable tracking server-wide
    public static double clientDetectionRange = 64.0; // range for spawn event consideration client side
    public static boolean clientI18nNames = true; // localize names in UI/HUD
    public static List<String> clientTrackedEntityIds = new ArrayList<>();
    public static String clientLastSelectedEntity = ""; // last selected entity in mob tracker GUI

    private static final String enableTrackingDesc = I18n.translateToLocal("config.supermobtracker.server.enableTracking.desc");
    private static final String detectionRangeDesc = I18n.translateToLocal("config.supermobtracker.client.detectionRange.desc");
    private static final String i18nNamesDesc = I18n.translateToLocal("config.supermobtracker.client.i18nNames.desc");
    private static final String trackedEntityIdsDesc = I18n.translateToLocal("config.supermobtracker.client.trackedEntityIds.desc");
    private static final String lastSelectedEntityDesc = I18n.translateToLocal("config.supermobtracker.client.lastSelectedEntity.desc");

    private static final List<String> hiddenConfigs = Arrays.asList(
        "i18nNames",
        "trackedEntityIds",
        "lastSelectedEntity"
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
        clientI18nNames = config.getBoolean("i18nNames", "client", clientI18nNames, i18nNamesDesc);
        clientLastSelectedEntity = config.getString("lastSelectedEntity", "client", clientLastSelectedEntity, lastSelectedEntityDesc);

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
}
