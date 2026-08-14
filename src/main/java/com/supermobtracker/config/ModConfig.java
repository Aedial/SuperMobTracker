package com.supermobtracker.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import com.supermobtracker.Tags;


@Config(modid = Tags.MODID, name = Tags.MODID, category = "client")
@Config.LangKey("config.supermobtracker.client")
public class ModConfig {
    private static final String PREFIX = "config.supermobtracker.client.";

    private static final String[] DEFAULT_UNSTABLE_SIMULATION_ENTITIES = {
        "minecraft:ender_dragon",
        "draconicevolution:chaosguardian"
    };

    private static final String[] DEFAULT_SHOULD_RENDER_ENTITIES = {
        "extrabotany:gaiaiii"
    };

    private static File configPath;

    // HUD position enum
    public enum HudPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    @Config.Name("enableTracking")
    @Config.LangKey(PREFIX + "enableTracking")
    @Config.Comment("Enable tracking of mobs. Requires a Minecraft restart.")
    public static boolean clientEnableTracking = true;

    @Config.Name("detectionRange")
    @Config.LangKey(PREFIX + "detectionRange")
    @Config.Comment("Range in blocks to detect tracked mobs for the x-ray or glow highlight effect.")
    @Config.RangeDouble(min = 8.0, max = 256.0)
    public static double clientDetectionRange = 64.0;

    @Config.Name("useModelXRay")
    @Config.LangKey(PREFIX + "useModelXRay")
    @Config.Comment("Render tracked mobs through walls with their normal model instead of using the vanilla glow outline.")
    public static boolean clientUseModelXRay = true;

    @Config.Name("i18nNames")
    @Config.LangKey(PREFIX + "i18nNames")
    @Config.Comment("Use localized names in the tracker GUI.")
    public static boolean clientI18nNames = true;

    @Config.Name("spawnCheckRetries")
    @Config.LangKey(PREFIX + "spawnCheckRetries")
    @Config.Comment("Maximum retries for spawn condition checks. Higher values handle random spawn conditions better but increase analysis time.")
    @Config.RangeInt(min = 1, max = 10000)
    public static int clientSpawnCheckRetries = 100;

    @Config.Name("trackedEntityIds")
    @Config.LangKey(PREFIX + "trackedEntityIds")
    @Config.Comment("List of entity IDs currently being tracked.")
    public static String[] clientTrackedEntityIds = new String[0];

    @Config.Name("lastSelectedEntity")
    @Config.LangKey(PREFIX + "lastSelectedEntity")
    @Config.Comment("Last selected entity in the mob tracker GUI.")
    public static String clientLastSelectedEntity = "";

    @Config.Name("filterText")
    @Config.LangKey(PREFIX + "filterText")
    @Config.Comment("Last filter text in the mob tracker GUI.")
    public static String clientFilterText = "";

    @Config.Name("hudPosition")
    @Config.LangKey(PREFIX + "hudPosition")
    @Config.Comment("Position of the tracking overlay on screen.")
    public static String clientHudPosition = HudPosition.TOP_LEFT.name();

    @Config.Name("hudPaddingExternal")
    @Config.LangKey(PREFIX + "hudPaddingExternal")
    @Config.Comment("Padding from screen edge for the tracking overlay.")
    @Config.RangeInt(min = 0, max = 100)
    public static int clientHudPaddingExternal = 4;

    @Config.Name("hudPaddingInternal")
    @Config.LangKey(PREFIX + "hudPaddingInternal")
    @Config.Comment("Padding inside the tracking overlay box.")
    @Config.RangeInt(min = 0, max = 50)
    public static int clientHudPaddingInternal = 2;

    @Config.Name("hudLineSpacing")
    @Config.LangKey(PREFIX + "hudLineSpacing")
    @Config.Comment("Spacing between lines in the tracking overlay.")
    @Config.RangeInt(min = 0, max = 20)
    public static int clientHudLineSpacing = 2;

