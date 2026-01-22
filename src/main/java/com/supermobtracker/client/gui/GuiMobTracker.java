package com.supermobtracker.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.block.Block;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.SuperMobTracker;
import com.supermobtracker.client.ClientSettings;
import com.supermobtracker.client.util.GuiDrawingUtils;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.drops.DropSimulator;
import com.supermobtracker.spawn.BiomeDimensionMapper;
import com.supermobtracker.spawn.ConditionUtils;
import com.supermobtracker.spawn.SpawnConditionAnalyzer;
import com.supermobtracker.tracking.SpawnTrackerManager;
import com.supermobtracker.util.JEIHelper;
import com.supermobtracker.util.TranslationUtils;
import com.supermobtracker.util.Utils;


public class GuiMobTracker extends GuiScreen {
    private GuiTextField filterField;
    private MobListWidget listWidget;
    private ResourceLocation selected;
    private SpawnConditionAnalyzer analyzer = new SpawnConditionAnalyzer();
    private SpawnConditionAnalyzer.SpawnConditions spawnConditions;
    private long lastClickTime = 0L;
    private ResourceLocation lastClickId = null;

    // Cache for spawn conditions to avoid regenerating on window resize
    private static ResourceLocation cachedEntityId = null;
    private static SpawnConditionAnalyzer.SpawnConditions cachedSpawnConditions = null;

    // JEI button bounds (updated during draw)
    private int jeiButtonX, jeiButtonY, jeiButtonW, jeiButtonH;
    private boolean jeiButtonVisible = false;

    // Drops button bounds (updated during draw)
    private int dropsButtonX, dropsButtonY, dropsButtonW, dropsButtonH;
    private boolean dropsButtonVisible = false;

    // Drops window modal
    private GuiDropsWindow dropsWindow = null;

    // Entity preview modal
    private GuiEntityPreviewModal previewModal = null;

    // Preview bounds (updated during draw)
    private int previewX, previewY, previewSize;

    // Retry button bounds (updated during draw)
    private int retryButtonX, retryButtonY, retryButtonW, retryButtonH;
    private boolean retryButtonVisible = false;

    // Panel bounds for text clipping
    private int panelMaxY = Integer.MAX_VALUE;

    // Biome tooltip widget
    private MultiColumnTooltipWidget biomeTooltipWidget = new MultiColumnTooltipWidget(null);

    // Unknown dimension tooltip data (set during drawRightPanel, rendered in drawDimensionTooltip)
    private boolean showDimensionUnknownTooltip = false;
    private int dimensionLabelX, dimensionLabelY, dimensionLabelW;

    private static final int entityBgColor = 0xFF404040;
    private static final int entityBorderColor = 0xFF808080;
    private static final int lightColor = 0xFFFFAA;
    private static final int ylevelColor = 0xAAAAFF;
    private static final int groundColor = 0xAAFFAA;
    private static final int timeOfDayColor = 0xFFAAFF;
    private static final int weatherColor = 0xAABBFF;
    private static final int skyColor = 0xAAFFFF;
    private static final int dimensionColor = 0xFFDDAA;
    private static final int biomeColor = 0xAADDFF;
    private static final int hintColor = 0xFFAAAA;

    private String getI18nButtonString() {
        return ClientSettings.i18nNames ? I18n.format("gui.supermobtracker.i18nIDs.on") : I18n.format("gui.supermobtracker.i18nIDs.off");
    }

    @Override
    public void initGui() {
        int leftWidth = Math.min(width / 2, 250);
        filterField = new GuiTextField(0, fontRenderer, 10, 10, leftWidth - 20, 14);
        filterField.setText(ModConfig.getClientFilterText());
        listWidget = new MobListWidget(10, 30, leftWidth - 20, height - 70, fontRenderer, this);
        this.buttonList.clear();

        this.buttonList.add(new GuiButton(1, 10, height - 30, leftWidth - 20, 20, getI18nButtonString()));

        // Initialize biome tooltip widget
        biomeTooltipWidget = new MultiColumnTooltipWidget(fontRenderer);
        biomeTooltipWidget.updateScreenSize(width, height);

        // Restore last selected entity and its cached spawn conditions
        String lastEntity = ModConfig.getClientLastSelectedEntity();
        if (lastEntity != null && !lastEntity.isEmpty()) {
            ResourceLocation lastId = new ResourceLocation(lastEntity);
            if (analyzer.getEntityInstance(lastId) != null) {
                this.selected = lastId;

                // Reuse cached spawn conditions if the entity ID matches
                if (lastId.equals(cachedEntityId) && cachedSpawnConditions != null) {
                    this.spawnConditions = cachedSpawnConditions;
                } else {
                    this.spawnConditions = analyzer.analyze(lastId);
                    cachedEntityId = lastId;
                    cachedSpawnConditions = this.spawnConditions;

                    if (lastId != null) DropSimulator.getOrStartSimulation(lastId);
                }

                listWidget.ensureVisible(lastId);
            }
        }

        // Restore drops window if it was hidden for JEI navigation
        if (dropsWindow != null) {
            dropsWindow.restoreIfHiddenForJEI();
        }
    }

