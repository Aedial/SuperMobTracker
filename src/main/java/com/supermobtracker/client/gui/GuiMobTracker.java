package com.supermobtracker.client.gui;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Vector;
import java.util.stream.Collectors;

import org.lwjgl.input.Mouse;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityHanging;
import net.minecraft.util.ResourceLocation;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.client.ClientSettings;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.spawn.ConditionUtils;
import com.supermobtracker.spawn.SpawnConditionAnalyzer;
import com.supermobtracker.tracking.SpawnTrackerManager;
import com.supermobtracker.util.JEIHelper;
import com.supermobtracker.util.Utils;


public class GuiMobTracker extends GuiScreen {
    private GuiTextField filterField;
    private MobListWidget listWidget;
    private ResourceLocation selected;
    private SpawnConditionAnalyzer analyzer = new SpawnConditionAnalyzer();
    private SpawnConditionAnalyzer.SpawnConditions spawnConditions;
    private long lastClickTime = 0L;
    private ResourceLocation lastClickId = null;

    // JEI button bounds (updated during draw)
    private int jeiButtonX, jeiButtonY, jeiButtonW, jeiButtonH;
    private boolean jeiButtonVisible = false;

    // Panel bounds for text clipping
    private int panelMaxY = Integer.MAX_VALUE;

    private static final int entityBgColor = 0xFF404040;
    private static final int entityBorderColor = 0xFF808080;
    private static final int lightColor = 0xFFFFAA;
    private static final int ylevelColor = 0xAAAAFF;
    private static final int groundColor = 0xAAFFAA;
    private static final int timeOfDayColor = 0xFFAAFF;
    private static final int weatherColor = 0xFFAACC;   // TODO: change color for weather, perhaps blueish
    private static final int biomeColor = 0xAADDFF;
    private static final int hintColor = 0xFFAAAA;

    private String getI18nButtonString() {
        return ClientSettings.i18nNames ? I18n.format("gui.supermobtracker.i18nIDs.on") : I18n.format("gui.supermobtracker.i18nIDs.off");
    }

    @Override
    public void initGui() {
        int leftWidth = Math.min(width / 2, 250);
        filterField = new GuiTextField(0, fontRenderer, 10, 10, leftWidth - 20, 14);
        listWidget = new MobListWidget(10, 30, leftWidth - 20, height - 40, fontRenderer, this);
        this.buttonList.clear();

        this.buttonList.add(new GuiButton(1, leftWidth + 10, height - 30, 80, 20, getI18nButtonString()));

        // Restore last selected entity
        String lastEntity = ModConfig.getClientLastSelectedEntity();
        if (lastEntity != null && !lastEntity.isEmpty()) {
            ResourceLocation lastId = new ResourceLocation(lastEntity);
            if (analyzer.getEntityInstance(lastId) != null) {
                selectEntity(lastId);
                listWidget.ensureVisible(lastId);
            }
        }
    }

    public void selectEntity(ResourceLocation id) {
        this.selected = id;
        this.spawnConditions = analyzer.analyze(id);
        ModConfig.setClientLastSelectedEntity(id != null ? id.toString() : "");
    }

    public ResourceLocation getSelectedEntity() {
        return this.selected;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (selected == null) return;

        if (button.id == 1) {
            ClientSettings.toggleI18n();
            button.displayString = getI18nButtonString();
        }
    }