    @Config.Name("hudEnabled")
    @Config.LangKey(PREFIX + "hudEnabled")
    @Config.Comment("Whether to show the tracking overlay on screen.")
    public static boolean clientHudEnabled = true;

    @Config.Name("mobWhitelist")
    @Config.LangKey(PREFIX + "mobWhitelist")
    @Config.Comment("List of entity IDs that are whitelisted for tracking. The whitelist takes precedence over the blacklist.")
    public static String[] clientMobWhitelist = new String[0];

    @Config.Name("mobBlacklist")
    @Config.LangKey(PREFIX + "mobBlacklist")
    @Config.Comment("List of entity IDs that are blacklisted from tracking.")
    public static String[] clientMobBlacklist = new String[0];

    @Config.Name("dropSimulationCount")
    @Config.LangKey(PREFIX + "dropSimulationCount")
    @Config.Comment("Number of simulated kills for calculating drop rates.")
    @Config.RangeInt(min = 100, max = 100000)
    public static int clientDropSimulationCount = 10000;

    @Config.Name("unstableSimulationEntities")
    @Config.LangKey(PREFIX + "unstableSimulationEntities")
    @Config.Comment({
        "Entity IDs that corrupt global state during drop simulation and should be excluded from simulation.",
        "These entities can be identified by severe performance degradation for every entity after simulating their drops."
    })
    public static String[] clientUnstableSimulationEntities = DEFAULT_UNSTABLE_SIMULATION_ENTITIES.clone();

    @Config.Name("shouldRenderEntities")
    @Config.LangKey(PREFIX + "shouldRenderEntities")
    @Config.Comment("Entity IDs that crash or spam errors when rendered. These entities will not render at all in the preview or gallery.")
    public static String[] clientShouldRenderEntities = DEFAULT_SHOULD_RENDER_ENTITIES.clone();

    private static final List<String> hiddenConfigs = Arrays.asList(
        "i18nNames",
        "trackedEntityIds",
        "lastSelectedEntity",
        "filterText",
        "hudPosition"
    );

    public static void loadConfigs(File configFile) {
        configPath = configFile;
        syncManagedConfig();
    }

    public static void syncFromFile() {
        syncManagedConfig();
    }

    /**
     * Sync static fields from Forge's managed @Config instance.
     */
    public static void syncFromConfig() {
        syncManagedConfig();
    }

    public static File getConfigPath() {
        return configPath;
    }

    public static File getSupportDirectory() {
        File baseDir = configPath != null && configPath.getParentFile() != null
            ? configPath.getParentFile()
            : new File("config");
        File supportDir = new File(baseDir, Tags.MODID);

        if (!supportDir.exists()) supportDir.mkdirs();

        return supportDir;
    }

    public static boolean isConfigHidden(String name) {
        return hiddenConfigs.contains(name);
    }

    public enum FilterReason {
        NONE,
        NOT_WHITELISTED,
        BLACKLISTED
    }

    public static boolean isWhitelistActive() {
        return clientMobWhitelist.length > 0;
    }

    public static boolean isWhitelisted(String id) {
        return matchesConfiguredId(clientMobWhitelist, id);
    }

    public static boolean isBlacklisted(String id) {
        return matchesConfiguredId(clientMobBlacklist, id);
    }

    public static boolean isEntityAllowed(String id) {
        if (id == null) return false;

        if (isWhitelistActive()) return isWhitelisted(id);
        if (isBlacklisted(id)) return false;

        return true;
    }

    public static FilterReason getFilterReason(String id) {
        if (id == null) return FilterReason.NONE;

        if (isWhitelistActive()) return isWhitelisted(id) ? FilterReason.NONE : FilterReason.NOT_WHITELISTED;

        return isBlacklisted(id) ? FilterReason.BLACKLISTED : FilterReason.NONE;
    }