    public void selectEntity(ResourceLocation id) {
        this.selected = id;
        this.spawnConditions = analyzer.analyze(id);
        cachedEntityId = id;
        cachedSpawnConditions = this.spawnConditions;
        ModConfig.setClientLastSelectedEntity(id != null ? id.toString() : "");

       // Start drop simulation in background so it may be ready when the player opens the drops window
        if (id != null) DropSimulator.getOrStartSimulation(id);
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
        String newFilter = filterField.getText();
        listWidget.setFilter(newFilter);
        ModConfig.setClientFilterText(newFilter);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // Handle preview modal first if visible
        if (previewModal != null && previewModal.isVisible()) {
            if (previewModal.handleKey(keyCode)) return;
        }

        // Handle drops window first if visible
        if (dropsWindow != null && dropsWindow.isVisible()) {
            if (dropsWindow.handleKey(keyCode)) return;
        }

        if (filterField.textboxKeyTyped(typedChar, keyCode)) return;
        if (listWidget.handleKey(keyCode)) return;

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Handle preview modal first if visible
        if (previewModal != null && previewModal.isVisible()) {
            if (previewModal.handleClick(mouseX, mouseY, mouseButton)) return;
        }

        // Handle drops window first if visible
        if (dropsWindow != null && dropsWindow.isVisible()) {
            if (dropsWindow.handleClick(mouseX, mouseY, mouseButton)) return;
        }

        // Right-click on filter field clears it
        if (mouseButton == 1 &&
            mouseX >= filterField.x && mouseX <= filterField.x + filterField.width &&
            mouseY >= filterField.y && mouseY <= filterField.y + filterField.height) {
            filterField.setText("");
        }

        filterField.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0) {
            // Handle entity preview click - open modal
            if (selected != null && previewSize > 0 &&
                mouseX >= previewX && mouseX <= previewX + previewSize &&
                mouseY >= previewY && mouseY <= previewY + previewSize) {
                Entity entity = analyzer.getEntityInstance(selected);
                if (entity != null) {
                    String entityName = formatEntityName(selected, true);
                    previewModal = new GuiEntityPreviewModal(this, selected, entity, entityName);
                    previewModal.show(width, height);

                    return;
                }
            }

            // Handle biomes label click - copy to clipboard
            List<String> biomes = biomeTooltipWidget.getLines();
            if (biomes != null && !biomes.isEmpty() && biomeTooltipWidget.isHovered(mouseX, mouseY)) {
                String biomeList = String.join("\n", biomes);
                GuiScreen.setClipboardString(biomeList);

                String entityName = formatEntityName(selected, true);
                String msg = I18n.format("gui.mobtracker.biomesCopied", entityName);
                mc.player.sendMessage(new TextComponentString(msg));

                return;
            }

            // Handle drops button click
            if (dropsButtonVisible && selected != null &&
                mouseX >= dropsButtonX && mouseX <= dropsButtonX + dropsButtonW &&
                mouseY >= dropsButtonY && mouseY <= dropsButtonY + dropsButtonH) {
                String entityName = formatEntityName(selected, true);
                dropsWindow = new GuiDropsWindow(this, selected, entityName);
                dropsWindow.show();

                return;
            }

            // Handle JEI button click
            if (jeiButtonVisible && selected != null &&
                mouseX >= jeiButtonX && mouseX <= jeiButtonX + jeiButtonW &&
                mouseY >= jeiButtonY && mouseY <= jeiButtonY + jeiButtonH) {
                JEIHelper.showMobPage(selected);

                return;
            }

            // Handle retry button click
            if (retryButtonVisible && selected != null &&
                mouseX >= retryButtonX && mouseX <= retryButtonX + retryButtonW &&
                mouseY >= retryButtonY && mouseY <= retryButtonY + retryButtonH) {
                // Clear cache and re-analyze
                cachedEntityId = null;
                cachedSpawnConditions = null;
                selectEntity(selected);

                return;
            }

            // Handle list widget click
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

        // Don't scroll the list if drops window is visible and mouse is over it
        if (dropsWindow != null && dropsWindow.isVisible()) {
            int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (dropsWindow.isMouseOver(mouseX, mouseY)) return;
        }

        int wheel = Mouse.getDWheel();
        if (wheel != 0) listWidget.scroll(wheel < 0 ? 1 : -1);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        // Determine if any modal should block hover events
        boolean previewModalBlocking = previewModal != null && previewModal.isVisible();
        boolean dropsWindowBlocking = dropsWindow != null && dropsWindow.isVisible() && dropsWindow.isMouseOver(mouseX, mouseY);
        boolean modalBlocking = previewModalBlocking || dropsWindowBlocking;
        int effectiveMouseX = modalBlocking ? -1 : mouseX;
        int effectiveMouseY = modalBlocking ? -1 : mouseY;

        filterField.drawTextBox();
        listWidget.draw(effectiveMouseX, effectiveMouseY);
        drawRightPanel(effectiveMouseX, effectiveMouseY, partialTicks);

        super.drawScreen(mouseX, mouseY, partialTicks);

        // Draw tooltips only if no modal is blocking
        if (!modalBlocking) {
            biomeTooltipWidget.draw(mouseX, mouseY);
            drawDimensionTooltip(mouseX, mouseY);
            drawPreviewTooltip(mouseX, mouseY);
            listWidget.drawTooltips(mouseX, mouseY);
        }

        // Draw drops window on top of everything
        if (dropsWindow != null && dropsWindow.isVisible()) {
            dropsWindow.draw(mouseX, mouseY, partialTicks);
            dropsWindow.drawTooltips(mouseX, mouseY);
        }

        // Draw preview modal on top of everything
        if (previewModal != null && previewModal.isVisible()) {
            previewModal.draw(mouseX, mouseY, partialTicks, fontRenderer);
        }
    }

