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
import com.supermobtracker.util.Utils;


public class GuiMobTracker extends GuiScreen {
    private GuiTextField filterField;
    private MobListWidget listWidget;
    private ResourceLocation selected;
    private SpawnConditionAnalyzer analyzer = new SpawnConditionAnalyzer();
    private SpawnConditionAnalyzer.SpawnConditions spawnConditions;
    private long lastClickTime = 0L;
    private ResourceLocation lastClickId = null;

    // Mob preview rotation state
    private float previewRotationY = 0.0f;
    private boolean draggingPreview = false;
    private int dragStartX = 0;
    private float dragStartRotation = 0.0f;
    private long previewLastClickTime = 0L;
    private int previewX, previewY, previewSize;

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
    }

    public void selectEntity(ResourceLocation id) {
        this.selected = id;
        this.spawnConditions = analyzer.analyze(id);
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
        filterField.mouseClicked(mouseX, mouseY, mouseButton);
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

        // Handle preview area click for rotation
        if (isInPreviewArea(mouseX, mouseY)) {
            long now = Minecraft.getSystemTime();
            if ((now - previewLastClickTime) < 500L) {
                previewRotationY = 0.0f;
            } else {
                draggingPreview = true;
                dragStartX = mouseX;
                dragStartRotation = previewRotationY;
            }
            previewLastClickTime = now;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (listWidget != null) listWidget.onMouseDrag(mouseY);

        if (draggingPreview) {
            float delta = (mouseX - dragStartX) * 0.5f;
            previewRotationY = dragStartRotation + delta;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (listWidget != null) listWidget.onMouseRelease();
        draggingPreview = false;
    }

    private boolean isInPreviewArea(int mouseX, int mouseY) {
        return mouseX >= previewX && mouseX <= previewX + previewSize &&
               mouseY >= previewY && mouseY <= previewY + previewSize;
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
        drawRightPanel(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private int drawElidedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        String elided = renderer.trimStringToWidth(text, maxWidth - renderer.getStringWidth("..."));
        if (!elided.equals(text)) elided += "...";

        renderer.drawString(elided, x, y, color);

        return y + lineHeight;
    }

    private int drawWrappedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        String[] wrapped = Utils.wrapText(text, maxWidth);
        for (String line : wrapped) {
            renderer.drawString(line, x, y, color);
            y += lineHeight;
        }

        return y;
    }

    private String format(double d) {
        String s = String.format("%.1f", d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);

        return s;
    }

    private void drawRightPanel(int mouseX, int mouseY) {
        int leftWidth = Math.min(width / 2, 250);
        int panelX = leftWidth + 10;
        int panelY = 10;
        int panelW = width - panelX - 20;
        int panelH = height - 40;

        String sep = I18n.format("gui.mobtracker.separator");

        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0x80000000);
        if (selected != null) {
            int textX = panelX + 6;
            int textY = panelY + 6;
            int textW = panelW - 12;
            int color = 0xFFFFFF;

            // preview: up to a quarter of panel height
            int previewSizeLocal = Math.min(textW, panelH / 4);
            previewX = textX + (textW - previewSizeLocal) / 2;
            previewY = textY;
            previewSize = previewSizeLocal;

            // Calculate cursor-following yaw when mouse is in preview area
            float cursorYaw = 0.0f;
            if (isInPreviewArea(mouseX, mouseY)) {
                int centerX = previewX + previewSize / 2;
                cursorYaw = (mouseX - centerX) * 0.5f;
            }

            drawMobPreview(selected, previewX, previewY, previewSize, previewRotationY, cursorYaw);

            textY += previewSize + 6;

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
            textY = drawElidedString(fontRenderer, name, textX, textY, 12, textW, color);

            String entityIdStr = I18n.format("gui.mobtracker.entityId", selected.toString());
            textY = drawElidedString(fontRenderer, entityIdStr, textX, textY, 12, textW, color);

            Vec3d size = analyzer.getEntitySize(selected);
            String sizeStr = I18n.format("gui.mobtracker.entitySize", format(size.x), format(size.y), format(size.z));
            textY = drawElidedString(fontRenderer, sizeStr, textX, textY, 12, textW, color);

            String attributeString = I18n.format("gui.mobtracker.attributes", String.join(sep, attributes));
            textY = drawWrappedString(fontRenderer, attributeString, textX, textY, 12, textW, color);

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
                textY += 10;

                fontRenderer.drawString(I18n.format("gui.mobtracker.spawnConditions"), textX, textY, color);
                textY += 14;

                int condsX = textX + 6;

                String lightLevels = I18n.format("gui.mobtracker.lightLevels", Utils.formatRangeFromList(spawnConditions.lightLevels, sep));
                textY = drawWrappedString(fontRenderer, lightLevels, condsX, textY, 14, textW, 0xFFFFAA);

                String yPos = I18n.format("gui.mobtracker.yPos", Utils.formatRangeFromList(spawnConditions.yLevels, sep));
                textY = drawWrappedString(fontRenderer, yPos, condsX, textY, 14, textW, 0xAAAAFF);

                // FIXME: translate ground blocks. That one is tricky.
                String groundBlocks = I18n.format("gui.mobtracker.groundBlocks", String.join(sep, spawnConditions.groundBlocks));
                textY = drawWrappedString(fontRenderer, groundBlocks, condsX, textY, 14, textW, 0xAAFFAA);

                String timeOfDayTL = String.join(sep, ConditionUtils.translateList(spawnConditions.timeOfDay, "gui.mobtracker.timeOfDay"));
                String timeOfDay = I18n.format("gui.mobtracker.timeOfDay", timeOfDayTL);
                textY = drawWrappedString(fontRenderer, timeOfDay, condsX, textY, 14, textW, 0xFFFFFF);
                String weatherTL = String.join(sep, ConditionUtils.translateList(spawnConditions.weather, "gui.mobtracker.weather"));
                String weather = I18n.format("gui.mobtracker.weather", weatherTL);
                textY = drawWrappedString(fontRenderer, weather, condsX, textY, 14, textW, 0xFFFFFF);

                boolean hasBiomes = spawnConditions.biomes.size() > 0;
                boolean isUnknownBiome = hasBiomes && spawnConditions.biomes.size() == 1 &&
                                         spawnConditions.biomes.get(0).equals("unknown");
                boolean isAnyBiome = hasBiomes && spawnConditions.biomes.size() == 1 &&
                                     spawnConditions.biomes.get(0).equals("gui.mobtracker.any");

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
                fontRenderer.drawString(biomesLabel, condsX, textY, 0xFFFFFF);

                boolean showTooltip = hasBiomes && !isUnknownBiome && !isAnyBiome &&
                    mouseX >= condsX && mouseX <= condsX + fontRenderer.getStringWidth(biomesLabel) &&
                    mouseY >= textY && mouseY <= textY + 12;
                if (showTooltip) {
                    int maxWidth = Math.min(300, spawnConditions.biomes.stream().mapToInt(s -> fontRenderer.getStringWidth(s)).max().orElse(100) + 8);
                    int boxX = Math.min(mouseX + 12, panelX + panelW - maxWidth - 4);
                    int boxY = Math.min(mouseY + 12, panelY + panelH - 8 - Math.min(spawnConditions.biomes.size(), 20) * 10);
                    int lines = Math.min(spawnConditions.biomes.size(), 20);
                    drawRect(boxX - 2, boxY - 2, boxX + maxWidth + 2, boxY + lines * 10 + 2, 0xC0000000);
                    for (int i = 0; i < lines; i++) {
                        fontRenderer.drawString(spawnConditions.biomes.get(i), boxX + 4, boxY + i * 10, 0xDDDDDD);
                    }
                }
                textY += 12;

                List<String> hints = spawnConditions.hints;
                if (!hints.isEmpty()) {
                    textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.hintsHeader"), textX, textY, 10, textW, color);

                    for (String hint : hints) {
                        textY = drawWrappedString(fontRenderer, "- " + hint, textX + 6, textY, 12, textW, 0xFFAAAA);
                    }
                }
            }
        } else {
            fontRenderer.drawString(I18n.format("gui.mobtracker.noMobSelected"), panelX + 6, panelY + 6, 0xFFFFFF);
        }
    }

    private void drawMobPreview(ResourceLocation id, int x, int y, int size, float rotationY, float cursorYaw) {
        Entity entity = analyzer.getEntityInstance(id);
        if (entity == null || size <= 0) return;

        // Draw background with border
        int bgColor = 0xFF404040;
        int borderColor = 0xFF808080;
        drawRect(x - 1, y - 1, x + size + 1, y + size + 1, borderColor);
        drawRect(x, y, x + size, y + size, bgColor);

        // Calculate scale based on entity height
        float height = entity.height;
        height = (float) ((height - 1) * 0.7 + 1);
        float scale = (size * 14.0f / 25) / height;

        // Center position
        int centerX = x + size / 2;
        int centerY = y + size / 2;

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
        GlStateManager.rotate(rotationY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(0.0F, 1.0F, 0.0F, 0.0F);

        // Apply cursor-following yaw to entity (EntityLivingBase has these fields)
        if (entity instanceof net.minecraft.entity.EntityLivingBase) {
            net.minecraft.entity.EntityLivingBase living = (net.minecraft.entity.EntityLivingBase) entity;
            living.rotationYaw = cursorYaw;
            living.rotationYawHead = cursorYaw;
            living.prevRotationYaw = cursorYaw;
            living.prevRotationYawHead = cursorYaw;
            living.renderYawOffset = cursorYaw;
            living.prevRenderYawOffset = cursorYaw;
        }
        entity.rotationPitch = 0.0F;

        GlStateManager.translate(0.0F, (float) entity.getYOffset() + (entity instanceof EntityHanging ? 0.5F : 0.0F), 0.0F);
        Minecraft.getMinecraft().getRenderManager().playerViewY = 180F;

        try {
            Minecraft.getMinecraft().getRenderManager().renderEntity(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
        } catch (Exception e) {
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
        private List<String> filteredSortedRaw = new ArrayList<>();
        private List<String> filteredSortedI18n = new ArrayList<>();
        private Map<String, ResourceLocation> nameToIdMapRaw = new HashMap<>();
        private Map<String, ResourceLocation> nameToIdMapI18n = new HashMap<>();
        private int scrollOffset = 0;
        private String filter = "";
        private boolean draggingScrollbar = false;
        private boolean lastI18n = ClientSettings.i18nNames;

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

            // Clear and repopulate name->id maps for both raw and i18n names
            nameToIdMapRaw.clear();
            nameToIdMapI18n.clear();

            List<String> trackedRawNames = new ArrayList<>();
            List<String> trackedI18nNames = new ArrayList<>();
            List<String> rawNames = new ArrayList<>();
            List<String> i18nNames = new ArrayList<>();

            for (ResourceLocation id : trackedTop) {
                String rawName = tracker.formatEntityName(id, false);
                String i18nName = tracker.formatEntityName(id, true);
                trackedRawNames.add(rawName);
                trackedI18nNames.add(i18nName);
                nameToIdMapRaw.put(rawName, id);
                nameToIdMapI18n.put(i18nName, id);
            }

            for (ResourceLocation id : rest) {
                String rawName = tracker.formatEntityName(id, false);
                String i18nName = tracker.formatEntityName(id, true);
                rawNames.add(rawName);
                i18nNames.add(i18nName);
                nameToIdMapRaw.put(rawName, id);
                nameToIdMapI18n.put(i18nName, id);
            }

            filteredSortedRaw.clear();
            filteredSortedRaw.addAll(trackedRawNames.stream().sorted().collect(Collectors.toList()));
            filteredSortedRaw.addAll(rawNames.stream().sorted().collect(Collectors.toList()));

            filteredSortedI18n.clear();
            filteredSortedI18n.addAll(trackedI18nNames.stream().sorted().collect(Collectors.toList()));
            filteredSortedI18n.addAll(i18nNames.stream().sorted().collect(Collectors.toList()));
        }

        // Helper to return current display list depending on i18n setting
        private List<String> getDisplayList() {
            return ClientSettings.i18nNames ? filteredSortedI18n : filteredSortedRaw;
        }

        // Helper to return current map depending on i18n setting
        private Map<String, ResourceLocation> getNameToIdMap() {
            return ClientSettings.i18nNames ? nameToIdMapI18n : nameToIdMapRaw;
        }

        // When i18n mode changes, rebuild filtered names and keep selection visible
        private void ensureI18nSync() {
            boolean curr = ClientSettings.i18nNames;
            if (curr == lastI18n) return;

            ResourceLocation sel = tracker.getSelectedEntity();
            rebuildFiltered();
            lastI18n = curr;

            if (sel != null) {
                List<String> display = getDisplayList();
                String selName = tracker.formatEntityName(sel, curr);
                int idx = display.indexOf(selName);
                if (idx != -1) {
                    int visible = h / 12;
                    if (idx < scrollOffset) scrollOffset = idx;
                    else if (idx >= scrollOffset + visible) scrollOffset = idx - visible + 1;
                }
            }
        }

        ResourceLocation handleClick(int mouseX, int mouseY) {
            if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return null;
            ensureI18nSync();

            List<String> display = getDisplayList();
            Map<String, ResourceLocation> nameToIdMap = getNameToIdMap();

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
            if (index >= 0 && index < display.size()) return nameToIdMap.get(display.get(index));

            return null;
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

            boolean i18n = ClientSettings.i18nNames;
            List<String> display = getDisplayList();
            Map<String, ResourceLocation> nameToIdMap = getNameToIdMap();

            String selectedName = tracker.formatEntityName(selected, i18n);
            int selectedIndex = display.indexOf(selectedName);
            if (selectedIndex == -1) return false;

            selectedIndex = Math.min(display.size() - 1, Math.max(0, selectedIndex + direction));
            ResourceLocation newId = nameToIdMap.get(display.get(selectedIndex));
            if (newId != null) tracker.selectEntity(newId);

            // Adjust scroll to keep selected visible
            int visible = h / 12;
            if (selectedIndex < scrollOffset) {
                scrollOffset = selectedIndex;
            } else if (selectedIndex >= scrollOffset + visible) {
                scrollOffset = selectedIndex - visible + 1;
            }

            return true;
        }

        void draw() {
            ensureI18nSync();

            boolean i18n = ClientSettings.i18nNames;
            List<String> displayList = getDisplayList();
            Map<String, ResourceLocation> nameToIdMap = getNameToIdMap();
            ResourceLocation selectedId = tracker.getSelectedEntity();
            String selectedName = selectedId != null ? tracker.formatEntityName(selectedId, i18n) : "";
            int selectedIndex = selectedId != null ? displayList.indexOf(selectedName) : -1;

            GlStateManager.pushMatrix();
            int visible = h / 12;
            int totalToDraw = Math.max(0, Math.min(displayList.size() - scrollOffset, visible));
            for (int i = 0; i < totalToDraw; i++) {
                int drawY = y + i * 12;
                int listIndex = scrollOffset + i;
                boolean isSel = selectedIndex == listIndex;
                if (isSel) Gui.drawRect(x, drawY, x + w - 6, drawY + 12, 0x40FFFFFF);

                String display = displayList.get(listIndex);
                fontRenderer.drawString(display, x + 4, drawY + 2, isSel ? 0xFFFFA0 : 0xFFFFFF);

                ResourceLocation id = nameToIdMap.get(display);
                if (id != null && SpawnTrackerManager.isTracked(id)) {
                    float scale = 2.0f;
                    int starX = (int) ((x + w - 16) / scale);
                    int starY = (int) ((drawY / scale)) - 1;

                    GlStateManager.pushMatrix();
                    GlStateManager.scale(scale, scale, scale);
                    // FIXME: The star symbol kinda sucks, due to lack of antialiasing
                    fontRenderer.drawString("★", starX, starY, 0xFFD700);
                    GlStateManager.popMatrix();
                }
            }

            drawScrollbar();
            GlStateManager.popMatrix();
        }

        private void drawScrollbar() {
            List<String> display = getDisplayList();
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
            List<String> display = getDisplayList();
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
            List<String> display = getDisplayList();
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