    public static void setClientI18nNames(boolean value) {
        if (clientI18nNames == value) return;

        clientI18nNames = value;
        syncManagedConfig();
    }

    public static List<String> getClientMobWhitelist() {
        return toMutableList(clientMobWhitelist);
    }

    public static void setClientMobWhitelist(Collection<String> ids) {
        String[] newValue = normalizeStringCollection(ids);
        if (Arrays.equals(clientMobWhitelist, newValue)) return;

        clientMobWhitelist = newValue;
        syncManagedConfig();
    }

    public static List<String> getClientMobBlacklist() {
        return toMutableList(clientMobBlacklist);
    }

    public static void setClientMobBlacklist(Collection<String> ids) {
        String[] newValue = normalizeStringCollection(ids);
        if (Arrays.equals(clientMobBlacklist, newValue)) return;

        clientMobBlacklist = newValue;
        syncManagedConfig();
    }

    public static List<String> getClientTrackedIds() {
        return toMutableList(clientTrackedEntityIds);
    }

    public static void setClientTrackedIds(Collection<String> ids) {
        String[] newValue = normalizeStringCollection(ids);
        if (Arrays.equals(clientTrackedEntityIds, newValue)) return;

        clientTrackedEntityIds = newValue;
        syncManagedConfig();
    }

    public static String getClientLastSelectedEntity() {
        return clientLastSelectedEntity;
    }

    public static void setClientLastSelectedEntity(String entityId) {
        String newValue = normalizeStringValue(entityId);
        if (clientLastSelectedEntity.equals(newValue)) return;

        clientLastSelectedEntity = newValue;
        syncManagedConfig();
    }

    public static String getClientFilterText() {
        return clientFilterText;
    }

    public static void setClientFilterText(String text) {
        String newValue = normalizeStringValue(text);
        if (clientFilterText.equals(newValue)) return;

        clientFilterText = newValue;
        syncManagedConfig();
    }

    public static HudPosition getClientHudPosition() {
        return resolveHudPosition(clientHudPosition);
    }