    @Override
    public void updateScreen() {
        filterField.updateCursorCounter();
        listWidget.setFilter(filterField.getText());
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (filterField.textboxKeyTyped(typedChar, keyCode)) return;
        if (listWidget.handleKey(keyCode)) return;

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        filterField.mouseClicked(mouseX, mouseY, mouseButton);  // TODO: right click to clear

        // Handle JEI button click
        if (jeiButtonVisible && selected != null && mouseButton == 0 &&
            mouseX >= jeiButtonX && mouseX <= jeiButtonX + jeiButtonW &&
            mouseY >= jeiButtonY && mouseY <= jeiButtonY + jeiButtonH) {
            JEIHelper.showMobPage(selected);

            return;
        }

        ResourceLocation click = listWidget.handleClick(mouseX, mouseY);

        if (click != null) {
            long now = Minecraft.getSystemTime();
            boolean isDouble = click.equals(lastClickId) && (now - lastClickTime) < 500L;
            lastClickId = click;
            lastClickTime = now;
            selectEntity(click);

            if (isDouble) {
                SpawnTrackerManager.toggleTracked(click);
                ModConfig.setClientTrackedIds(SpawnTrackerManager.getTrackedIdStrings());
                SpawnTrackerManager.scanWorld(mc.world);
                listWidget.rebuildFiltered();
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (listWidget != null) listWidget.onMouseDrag(mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (listWidget != null) listWidget.onMouseRelease();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getDWheel();
        if (wheel != 0) listWidget.scroll(wheel < 0 ? 1 : -1);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        filterField.drawTextBox();
        listWidget.draw();
        drawRightPanel(mouseX, mouseY, partialTicks);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private int drawElidedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        if (y + lineHeight > panelMaxY) return y;

        String elided = renderer.trimStringToWidth(text, maxWidth - renderer.getStringWidth("…"));
        if (!elided.equals(text)) elided += "…";

        renderer.drawString(elided, x, y, color);

        return y + lineHeight;
    }

    // TODO: separate internal line height and external spacing
    private int drawWrappedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        List<String> wrapped = Utils.wrapText(renderer, text, maxWidth);
        if (y + lineHeight * wrapped.size() > panelMaxY) return y;

        for (String line : wrapped) {
            renderer.drawString(line, x, y, color);
            y += lineHeight;
        }

        return y;
    }

    private int drawSingleString(FontRenderer renderer, String text, int x, int y, int lineHeight, int color) {
        if (y + lineHeight > panelMaxY) return y;

        renderer.drawString(text, x, y, color);

        return y + lineHeight;
    }

    private String format(double d) {
        String s = String.format("%.1f", d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);

        return s;
    }

    private void drawRightPanel(int mouseX, int mouseY, float partialTicks) {
        int leftWidth = Math.min(width / 2, 250);
        int panelX = leftWidth + 10;
        int panelY = 10;
        int panelW = width - panelX - 20;
        int panelH = height - 40;
        panelMaxY = panelY + panelH - 6;

        String sep = I18n.format("gui.mobtracker.separator");

        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0x80000000);
        if (selected != null) {
            int textX = panelX + 6;
            int textY = panelY + 6;
            int textW = panelW - 12;
            int color = 0xFFFFFF;

            // preview: up to a quarter of panel height, slowly rotating (full rotation every 10s)
            int previewSizeLocal = Math.min(textW, panelH / 4);
            int previewX = textX + (textW - previewSizeLocal) / 2;
            int previewY = textY;
            int previewSize = previewSizeLocal;
            float previewRotationY = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;

            drawMobPreview(selected, previewX, previewY, previewSize, previewRotationY);

            textY += previewSize + 16;

            // TODO: some mobs just cannot spawn naturally, how do we check that (is that all bosses)?
            // We should indicate it here, instead of showing spawn conditions that don't apply.
            ArrayList<String> attributes = new ArrayList<>();
            if (analyzer.isBoss(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.boss"));
            if (analyzer.cannotDespawn(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.cannotDespawn"));
            if (analyzer.isPassive(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.passive"));
            if (analyzer.isNeutral(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.neutral"));
            if (analyzer.isHostile(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.hostile"));
            if (analyzer.isAquatic(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.aquatic"));
            if (analyzer.isFlying(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.flying"));
            if (analyzer.isTameable(selected)) attributes.add(I18n.format("gui.mobtracker.attribute.tameable"));

            String name = I18n.format("gui.mobtracker.entityName", formatEntityName(selected, true));
            textY = drawElidedString(fontRenderer, name, textX, textY, 14, textW, color);

            String entityIdStr = I18n.format("gui.mobtracker.entityId", selected.toString());
            textY = drawElidedString(fontRenderer, entityIdStr, textX, textY, 14, textW, color);

            Vec3d size = analyzer.getEntitySize(selected);
            String sizeStr = I18n.format("gui.mobtracker.entitySize", format(size.x), format(size.y), format(size.z));
            textY = drawElidedString(fontRenderer, sizeStr, textX, textY, 14, textW, color);

            String attributeString = I18n.format("gui.mobtracker.attributes", String.join(sep, attributes));
            textY = drawWrappedString(fontRenderer, attributeString, textX, textY, 14, textW, color);

            // JEI button (only if JEI is loaded and can show mob info)
            jeiButtonVisible = false;
            if (JEIHelper.canShowMobPage(selected)) {
                String jeiText = I18n.format("gui.mobtracker.jeiButton");
                jeiButtonW = fontRenderer.getStringWidth(jeiText) + 8;
                jeiButtonH = 12;
                jeiButtonX = textX;
                jeiButtonY = textY;
                jeiButtonVisible = true;

                boolean hovered = mouseX >= jeiButtonX && mouseX <= jeiButtonX + jeiButtonW &&
                                  mouseY >= jeiButtonY && mouseY <= jeiButtonY + jeiButtonH;
                int btnBg = hovered ? 0x60FFFFFF : 0x40FFFFFF;
                int btnColor = hovered ? 0xFFFFAA : 0xCCCCCC;

                drawRect(jeiButtonX, jeiButtonY, jeiButtonX + jeiButtonW, jeiButtonY + jeiButtonH, btnBg);
                fontRenderer.drawString(jeiText, jeiButtonX + 4, jeiButtonY + 2, btnColor);
                textY += jeiButtonH + 4;
            }

            if (spawnConditions == null) {
                List<String> hints = analyzer.getErrorHints();
                if (hints.isEmpty()) {
                    textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.noSpawnData"), textX, textY, 12, textW, color);
                } else {
                    for (String hint : hints) {
                        textY = drawWrappedString(fontRenderer, hint, textX, textY, 12, textW, 0xFFAAAA);
                    }
                }
            } else {
                textY += 20;

                textY = drawSingleString(fontRenderer, I18n.format("gui.mobtracker.spawnConditions"), textX, textY, 12, color);

                int condsX = textX + 6;

                String lightLevels = I18n.format("gui.mobtracker.lightLevels", Utils.formatRangeFromList(spawnConditions.lightLevels, sep));
                textY = drawWrappedString(fontRenderer, lightLevels, condsX, textY, 12, textW, lightColor);

                String yPos = I18n.format("gui.mobtracker.yPos", Utils.formatRangeFromList(spawnConditions.yLevels, sep));
                textY = drawWrappedString(fontRenderer, yPos, condsX, textY, 12, textW, ylevelColor);

                // FIXME: translate ground blocks. That one is tricky.
                String groundBlocks = I18n.format("gui.mobtracker.groundBlocks", String.join(sep, spawnConditions.groundBlocks));
                textY = drawWrappedString(fontRenderer, groundBlocks, condsX, textY, 12, textW, groundColor);

                String timeOfDayTL = String.join(sep, ConditionUtils.translateList(spawnConditions.timeOfDay, "gui.mobtracker.timeOfDay"));
                String timeOfDay = I18n.format("gui.mobtracker.timeOfDay", timeOfDayTL);
                textY = drawWrappedString(fontRenderer, timeOfDay, condsX, textY, 12, textW, timeOfDayColor);

                String weatherTL = String.join(sep, ConditionUtils.translateList(spawnConditions.weather, "gui.mobtracker.weather"));
                String weather = I18n.format("gui.mobtracker.weather", weatherTL);
                textY = drawWrappedString(fontRenderer, weather, condsX, textY, 12, textW, weatherColor);

                int biomesCount = spawnConditions.biomes.size();
                boolean hasBiomes = biomesCount > 0;
                boolean isUnknownBiome = biomesCount == 1 && spawnConditions.biomes.get(0).equals("unknown");
                boolean isAnyBiome = biomesCount == 1 && spawnConditions.biomes.get(0).equals("any");

                String biomesLabel;
                if (!hasBiomes) {
                    biomesLabel = I18n.format("gui.mobtracker.biomes.none");
                } else if (isUnknownBiome) {
                    biomesLabel = I18n.format("gui.mobtracker.biomes.unknown");
                } else if (isAnyBiome) {
                    biomesLabel = I18n.format("gui.mobtracker.biomes.any");
                } else {
                    biomesLabel = I18n.format("gui.mobtracker.biomes", spawnConditions.biomes.size());
                }

                int biomesLabelY = textY;
                textY = drawSingleString(fontRenderer, biomesLabel, condsX, textY, 12, biomeColor);

                boolean showTooltip = textY > biomesLabelY && hasBiomes && !isUnknownBiome && !isAnyBiome &&
                    mouseX >= condsX && mouseX <= condsX + fontRenderer.getStringWidth(biomesLabel) &&
                    mouseY >= biomesLabelY && mouseY <= biomesLabelY + 12;
                if (showTooltip) {
                    // TODO: give a darkened background box with border for better readability
                    // TODO: sort biomes (prioritize minecraft: ones?)
                    int maxWidth = Math.min(300, spawnConditions.biomes.stream().mapToInt(s -> fontRenderer.getStringWidth(s)).max().orElse(100) + 8);
                    int boxX = Math.min(mouseX + 12, panelX + panelW - maxWidth - 4);
                    int boxY = Math.min(mouseY + 12, panelY + panelH - 8 - Math.min(spawnConditions.biomes.size(), 20) * 10);
                    int lines = Math.min(spawnConditions.biomes.size(), 20);
                    drawRect(boxX - 2, boxY - 2, boxX + maxWidth + 2, boxY + lines * 10 + 2, 0xC0000000);
                    for (int i = 0; i < lines; i++) {
                        // FIXME: how to translate biome names properly?
                        fontRenderer.drawString(I18n.format(spawnConditions.biomes.get(i)), boxX + 4, boxY + i * 10, 0xDDDDDD);
                    }
                }

                List<String> hints = spawnConditions.hints;
                if (!hints.isEmpty()) {
                    textY += 10;
                    textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.hintsHeader"), textX, textY, 10, textW, color);

                    for (String hint : hints) textY = drawWrappedString(fontRenderer, "- " + hint, textX + 6, textY, 12, textW, hintColor);
                }
            }
        } else {
            fontRenderer.drawString(I18n.format("gui.mobtracker.noMobSelected"), panelX + 6, panelY + 6, 0xFFFFFF);
        }
    }

    private void drawMobPreview(ResourceLocation id, int x, int y, int size, float rotationY) {
        Entity entity = analyzer.getEntityInstance(id);
        if (entity == null || size <= 0) return;

        // Draw background with border
        drawRect(x - 1, y - 1, x + size + 1, y + size + 1, entityBorderColor);
        drawRect(x, y, x + size, y + size, entityBgColor);

        // Calculate scale based on entity height - TODO: get length/depth too for animal-like mobs
        // Scale works great for humanoid mobs, but not the ones that are long or flat-ish/cube-ish (or lie about their size).
        float scale = size / Math.max(entity.height, entity.width) / 1.5f;

        // Center position
        int centerX = x + size / 2;
        int centerY = y + (int)(size * 0.85f);

        GlStateManager.pushMatrix();
        GlStateManager.color(1f, 1f, 1f);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableColorMaterial();
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 50F);
        GlStateManager.scale(-scale, scale, scale);
        GlStateManager.rotate(180F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(135F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(20F, 1.0F, 0.0F, 0.0F); // isometric tilt
        GlStateManager.rotate(rotationY, 0.0F, 1.0F, 0.0F);

        GlStateManager.translate(0.0F, (float) entity.getYOffset() + (entity instanceof EntityHanging ? 0.5F : 0.0F), 0.0F);
        Minecraft.getMinecraft().getRenderManager().playerViewY = 180F;

        try {
            Minecraft.getMinecraft().getRenderManager().renderEntity(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
        } catch (Exception e) {
            // TODO: would be funny to show MissingNo there instead
            SuperMobTracker.LOGGER.error("Error rendering entity!", e);
        }

        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();

        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        GlStateManager.popMatrix();
        GlStateManager.enableDepth();
        GlStateManager.disableColorMaterial();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    /**
     * Formats the entity name based on settings.
     * @param id Entity ResourceLocation
     * @param applyI18n Whether to apply internationalization
     * @return Formatted entity name
     */
    private String formatEntityName(ResourceLocation id, boolean applyI18n) {
        if (id == null) return "";
        if (!applyI18n) return id.toString();

        Entity entity = analyzer.getEntityInstance(id);
        if (entity != null) return entity.getDisplayName().getUnformattedText();

        // Fallback for modded entities missing translation mapping
        String[] parts = id.toString().split(":" , 2);
        String domain = parts.length > 0 ? parts[0] : "minecraft";
        String path = parts.length > 1 ? parts[1] : parts[0];
        String altKey = "entity." + domain + "." + path + ".name";
        if (I18n.hasKey(altKey)) return I18n.format(altKey);

        return id.toString();
    }

    class MobListWidget {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private FontRenderer fontRenderer;
        private GuiMobTracker tracker;

        private List<ResourceLocation> all = new ArrayList<>();
        // Store entries as (displayName, id) pairs to handle duplicate names
        private List<MobEntry> filteredSortedRaw = new ArrayList<>();
        private List<MobEntry> filteredSortedI18n = new ArrayList<>();
        private int scrollOffset = 0;
        private String filter = "";
        private boolean draggingScrollbar = false;
        private boolean lastI18n = ClientSettings.i18nNames;

        // Simple entry class to pair display name with resource location
        private class MobEntry {
            final String displayName;
            final ResourceLocation id;

            MobEntry(String displayName, ResourceLocation id) {
                this.displayName = displayName;
                this.id = id;
            }
        }

        MobListWidget(int x, int y, int w, int h, FontRenderer fontRenderer, GuiMobTracker tracker) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.fontRenderer = fontRenderer;
            this.tracker = tracker;

            all.addAll(ForgeRegistries.ENTITIES.getKeys().stream()
                .filter(id -> analyzer.getEntityInstance(id) != null)
                .collect(Collectors.toList()));

            rebuildFiltered();
            lastI18n = ClientSettings.i18nNames;
        }

        void setFilter(String filter) {
            if (filter == null) filter = "";

            if (!filter.equals(this.filter)) {
                this.filter = filter.toLowerCase();
                rebuildFiltered();
                scrollOffset = 0;
            }
        }

        private void rebuildFiltered() {
            List<ResourceLocation> base = all.stream()
                .filter(id -> id.toString().toLowerCase().contains(this.filter))
                .collect(Collectors.toList());

            List<ResourceLocation> trackedTop = base.stream().filter(SpawnTrackerManager::isTracked).collect(Collectors.toList());
            List<ResourceLocation> rest = base.stream().filter(id -> !SpawnTrackerManager.isTracked(id)).collect(Collectors.toList());

            // Build entries with unique display names
            filteredSortedRaw.clear();
            filteredSortedI18n.clear();

            // Helper to create entries with disambiguation for duplicates
            java.util.function.BiConsumer<List<ResourceLocation>, Boolean> buildEntries = (ids, isTracked) -> {
                // Count occurrences of each i18n name to detect duplicates
                Map<String, Integer> i18nNameCounts = new HashMap<>();
                for (ResourceLocation id : ids) {
                    String i18nName = tracker.formatEntityName(id, true);
                    i18nNameCounts.merge(i18nName, 1, Integer::sum);
                }

                // Track how many times we've seen each duplicate name
                Map<String, Integer> i18nNameSeen = new HashMap<>();

                List<MobEntry> rawEntries = new ArrayList<>();
                List<MobEntry> i18nEntries = new ArrayList<>();

                for (ResourceLocation id : ids) {
                    String rawName = tracker.formatEntityName(id, false);
                    String i18nName = tracker.formatEntityName(id, true);

                    // If this i18n name appears multiple times, append the entity ID to disambiguate
                    if (i18nNameCounts.get(i18nName) > 1) {
                        int seen = i18nNameSeen.getOrDefault(i18nName, 0) + 1;
                        i18nNameSeen.put(i18nName, seen);
                        i18nName = i18nName + " (" + id.toString() + ")";
                    }

                    rawEntries.add(new MobEntry(rawName, id));
                    i18nEntries.add(new MobEntry(i18nName, id));
                }

                // Sort by display name
                rawEntries.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
                i18nEntries.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

                filteredSortedRaw.addAll(rawEntries);
                filteredSortedI18n.addAll(i18nEntries);
            };

            buildEntries.accept(trackedTop, true);
            int trackedCountRaw = filteredSortedRaw.size();
            int trackedCountI18n = filteredSortedI18n.size();

            buildEntries.accept(rest, false);

            // Re-sort only the non-tracked portion (tracked entries stay at top)
            if (trackedCountRaw < filteredSortedRaw.size()) {
                List<MobEntry> trackedRaw = new ArrayList<>(filteredSortedRaw.subList(0, trackedCountRaw));
                List<MobEntry> restRaw = new ArrayList<>(filteredSortedRaw.subList(trackedCountRaw, filteredSortedRaw.size()));
                restRaw.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
                filteredSortedRaw.clear();
                filteredSortedRaw.addAll(trackedRaw);
                filteredSortedRaw.addAll(restRaw);
            }

            if (trackedCountI18n < filteredSortedI18n.size()) {
                List<MobEntry> trackedI18n = new ArrayList<>(filteredSortedI18n.subList(0, trackedCountI18n));
                List<MobEntry> restI18n = new ArrayList<>(filteredSortedI18n.subList(trackedCountI18n, filteredSortedI18n.size()));
                restI18n.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
                filteredSortedI18n.clear();
                filteredSortedI18n.addAll(trackedI18n);
                filteredSortedI18n.addAll(restI18n);
            }
        }

        // Helper to return current display list depending on i18n setting
        private List<MobEntry> getDisplayList() {
            return ClientSettings.i18nNames ? filteredSortedI18n : filteredSortedRaw;
        }

        // Find index of entry by ResourceLocation id
        private int findIndexById(ResourceLocation id) {
            if (id == null) return -1;

            List<MobEntry> display = getDisplayList();
            for (int i = 0; i < display.size(); i++) {
                if (display.get(i).id.equals(id)) return i;
            }

            return -1;
        }

        // When i18n mode changes, rebuild filtered names and keep selection visible
        private void ensureI18nSync() {
            boolean curr = ClientSettings.i18nNames;
            if (curr == lastI18n) return;

            ResourceLocation sel = tracker.getSelectedEntity();
            rebuildFiltered();
            lastI18n = curr;

            ensureVisible(sel);
        }

        ResourceLocation handleClick(int mouseX, int mouseY) {
            if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return null;
            ensureI18nSync();

            List<MobEntry> display = getDisplayList();

            int total = display.size();
            int visible = h / 12;
            if (total > visible) {
                int barX1 = x + w - 4;
                int barX2 = x + w - 2;
                if (mouseX >= barX1 && mouseX <= barX2) {
                    draggingScrollbar = true;
                    updateScrollFromMouse(mouseY);
                    return null;
                }
            }

            int index = (mouseY - y) / 12 + scrollOffset;
            if (index >= 0 && index < display.size()) return display.get(index).id;

            return null;
        }

        /**
         * Ensure the entry at the given index is visible in the list.
         * @param index Index of the entry to make visible
         */
        public void ensureVisible(int index) {
            if (index == -1) return;

            int visible = h / 12;
            if (index < scrollOffset) {
                scrollOffset = index;
            } else if (index >= scrollOffset + visible) {
                scrollOffset = index - visible + 1;
            }
        }

        /**
         * Ensure the entry with the given ResourceLocation id is visible in the list.
         * @param id ResourceLocation of the entry to make visible
         */
        public void ensureVisible(ResourceLocation id) {
            ensureVisible(findIndexById(id));
        }

        public boolean handleKey(int keyCode) {
            int direction = 0;
            if (keyCode == 200) direction = -1; // up
            else if (keyCode == 208) direction = 1; // down
            else if (keyCode == 201) direction = -10; // page up
            else if (keyCode == 209) direction = 10; // page down
            else return false;
            ensureI18nSync();

            ResourceLocation selected = tracker.getSelectedEntity();
            if (selected == null) return false;

            List<MobEntry> display = getDisplayList();
            int selectedIndex = findIndexById(selected);
            if (selectedIndex == -1) return false;

            selectedIndex = Math.min(display.size() - 1, Math.max(0, selectedIndex + direction));
            ResourceLocation newId = display.get(selectedIndex).id;
            if (newId != null) {
                tracker.selectEntity(newId);
                ensureVisible(selectedIndex);
            }

            return true;
        }

        void draw() {
            ensureI18nSync();

            List<MobEntry> displayList = getDisplayList();
            ResourceLocation selectedId = tracker.getSelectedEntity();
            int selectedIndex = selectedId != null ? findIndexById(selectedId) : -1;

            GlStateManager.pushMatrix();
            int visible = h / 12;
            int totalToDraw = Math.max(0, Math.min(displayList.size() - scrollOffset, visible));
            for (int i = 0; i < totalToDraw; i++) {
                int drawY = y + i * 12;
                int listIndex = scrollOffset + i;
                boolean isSel = selectedIndex == listIndex;
                if (isSel) Gui.drawRect(x, drawY, x + w - 6, drawY + 12, 0x40FFFFFF);

                MobEntry entry = displayList.get(listIndex);
                fontRenderer.drawString(entry.displayName, x + 4, drawY + 2, isSel ? 0xFFFFA0 : 0xFFFFFF);

                if (SpawnTrackerManager.isTracked(entry.id)) {
                    float scale = 2.0f;
                    int starX = (int) ((x + w - 16) / scale);
                    int starY = (int) ((drawY / scale)) - 1;

                    GlStateManager.pushMatrix();
                    GlStateManager.scale(scale, scale, scale);
                    fontRenderer.drawString("★", starX, starY, 0xFFD700);
                    GlStateManager.popMatrix();
                }
            }

            drawScrollbar();
            GlStateManager.popMatrix();
        }

        private void drawScrollbar() {
            List<MobEntry> display = getDisplayList();
            int total = display.size();
            int visible = h / 12;
            if (total <= visible) return;

            int barX1 = x + w - 4;
            int barX2 = x + w - 2;
            int trackY1 = y;
            int trackY2 = y + h;
            Gui.drawRect(barX1, trackY1, barX2, trackY2, 0x40000000);

            float ratio = (float) visible / (float) total;
            int barH = Math.max(8, (int) (h * ratio));
            float posRatio = (float) scrollOffset / (float) (total - visible);
            int barY1 = y + (int) ((h - barH) * posRatio);
            int barY2 = barY1 + barH;
            Gui.drawRect(barX1, barY1, barX2, barY2, 0x80FFFFFF);
        }

        void scroll(int amount) {
            List<MobEntry> display = getDisplayList();
            int visible = h / 12;
            int maxOffset = Math.max(0, display.size() - visible);
            scrollOffset = Math.min(maxOffset, Math.max(0, scrollOffset + amount));
        }

        void onMouseDrag(int mouseY) {
            if (!draggingScrollbar) return;

            updateScrollFromMouse(mouseY);
        }

        void onMouseRelease() {
            draggingScrollbar = false;
        }

        private void updateScrollFromMouse(int mouseY) {
            List<MobEntry> display = getDisplayList();
            int total = display.size();
            int visible = h / 12;
            if (total <= visible) return;

            int barH = Math.max(8, (int) (h * ((float) visible / (float) total)));
            int trackTop = y;
            int trackBottom = y + h - barH;
            int clamped = Math.max(trackTop, Math.min(mouseY - barH / 2, trackBottom));
            float ratio = (float) (clamped - trackTop) / (float) (h - barH);
            int maxOffset = total - visible;
            scrollOffset = Math.max(0, Math.min(maxOffset, Math.round(ratio * maxOffset)));
        }
    }
}