    private int drawElidedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        if (y + lineHeight > panelMaxY) return y;

        String elided = renderer.trimStringToWidth(text, maxWidth - renderer.getStringWidth("..."));
        if (!elided.equals(text)) elided += "...";

        renderer.drawString(elided, x, y, color);

        return y + lineHeight;
    }

    private int drawWrappedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        return drawWrappedString(renderer, text, x, y, lineHeight, 0, maxWidth, color);
    }

    private int drawWrappedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int extraSpacing, int maxWidth, int color) {
        List<String> wrapped = Utils.wrapText(renderer, text, maxWidth);
        int totalHeight = lineHeight * wrapped.size() + extraSpacing * Math.max(0, wrapped.size() - 1);
        if (y + totalHeight > panelMaxY) return y;

        for (int i = 0; i < wrapped.size(); i++) {
            renderer.drawString(wrapped.get(i), x, y, color);
            y += lineHeight + (i < wrapped.size() - 1 ? extraSpacing : 0);
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

        // Reset button visibility
        retryButtonVisible = false;

        // Clear tooltip data
        showDimensionUnknownTooltip = false;

        String sep = I18n.format("gui.mobtracker.separator");

        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0x80000000);
        if (selected == null) {
            fontRenderer.drawString(I18n.format("gui.mobtracker.noMobSelected"), panelX + 6, panelY + 6, 0xFFFFFF);
            return;
        }

        int textX = panelX + 6;
        int textY = panelY + 6;
        int textW = panelW - 12;
        int color = 0xFFFFFF;

        // preview: up to a quarter of panel height, slowly rotating (full rotation every 10s)
        int previewSizeLocal = Math.min(textW, panelH / 4);
        this.previewX = textX + (textW - previewSizeLocal) / 2;
        this.previewY = textY;
        this.previewSize = previewSizeLocal;
        float previewRotationY = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;

        Entity entity = analyzer.getEntityInstance(selected);
        if (previewModal == null || !previewModal.isVisible()) {
            // If preview modal is open, do not draw preview in panel to avoid overlap
            GuiDrawingUtils.drawMobPreview(selected, entity, previewX, previewY, previewSize, previewRotationY);
        }

        textY += previewSize + 16;

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

        // Drops button (always visible for selected entity)
        dropsButtonVisible = false;
        String dropsText = I18n.format("gui.mobtracker.dropsButton");
        dropsButtonW = fontRenderer.getStringWidth(dropsText) + 8;
        dropsButtonH = 12;
        dropsButtonX = textX;
        dropsButtonY = textY;
        dropsButtonVisible = true;

        boolean dropsHovered = mouseX >= dropsButtonX && mouseX <= dropsButtonX + dropsButtonW &&
                            mouseY >= dropsButtonY && mouseY <= dropsButtonY + dropsButtonH;
        int dropsBtnBg = dropsHovered ? 0x60FFFFFF : 0x40FFFFFF;
        int dropsBtnColor = dropsHovered ? 0xFFFFAA : 0xCCCCCC;

        drawRect(dropsButtonX, dropsButtonY, dropsButtonX + dropsButtonW, dropsButtonY + dropsButtonH, dropsBtnBg);
        fontRenderer.drawString(dropsText, dropsButtonX + 4, dropsButtonY + 2, dropsBtnColor);

        int nextButtonX = dropsButtonX + dropsButtonW + 4;

        // JEI button (only if JEI is loaded and can show mob info)
        jeiButtonVisible = false;
        if (JEIHelper.canShowMobPage(selected)) {
            String jeiText = I18n.format("gui.mobtracker.jeiButton");
            jeiButtonW = fontRenderer.getStringWidth(jeiText) + 8;
            jeiButtonH = 12;
            jeiButtonX = nextButtonX;
            jeiButtonY = textY;
            jeiButtonVisible = true;

            boolean hovered = mouseX >= jeiButtonX && mouseX <= jeiButtonX + jeiButtonW &&
                                mouseY >= jeiButtonY && mouseY <= jeiButtonY + jeiButtonH;
            int btnBg = hovered ? 0x60FFFFFF : 0x40FFFFFF;
            int btnColor = hovered ? 0xFFFFAA : 0xCCCCCC;

            drawRect(jeiButtonX, jeiButtonY, jeiButtonX + jeiButtonW, jeiButtonY + jeiButtonH, btnBg);
            fontRenderer.drawString(jeiText, jeiButtonX + 4, jeiButtonY + 2, btnColor);
        }

        textY += dropsButtonH + 4;

        // Check if analysis failed (crashed) vs entity cannot spawn naturally
        List<String> errorHints = analyzer.getErrorHints();
        boolean analysisCrashed = !errorHints.isEmpty() && errorHints.stream().anyMatch(h -> h.contains("crashed"));

        // Entities without native biomes (not in any spawn table) cannot spawn naturally.
        // But if analysis crashed, show that instead.
        if (spawnConditions == null) {
            textY += 10;

            String line1 = analysisCrashed ? "gui.mobtracker.analysisCrashed" : "gui.mobtracker.cannotSpawnNaturally";
            String line2 = analysisCrashed ? "gui.mobtracker.analysisCrashedHint" : "gui.mobtracker.cannotSpawnNaturallyHint";

            textY = drawWrappedString(fontRenderer, I18n.format(line1), textX, textY, 12, textW, 0xFFAAAA);
            textY = drawWrappedString(fontRenderer, I18n.format(line2), textX, textY, 12, textW, 0xAAAAAA);

            return;
        }

        textY += 20;

        textY = drawSingleString(fontRenderer, I18n.format("gui.mobtracker.spawnConditions"), textX, textY, 12, color);

        int condsX = textX + 6;

        // Only show spawn condition details if they have valid data
        if (!spawnConditions.failed()) {
            String lightLevels = I18n.format("gui.mobtracker.lightLevels", Utils.formatRangeFromList(spawnConditions.lightLevels, sep));
            textY = drawWrappedString(fontRenderer, lightLevels, condsX, textY, 12, textW, lightColor);

            String yPos = I18n.format("gui.mobtracker.yPos", Utils.formatRangeFromList(spawnConditions.yLevels, sep));
            textY = drawWrappedString(fontRenderer, yPos, condsX, textY, 12, textW, ylevelColor);

            // Show ground blocks only if this condition was queried (list is non-null)
            if (spawnConditions.groundBlocks != null) {
                // Limit displayed ground blocks to avoid excessively long lines
                int maxGroundBlocksToShow = 20;
                List<String> translatedGroundBlocksList = spawnConditions.groundBlocks.stream().map(TranslationUtils::translateBlockName).collect(Collectors.toList());
                List<String> groundBlocksList = new ArrayList<>(new LinkedHashSet<>(translatedGroundBlocksList));  // Deduplicate
                if (groundBlocksList.size() > maxGroundBlocksToShow) {
                    groundBlocksList = groundBlocksList.subList(0, maxGroundBlocksToShow);
                    groundBlocksList.add("...");
                }

                String groundBlocks = I18n.format("gui.mobtracker.groundBlocks", groundBlocksList.stream().collect(Collectors.joining(sep)));
                textY = drawWrappedString(fontRenderer, groundBlocks, condsX, textY, 12, textW, groundColor);
            }

            // Show time of day only if queried (list is non-null)
            if (spawnConditions.timeOfDay != null && !spawnConditions.timeOfDay.isEmpty()) {
                String timeOfDayTL = Utils.formatTimeRanges(spawnConditions.timeOfDay, sep);
                String timeOfDay = I18n.format("gui.mobtracker.timeOfDay", timeOfDayTL);
                textY = drawWrappedString(fontRenderer, timeOfDay, condsX, textY, 12, textW, timeOfDayColor);
            }

            // Show weather only if queried (list is non-null)
            if (spawnConditions.weather != null) {
                String weatherTL = String.join(sep, ConditionUtils.translateList(spawnConditions.weather, "gui.mobtracker.weather"));
                String weather = I18n.format("gui.mobtracker.weather", weatherTL);
                textY = drawWrappedString(fontRenderer, weather, condsX, textY, 12, textW, weatherColor);
            }

            // Display sky requirement if determined
            if (spawnConditions.requiresSky != null) {
                String skyKey = spawnConditions.requiresSky ? "outside" : "underground";
                String skyText = I18n.format("gui.mobtracker.sky." + skyKey);
                textY = drawWrappedString(fontRenderer, skyText, condsX, textY, 12, textW, skyColor);
            }

            // Display moon phases if determined
            if (spawnConditions.moonPhases != null && !spawnConditions.moonPhases.isEmpty()) {
                String moonPhasesStr = spawnConditions.moonPhases.stream()
                    .map(phase -> I18n.format("gui.mobtracker.moonphase." + phase))
                    .collect(Collectors.joining(sep));
                String moonText = I18n.format("gui.mobtracker.moonphase", moonPhasesStr);
                textY = drawWrappedString(fontRenderer, moonText, condsX, textY, 12, textW, skyColor);
            }

            // Display slime chunk requirement if determined
            if (spawnConditions.requiresSlimeChunk != null) {
                String slimeKey = spawnConditions.requiresSlimeChunk ? "required" : "excluded";
                String slimeText = I18n.format("gui.mobtracker.slimechunk." + slimeKey);
                textY = drawWrappedString(fontRenderer, slimeText, condsX, textY, 12, textW, skyColor);
            }

            // Display nether requirement if determined
            if (spawnConditions.requiresNether != null) {
                String netherKey = spawnConditions.requiresNether ? "required" : "excluded";
                String netherText = I18n.format("gui.mobtracker.nether." + netherKey);
                textY = drawWrappedString(fontRenderer, netherText, condsX, textY, 12, textW, skyColor);
            }
        } else {
            String noConditions = I18n.format("gui.mobtracker.noSpawnConditions");
            textY = drawWrappedString(fontRenderer, noConditions, condsX, textY, 12, textW, 0xFFAAAA);

            // Draw retry button
            String retryText = I18n.format("gui.mobtracker.retryButton");
            retryButtonW = fontRenderer.getStringWidth(retryText) + 8;
            retryButtonH = 12;
            retryButtonX = condsX;
            retryButtonY = textY + 2;
            retryButtonVisible = true;

            boolean retryHovered = mouseX >= retryButtonX && mouseX <= retryButtonX + retryButtonW &&
                                   mouseY >= retryButtonY && mouseY <= retryButtonY + retryButtonH;
            int retryBtnBg = retryHovered ? 0x60FFFFFF : 0x40FFFFFF;
            int retryBtnColor = retryHovered ? 0xFFFFAA : 0xCCCCCC;

            drawRect(retryButtonX, retryButtonY, retryButtonX + retryButtonW, retryButtonY + retryButtonH, retryBtnBg);
            fontRenderer.drawString(retryText, retryButtonX + 4, retryButtonY + 2, retryBtnColor);

            textY = retryButtonY + retryButtonH + 4;
        }

        // Format dimension as "ID (name)" where name is the translated dimension name
        String dimDisplay;
        boolean isDimensionUnknown = false;
        if (spawnConditions.dimension != null) {
            String translatedName = TranslationUtils.translateDimensionName(spawnConditions.dimension);
            if (spawnConditions.dimensionId != Integer.MIN_VALUE) {
                dimDisplay = spawnConditions.dimensionId + " (" + translatedName + ")";
            } else {
                dimDisplay = "? (" + translatedName + ")";
            }
        } else {
            dimDisplay = "?";
            isDimensionUnknown = true;
        }
        String dimension = I18n.format("gui.mobtracker.dimension", dimDisplay);
        int dimensionLabelYPos = textY;
        textY = drawWrappedString(fontRenderer, dimension, condsX, textY, 12, textW, dimensionColor);

        // Store dimension tooltip data for rendering when dimension is unknown
        showDimensionUnknownTooltip = isDimensionUnknown;
        if (isDimensionUnknown) {
            dimensionLabelX = condsX;
            dimensionLabelY = dimensionLabelYPos;
            dimensionLabelW = fontRenderer.getStringWidth(dimension);
        }

        int biomesCount = spawnConditions.biomes.size();
        boolean hasBiomes = biomesCount > 0;
        boolean isUnknownBiome = biomesCount == 1 && spawnConditions.biomes.get(0).equals("unknown");
        boolean isAnyBiome = biomesCount == 1 && spawnConditions.biomes.get(0).equals("any");

        // Deduplicate biomes
        List<String> uniqueBiomes = new ArrayList<>(new LinkedHashSet<>(spawnConditions.biomes));
        int uniqueBiomesCount = uniqueBiomes.size();

        String biomesLabel;
        if (!hasBiomes) {
            biomesLabel = I18n.format("gui.mobtracker.biomes.none");
        } else if (isUnknownBiome) {
            biomesLabel = I18n.format("gui.mobtracker.biomes.unknown");
        } else if (isAnyBiome) {
            biomesLabel = I18n.format("gui.mobtracker.biomes.any");
        } else if (uniqueBiomesCount == 1) {
            biomesLabel = I18n.format("gui.mobtracker.biomes", TranslationUtils.translateBiomeName(uniqueBiomes.get(0)));
        } else {
            biomesLabel = I18n.format("gui.mobtracker.biomes", uniqueBiomesCount);
        }

        int biomesLabelY = textY;
        textY = drawSingleString(fontRenderer, biomesLabel, condsX, textY, 12, biomeColor);

        List<String> hints = spawnConditions.hints;
        if (!hints.isEmpty()) {
            textY += 10;
            textY = drawWrappedString(fontRenderer, I18n.format("gui.mobtracker.hintsHeader"), textX, textY, 10, textW, color);

            for (String hint : hints) textY = drawWrappedString(fontRenderer, "- " + hint, textX + 6, textY, 12, textW, hintColor);
        }

        // Store biome tooltip data for later rendering (after buttons are drawn)
        biomeTooltipWidget.clear();
        if (uniqueBiomesCount > 1) {
            // Sort biomes: minecraft: first, then alphabetically by localized name
            List<String> sortedBiomes = new ArrayList<>(uniqueBiomes);
            sortedBiomes.sort((a, b) -> {
                boolean aMinecraft = a.startsWith("minecraft:");
                boolean bMinecraft = b.startsWith("minecraft:");
                if (aMinecraft != bMinecraft) return aMinecraft ? -1 : 1;

                return TranslationUtils.translateBiomeName(a).compareToIgnoreCase(TranslationUtils.translateBiomeName(b));
            });

            // Remove REID warning biome if present
            sortedBiomes = sortedBiomes.stream().filter(b -> !b.contains("jeid:error_biome")).collect(Collectors.toList());

            // Translate biome names for display
            List<String> translatedBiomes = sortedBiomes.stream().map(TranslationUtils::translateBiomeName).collect(Collectors.toList());

            // Deduplicate biomes for tooltip
            translatedBiomes = new ArrayList<>(new LinkedHashSet<>(translatedBiomes));

            biomeTooltipWidget.setData(translatedBiomes, condsX, biomesLabelY, fontRenderer.getStringWidth(biomesLabel));
            biomeTooltipWidget.updateScreenSize(width, height);
        }
    }

    private void drawPreviewTooltip(int mouseX, int mouseY) {
        if (selected == null || previewSize <= 0) return;
        if (previewModal != null && previewModal.isVisible()) return;

        boolean showTooltip = mouseX >= previewX && mouseX <= previewX + previewSize &&
            mouseY >= previewY && mouseY <= previewY + previewSize;
        if (!showTooltip) return;

        drawHoveringText(Collections.singletonList(I18n.format("gui.mobtracker.clickToEnlarge")), mouseX, mouseY);
    }

    private void drawDimensionTooltip(int mouseX, int mouseY) {
        if (!showDimensionUnknownTooltip) return;

        boolean showTooltip = mouseX >= dimensionLabelX && mouseX <= dimensionLabelX + dimensionLabelW &&
            mouseY >= dimensionLabelY && mouseY <= dimensionLabelY + 12;
        if (!showTooltip) return;

        String tooltipKey = BiomeDimensionMapper.isBackgroundSamplingActive()
            ? "gui.mobtracker.dimension.unknown.tooltip.scanning"
            : "gui.mobtracker.dimension.unknown.tooltip.complete";
        String tooltipText = I18n.format(tooltipKey);
        drawHoveringText(Collections.singletonList(tooltipText), mouseX, mouseY);
    }

    /**
     * Formats the entity name based on settings.
     * @param id Entity ResourceLocation
     * @param applyI18n Whether to apply internationalization
     * @return Formatted entity name
     */
    private String formatEntityName(ResourceLocation id, boolean applyI18n) {
        return TranslationUtils.formatEntityName(id, analyzer.getEntityInstance(id), applyI18n);
    }

    class MobListWidget {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private FontRenderer fontRenderer;
        private GuiMobTracker tracker;
        private int blacklistTooltipX = 0;
        private int blacklistTooltipY = 0;
        private String blacklistTooltipText = null;

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
            String filterLower = this.filter.toLowerCase();

            // Filter by both raw ID and i18n name
            List<ResourceLocation> base = all.stream()
                .filter(id -> {
                    if (id.toString().toLowerCase().contains(filterLower)) return true;

                    String i18nName = tracker.formatEntityName(id, true).toLowerCase();

                    return i18nName.contains(filterLower);
                })
                .collect(Collectors.toList());

            List<ResourceLocation> trackedTop = base.stream().filter(SpawnTrackerManager::isTracked).collect(Collectors.toList());
            List<ResourceLocation> rest = base.stream().filter(id -> !SpawnTrackerManager.isTracked(id)).collect(Collectors.toList());

            // Build entries with unique display names
            filteredSortedRaw.clear();
            filteredSortedI18n.clear();

            // Count occurrences of each i18n name to detect duplicates (across all entries)
            Map<String, Integer> i18nNameCounts = new HashMap<>();
            for (ResourceLocation id : base) {
                String i18nName = tracker.formatEntityName(id, true);
                i18nNameCounts.merge(i18nName, 1, Integer::sum);
            }

            // Helper to create entries with disambiguation for duplicates
            BiConsumer<List<ResourceLocation>, Boolean> buildEntries = (ids, isTracked) -> {
                List<MobEntry> rawEntries = new ArrayList<>();
                List<MobEntry> i18nEntries = new ArrayList<>();

                for (ResourceLocation id : ids) {
                    String rawName = tracker.formatEntityName(id, false);
                    String i18nName = tracker.formatEntityName(id, true);

                    // If this i18n name appears multiple times, append the entity ID to disambiguate
                    if (i18nNameCounts.get(i18nName) > 1) i18nName = i18nName + " (" + id.toString() + ")";

                    rawEntries.add(new MobEntry(rawName, id));
                    i18nEntries.add(new MobEntry(i18nName, id));
                }

                // Sort by display name
                rawEntries.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
                i18nEntries.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

                filteredSortedRaw.addAll(rawEntries);
                filteredSortedI18n.addAll(i18nEntries);
            };

            // Build tracked entries first (they appear at top, sorted within themselves)
            buildEntries.accept(trackedTop, true);

            // Then build non-tracked entries (sorted within themselves)
            buildEntries.accept(rest, false);
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
            if (keyCode == Keyboard.KEY_UP) direction = -1; // up
            else if (keyCode == Keyboard.KEY_DOWN) direction = 1; // down
            else if (keyCode == Keyboard.KEY_PRIOR) direction = -10; // page up
            else if (keyCode == Keyboard.KEY_NEXT) direction = 10; // page down
            else return false;
            ensureI18nSync();

            ResourceLocation selected = tracker.getSelectedEntity();
            if (selected == null) return false;

            List<MobEntry> display = getDisplayList();
            int selectedIndex = findIndexById(selected);
            if (selectedIndex == -1) return false;

            int oldIndex = selectedIndex;
            selectedIndex = Math.min(display.size() - 1, Math.max(0, selectedIndex + direction));
            if (selectedIndex == oldIndex) return true;

            ResourceLocation newId = display.get(selectedIndex).id;
            if (newId != null) {
                tracker.selectEntity(newId);
                ensureVisible(selectedIndex);
            }

            return true;
        }

        void draw(int mouseX, int mouseY) {
            ensureI18nSync();

            List<MobEntry> displayList = getDisplayList();
            ResourceLocation selectedId = tracker.getSelectedEntity();
            int selectedIndex = selectedId != null ? findIndexById(selectedId) : -1;

            blacklistTooltipText = null;

            GlStateManager.pushMatrix();
            int visible = h / 12;
            int totalToDraw = Math.max(0, Math.min(displayList.size() - scrollOffset, visible));
            for (int i = 0; i < totalToDraw; i++) {
                int drawY = y + i * 12;
                int listIndex = scrollOffset + i;
                boolean isSel = selectedIndex == listIndex;
                if (isSel) Gui.drawRect(x, drawY, x + w - 6, drawY + 12, 0x40FFFFFF);

                MobEntry entry = displayList.get(listIndex);
                String text = entry.displayName;
                int maxWidth = w - 10;
                String elided = fontRenderer.trimStringToWidth(text, maxWidth - fontRenderer.getStringWidth("..."));
                if (!elided.equals(text)) elided += "...";
                fontRenderer.drawString(elided, x + 4, drawY + 2, isSel ? 0xFFFFA0 : 0xFFFFFF);

                float iconCenterX = x + w - 16;
                float iconCenterY = drawY + 6;
                float iconRadius = 4.0f;

                boolean tracked = SpawnTrackerManager.isTracked(entry.id);
                ModConfig.FilterReason reason = ModConfig.getFilterReason(entry.id.toString());

                if (tracked) {
                    GuiDrawingUtils.drawStar(iconCenterX, iconCenterY, iconRadius, 0xFFFFD700);

                    if (reason != ModConfig.FilterReason.NONE) {        // Draw red X over the star
                        float bx1 = iconCenterX - iconRadius * 0.7f;
                        float by1 = iconCenterY - iconRadius * 0.7f;
                        float bx2 = iconCenterX + iconRadius * 0.7f;
                        float by2 = iconCenterY + iconRadius * 0.7f;
                        GuiDrawingUtils.drawRedX(bx1, by1, bx2, by2, 3.0f, 0xFFFF4444);
                    }
                } else if (reason != ModConfig.FilterReason.NONE) {     // Draw stop sign for filtered entities
                    GuiDrawingUtils.drawStopSign(iconCenterX, iconCenterY, iconRadius + 0.5f, 0xE0FF4444);
                }

                // Tooltip for stop sign or red X if hovered
                if (reason != ModConfig.FilterReason.NONE) {
                    float iconLeft = iconCenterX - iconRadius;
                    float iconTop = iconCenterY - iconRadius;
                    float iconRight = iconCenterX + iconRadius;
                    float iconBottom = iconCenterY + iconRadius;

                    if (mouseX >= iconLeft && mouseX <= iconRight && mouseY >= iconTop && mouseY <= iconBottom) {
                        String tip = reason == ModConfig.FilterReason.BLACKLISTED
                            ? I18n.format("gui.mobtracker.blacklisted")
                            : I18n.format("gui.mobtracker.whitelisted");
                        blacklistTooltipX = mouseX;
                        blacklistTooltipY = mouseY;
                        blacklistTooltipText = tip;
                    }
                }
            }

            drawScrollbar();
            GlStateManager.popMatrix();
        }

        public void drawTooltips(int mouseX, int mouseY) {
            if (blacklistTooltipText != null) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(0.0F, 0.0F, 400.0F);
                tracker.drawHoveringText(Collections.singletonList(blacklistTooltipText), blacklistTooltipX, blacklistTooltipY);
                GlStateManager.popMatrix();
            }
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