    public static void setClientHudPosition(HudPosition position) {
        String newValue = position != null ? position.name() : HudPosition.TOP_LEFT.name();
        if (newValue.equals(clientHudPosition)) return;

        clientHudPosition = newValue;
        syncManagedConfig();
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

    public static boolean isClientUseModelXRay() {
        return clientUseModelXRay;
    }

    public static void setClientUseModelXRay(boolean value) {
        if (clientUseModelXRay == value) return;

        clientUseModelXRay = value;
        syncManagedConfig();
    }

    public static List<String> getClientUnstableSimulationEntities() {
        return toMutableList(clientUnstableSimulationEntities);
    }

    public static void setClientUnstableSimulationEntities(Collection<String> ids) {
        String[] newValue = normalizeStringCollection(ids);
        if (Arrays.equals(clientUnstableSimulationEntities, newValue)) return;

        clientUnstableSimulationEntities = newValue;
        syncManagedConfig();
    }

    public static boolean isUnstableSimulationEntity(String id) {
        return matchesConfiguredId(clientUnstableSimulationEntities, id);
    }

    public static List<String> getClientShouldRenderEntities() {
        return toMutableList(clientShouldRenderEntities);
    }

    public static void setClientShouldRenderEntities(Collection<String> ids) {
        String[] newValue = normalizeStringCollection(ids);
        if (Arrays.equals(clientShouldRenderEntities, newValue)) return;

        clientShouldRenderEntities = newValue;
        syncManagedConfig();
    }

    public static boolean shouldRenderEntity(String id) {
        return matchesConfiguredId(clientShouldRenderEntities, id);
    }

    // Trim persisted string arrays after load so blank GUI entries do not turn the substring-based checks into match-all filters.
    private static boolean normalizeClientValues() {
        boolean changed = false;

        String normalizedLastSelectedEntity = normalizeStringValue(clientLastSelectedEntity);
        if (!normalizedLastSelectedEntity.equals(clientLastSelectedEntity)) {
            clientLastSelectedEntity = normalizedLastSelectedEntity;
            changed = true;
        }

        String normalizedFilterText = normalizeStringValue(clientFilterText);
        if (!normalizedFilterText.equals(clientFilterText)) {
            clientFilterText = normalizedFilterText;
            changed = true;
        }

        String normalizedHudPosition = normalizeHudPositionName(clientHudPosition);
        if (!normalizedHudPosition.equals(clientHudPosition)) {
            clientHudPosition = normalizedHudPosition;
            changed = true;
        }

        String[] normalizedTrackedIds = normalizeStringArray(clientTrackedEntityIds);
        if (!Arrays.equals(clientTrackedEntityIds, normalizedTrackedIds)) {
            clientTrackedEntityIds = normalizedTrackedIds;
            changed = true;
        }

        String[] normalizedWhitelist = normalizeStringArray(clientMobWhitelist);
        if (!Arrays.equals(clientMobWhitelist, normalizedWhitelist)) {
            clientMobWhitelist = normalizedWhitelist;
            changed = true;
        }

        String[] normalizedBlacklist = normalizeStringArray(clientMobBlacklist);
        if (!Arrays.equals(clientMobBlacklist, normalizedBlacklist)) {
            clientMobBlacklist = normalizedBlacklist;
            changed = true;
        }

        String[] normalizedUnstableEntities = normalizeStringArray(clientUnstableSimulationEntities);
        if (!Arrays.equals(clientUnstableSimulationEntities, normalizedUnstableEntities)) {
            clientUnstableSimulationEntities = normalizedUnstableEntities;
            changed = true;
        }

        String[] normalizedRenderEntities = normalizeStringArray(clientShouldRenderEntities);
        if (!Arrays.equals(clientShouldRenderEntities, normalizedRenderEntities)) {
            clientShouldRenderEntities = normalizedRenderEntities;
            changed = true;
        }

        return changed;
    }

    private static boolean matchesConfiguredId(String[] configuredIds, String id) {
        if (id == null || configuredIds == null || configuredIds.length == 0) return false;

        for (String entry : configuredIds) {
            if (id.contains(entry)) return true;
        }

        return false;
    }

    private static String normalizeHudPositionName(String value) {
        return resolveHudPosition(value).name();
    }

    private static String normalizeStringValue(String value) {
        return value != null ? value : "";
    }

    private static String[] normalizeStringCollection(Collection<String> values) {
        if (values == null || values.isEmpty()) return new String[0];

        return normalizeStringArray(values.toArray(new String[0]));
    }

    private static String[] normalizeStringArray(String[] values) {
        if (values == null || values.length == 0) return new String[0];

        List<String> normalized = new ArrayList<>();

        for (String value : values) {
            if (value == null) continue;

            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;

            normalized.add(trimmed);
        }

        return normalized.toArray(new String[0]);
    }

    private static HudPosition resolveHudPosition(String value) {
        if (value == null || value.isEmpty()) return HudPosition.TOP_LEFT;

        for (HudPosition position : HudPosition.values()) {
            if (position.name().equalsIgnoreCase(value)) return position;
        }

        return HudPosition.TOP_LEFT;
    }

    private static void syncManagedConfig() {
        ConfigManager.sync(Tags.MODID, Config.Type.INSTANCE);

        if (normalizeClientValues()) ConfigManager.sync(Tags.MODID, Config.Type.INSTANCE);
    }

    private static List<String> toMutableList(String[] values) {
        if (values == null || values.length == 0) return new ArrayList<>();

        return new ArrayList<>(Arrays.asList(values));
    }

    @Mod.EventBusSubscriber(modid = Tags.MODID, value = Side.CLIENT)
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (!Tags.MODID.equals(event.getModID())) return;

            syncManagedConfig();
        }
    }
}
