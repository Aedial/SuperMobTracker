package com.supermobtracker.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.stream.Collectors;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.client.config.GuiUtils;

import com.supermobtracker.client.ClientSettings;
import com.supermobtracker.client.gui.widgets.GuiBiomeWidget;
import com.supermobtracker.client.gui.widgets.GuiMobListEntryWidget;
import com.supermobtracker.client.gui.widgets.GuiRetryTextButton;
import com.supermobtracker.client.gui.widgets.GuiHintArgument;
import com.supermobtracker.client.gui.widgets.GuiHintBlockArgument;
import com.supermobtracker.client.gui.widgets.GuiHintEntityArgument;
import com.supermobtracker.client.gui.widgets.GuiHintItemArgument;
import com.supermobtracker.client.gui.widgets.GuiUnknownDimensionWidget;
import com.supermobtracker.client.gui.widgets.MultiColumnTooltipWidget;
import com.supermobtracker.client.util.GuiDrawingUtils;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.drops.DropSimulator;
import com.supermobtracker.integration.jei.JEIHelper;
import com.supermobtracker.spawn.ConditionUtils;
import com.supermobtracker.spawn.SpawnConditionAnalyzer;
import com.supermobtracker.tracking.SpawnTrackerManager;
import com.supermobtracker.util.TranslationUtils;
import com.supermobtracker.util.Utils;


public class GuiMobTracker extends GuiScreen {
    private GuiTextField filterField;
    private MobListWidget listWidget;
    private ResourceLocation selected;
    private final SpawnConditionAnalyzer analyzer = new SpawnConditionAnalyzer();
    private SpawnConditionAnalyzer.SpawnConditions spawnConditions;
    private long lastClickTime = 0L;
    private ResourceLocation lastClickId = null;
    private boolean initialized;

    // Cache for spawn conditions to avoid regenerating on window resize
    private static ResourceLocation cachedEntityId = null;
    private static SpawnConditionAnalyzer.SpawnConditions cachedSpawnConditions = null;

    private GuiTrackerTextButton dropsButton;
    private GuiTrackerTextButton jerButton;
    private GuiTrackerTextButton lootJeiButton;
    private GuiRetryTextButton retryButton;

    // Drops window modal
    private GuiDropsWindow dropsWindow = null;

    // Entity preview modal
    private GuiEntityPreviewModal previewModal = null;

    // Gallery view (alternative tiled selection screen)
    private GuiGalleryView galleryView = null;

    // Preview bounds (updated during draw)
    private int previewX, previewY, previewSize;

    // Panel bounds for fixed text
    private int panelMaxY = Integer.MAX_VALUE;

    private final GuiScrollableWidgetList panelWidgets = new GuiScrollableWidgetList();
    private GuiScrollableWidget biomeWidget;

    // Biome tooltip widget
    private MultiColumnTooltipWidget biomeTooltipWidget;

    private static final int lightColor = 0xFFFFAA;
    private static final int ylevelColor = 0xAAAAFF;
    private static final int groundColor = 0xAAFFAA;
    private static final int timeOfDayColor = 0xFFAAFF;
    private static final int weatherColor = 0xAABBFF;
    private static final int skyColor = 0xAAFFFF;
    private static final int dimensionColor = 0xFFDDAA;
    private static final int biomeColor = 0xAADDFF;
    private static final int hintColor = 0xFFAAAA;

    private final ResourceLocation initialSelected;
    private final GuiScreen returnScreen;

    public GuiMobTracker() {
        this(null, null);
    }

    /**
     * Opens the tracker with an entity selected, for navigation back from the JEI loot page.
     */
    public GuiMobTracker(ResourceLocation initialSelected) {
        this(initialSelected, null);
    }

    public GuiMobTracker(ResourceLocation initialSelected, GuiScreen returnScreen) {
        this.initialSelected = initialSelected;
        this.returnScreen = returnScreen;
    }

    private String getI18nButtonString() {
        return I18n.format("gui.supermobtracker.i18nIDs." + (ClientSettings.i18nNames ? "on" : "off"));
    }

    @Override
    public void initGui() {
        int leftWidth = Math.min(width / 2, 250);
        filterField = new GuiTextField(0, fontRenderer, 10, 10, leftWidth - 20, 14);
        filterField.setText(ModConfig.getClientFilterText());

        if (!initialized) {
            listWidget = new MobListWidget(10, 30, leftWidth - 20, height - 70, fontRenderer, this);

            dropsButton = new GuiTrackerTextButton(fontRenderer, I18n.format("gui.mobtracker.dropsButton"), 12);
            jerButton = new GuiTrackerTextButton(fontRenderer, I18n.format("gui.mobtracker.jerButton"), 12);
            lootJeiButton = new GuiTrackerTextButton(fontRenderer, I18n.format("gui.mobtracker.lootJeiButton"), 12);
            retryButton = new GuiRetryTextButton(fontRenderer, I18n.format("gui.mobtracker.retryButton"), 12, this);

            galleryView = new GuiGalleryView(this, analyzer, fontRenderer);

            biomeTooltipWidget = new MultiColumnTooltipWidget(fontRenderer);

            initialized = true;
        } else {
            listWidget.setBounds(10, 30, leftWidth - 20, height - 70);
        }

        this.buttonList.clear();
        this.buttonList.add(new GuiButton(1, 10, height - 30, leftWidth / 2 - 12, 20, getI18nButtonString()));
        this.buttonList.add(new GuiButton(2, 10 + leftWidth / 2 - 2, height - 30, leftWidth / 2 - 8, 20,
                I18n.format("gui.mobtracker.gallery.button")));

        if (galleryView.isVisible()) galleryView.updateScreenSize(width, height);

        biomeTooltipWidget.updateScreenSize(width, height);

        // Restore last selected entity and its cached spawn conditions
        ResourceLocation restoreId = initialSelected;
        if (restoreId == null) {
            String lastEntity = ModConfig.getClientLastSelectedEntity();
            if (lastEntity != null && !lastEntity.isEmpty()) {
                try {
                    restoreId = new ResourceLocation(lastEntity);
                } catch (Exception ignored) {
                    restoreId = null;
                }
            }
        }

        if (restoreId != null) {
            if (!ModConfig.isGuiAndLootExcludedEntity(restoreId.toString())
                && analyzer.getEntityInstance(restoreId) != null) {
                this.selected = restoreId;

                // Reuse cached spawn conditions if the entity ID matches
                if (restoreId.equals(cachedEntityId) && cachedSpawnConditions != null) {
                    this.spawnConditions = cachedSpawnConditions;
                } else {
                    this.spawnConditions = analyzer.getSpawnConditions(restoreId);
                    cachedEntityId = restoreId;
                    cachedSpawnConditions = this.spawnConditions;

                    DropSimulator.getOrStartSimulation(restoreId);
                }

                listWidget.ensureVisible(restoreId);
            }
        }

        // Restore drops window if it was hidden for JEI navigation
        if (dropsWindow != null) dropsWindow.restoreIfHiddenForJEI();

        if (selected == null) panelWidgets.setVisible(false);
        else rebuildPanelWidgets();
    }

    public void selectEntity(ResourceLocation id) {
        panelWidgets.scrollToTop();

        if (id == null || ModConfig.isGuiAndLootExcludedEntity(id.toString())) {
            this.selected = null;
            this.spawnConditions = null;
            cachedEntityId = null;
            cachedSpawnConditions = null;
            ModConfig.setClientLastSelectedEntity("");
            panelWidgets.setVisible(false);
            return;
        }

        this.selected = id;
        this.spawnConditions = analyzer.getSpawnConditions(id);
        cachedEntityId = id;
        cachedSpawnConditions = this.spawnConditions;
        ModConfig.setClientLastSelectedEntity(id != null ? id.toString() : "");

       // Start drop simulation in background so it may be ready when the player opens the drops window
        if (id != null) DropSimulator.getOrStartSimulation(id);
        rebuildPanelWidgets();
    }

    public ResourceLocation getSelectedEntity() {
        return this.selected;
    }

    public boolean copyBiomes(List<String> biomes) {
        if (selected == null) return false;

        GuiScreen.setClipboardString(String.join("\n", biomes));
        String entityName = formatEntityName(selected, true);
        mc.player.sendMessage(new TextComponentString(I18n.format("gui.mobtracker.biomesCopied", entityName)));
        return true;
    }

    public void selectHintEntity(ResourceLocation id) {
        selectEntity(id);
        listWidget.ensureVisible(id);
    }

    private boolean openDropsWindow() {
        if (selected == null) return false;

        String entityName = formatEntityName(selected, true);
        dropsWindow = new GuiDropsWindow(this, selected, entityName);
        dropsWindow.show();
        return true;
    }

    private boolean openJERPage() {
        if (selected == null || !JEIHelper.canShowJERMobPage(selected)) return false;

        JEIHelper.showJERMobPage(selected);
        return true;
    }

    private boolean openMobLootPage() {
        if (selected == null || !JEIHelper.canShowMobLootPage(selected)) return false;

        JEIHelper.showMobLootPage(selected);
        return true;
    }

    public boolean retrySelectedEntity() {
        if (selected == null) return false;

        // Clear cache and re-analyze
        cachedEntityId = null;
        cachedSpawnConditions = null;
        selectEntity(selected);
        return true;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            ClientSettings.toggleI18n();
            button.displayString = getI18nButtonString();
        } else if (button.id == 2) {
            if (galleryView != null) {
                // close all other views (they should have closed with the button click already)
                if (previewModal != null && previewModal.isVisible()) {
                    previewModal.hide();
                }
                if (dropsWindow != null && dropsWindow.isVisible()) {
                    dropsWindow.hide();
                }

                galleryView.show(width, height);
            }
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
        // Handle gallery view first if visible
        if (galleryView != null && galleryView.isVisible()) {
            if (galleryView.handleKey(keyCode)) {
                // After gallery closes, check if an entity was selected
                ResourceLocation gallerySelection = galleryView.consumeSelection();
                if (gallerySelection != null) {
                    selectEntity(gallerySelection);
                    listWidget.ensureVisible(gallerySelection);
                }

                return;
            }
        }

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

        if (shouldCloseTracker(keyCode)) {
            closeTracker();
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    private boolean isMouseOver(GuiTextField field, int mouseX, int mouseY) {
        return mouseX >= field.x && mouseX <= field.x + field.width &&
               mouseY >= field.y && mouseY <= field.y + field.height;
    }

    private boolean handlePanelButtonClick(int mouseX, int mouseY) {
        if (selected == null) return false;

        int leftWidth = Math.min(width / 2, 250);
        int panelX = leftWidth + 10;
        int panelW = width - panelX - 20;
        int buttonX = panelX + 6;
        int buttonY = getPanelButtonY(panelW);
        if (dropsButton.isHovered(buttonX, buttonY, mouseX, mouseY)) return openDropsWindow();

        buttonX += dropsButton.getWidth() + 4;
        if (JEIHelper.canShowJERMobPage(selected)) {
            if (jerButton.isHovered(buttonX, buttonY, mouseX, mouseY)) return openJERPage();

            buttonX += jerButton.getWidth() + 4;
        }
        if (JEIHelper.canShowMobLootPage(selected)
            && lootJeiButton.isHovered(buttonX, buttonY, mouseX, mouseY)) return openMobLootPage();

        return false;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Handle gallery view first if visible
        if (galleryView != null && galleryView.isVisible()) {
            if (galleryView.handleClick(mouseX, mouseY, mouseButton)) {
                // Check if an entity was selected
                ResourceLocation gallerySelection = galleryView.consumeSelection();
                if (gallerySelection != null) {
                    selectEntity(gallerySelection);
                    listWidget.ensureVisible(gallerySelection);
                }

                return;
            }
        }

        // Handle preview modal first if visible
        if (previewModal != null && previewModal.isVisible()) {
            if (previewModal.handleClick(mouseX, mouseY, mouseButton)) return;
        }

        // Handle drops window first if visible
        if (dropsWindow != null && dropsWindow.isVisible()) {
            if (dropsWindow.handleClick(mouseX, mouseY, mouseButton)) return;
        }

        // Right-click on filter field clears it
        if (mouseButton == 1 && isMouseOver(filterField, mouseX, mouseY)) {
            filterField.setText("");
        }

        filterField.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0) {
            // Handle entity preview click - open modal
            if (selected != null && previewSize > 0 &&
                mouseX >= previewX && mouseX <= previewX + previewSize &&
                mouseY >= previewY && mouseY <= previewY + previewSize) {
                Entity entity = analyzer.getInitializedEntityInstance(selected);
                if (entity != null) {
                    String entityName = formatEntityName(selected, true);
                    previewModal = new GuiEntityPreviewModal(this, selected, entity, entityName);
                    previewModal.show(width, height);

                    return;
                }
            }

            if (handlePanelButtonClick(mouseX, mouseY)) return;
        }

        if (panelWidgets.handleClick(mouseX, mouseY, mouseButton)) return;
        if (listWidget.handleClick(mouseX, mouseY, mouseButton)) return;

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        panelWidgets.onMouseDrag(mouseY);
        listWidget.onMouseDrag(mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        panelWidgets.onMouseRelease();
        listWidget.onMouseRelease();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();

        // Handle gallery view scroll if visible
        if (galleryView != null && galleryView.isVisible()) {
            int wheel = Mouse.getDWheel();
            if (wheel != 0) galleryView.handleScroll(wheel < 0 ? 1 : -1);

            return;
        }

        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

        // Don't scroll the list if drops window is visible and mouse is over it
        if (dropsWindow != null && dropsWindow.isVisible()) {
            if (dropsWindow.isMouseOver(mouseX, mouseY)) return;
        }

        int wheel = Mouse.getDWheel();
        if (wheel != 0 && panelWidgets.isMouseOver(mouseX, mouseY)) {
            panelWidgets.scroll(wheel < 0 ? 12 : -12);

            return;
        }

        if (wheel != 0) listWidget.scroll(wheel < 0 ? 12 : -12);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        // Determine if any modal should block hover events
        boolean previewModalBlocking = previewModal != null && previewModal.isVisible();
        boolean dropsWindowBlocking = dropsWindow != null && dropsWindow.isVisible() && dropsWindow.isMouseOver(mouseX, mouseY);
        boolean galleryViewBlocking = galleryView != null && galleryView.isVisible();
        boolean modalBlocking = previewModalBlocking || dropsWindowBlocking || galleryViewBlocking;
        int effectiveMouseX = modalBlocking ? -1 : mouseX;
        int effectiveMouseY = modalBlocking ? -1 : mouseY;

        filterField.drawTextBox();
        listWidget.draw(mc, effectiveMouseX, effectiveMouseY);
        drawRightPanel(effectiveMouseX, effectiveMouseY, partialTicks);

        super.drawScreen(mouseX, mouseY, partialTicks);

        // Draw tooltips only if no modal is blocking
        if (!modalBlocking) {
            if (panelWidgets.isHovered(biomeWidget)) biomeTooltipWidget.draw(mouseX, mouseY);
            drawPreviewTooltip(mouseX, mouseY);
            drawWidgetTooltip(panelWidgets, mouseX, mouseY);
            drawWidgetTooltip(listWidget, mouseX, mouseY);
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

        // Draw gallery view on top of everything else
        if (galleryView != null && galleryView.isVisible()) {
            galleryView.draw(mouseX, mouseY, partialTicks);
            galleryView.drawTooltips(mouseX, mouseY);
        }
    }

    private boolean shouldCloseTracker(int keyCode) {
        return keyCode == Keyboard.KEY_ESCAPE || mc.gameSettings.keyBindInventory.isActiveAndMatches(keyCode);
    }

    private void closeTracker() {
        mc.displayGuiScreen(returnScreen);
        if (returnScreen == null) mc.setIngameFocus();
    }

    private int drawElidedString(FontRenderer renderer, String text, int x, int y, int lineHeight, int maxWidth, int color) {
        if (y + lineHeight > panelMaxY) return y;

        String elided = renderer.trimStringToWidth(text, maxWidth - renderer.getStringWidth("..."));
        if (!elided.equals(text)) elided += "...";

        renderer.drawString(elided, x, y, color);

        return y + lineHeight;
    }

    private int getPanelButtonY(int panelW) {
        int panelY = 10;
        int panelH = height - 40;
        int maxY = panelY + panelH - 6;
        int fixedTextW = panelW - 12;
        int previewSize = Math.min(fixedTextW, panelH / 4);
        int textY = panelY + 6 + previewSize + 16;
        for (int i = 0; i < 3 && textY + 14 <= maxY; i++) textY += 14;

        return textY;
    }

    private String format(double d) {
        String s = String.format("%.1f", d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);

        return s;
    }

    private String formatSpawnReason(String spawnReason) {
        String normalized = spawnReason == null || spawnReason.trim().isEmpty()
            ? SpawnConditionAnalyzer.NATURAL_SPAWN_REASON
            : spawnReason.trim().toLowerCase(Locale.ROOT);
        String translationKey = "gui.mobtracker.spawnReason." + normalized;
        String translated = I18n.format(translationKey);

        if (!translationKey.equals(translated)) return translated;

        // FIXME: Should not provide a fallback, it makes it harder to detect missing translations
        String[] words = normalized.replace('-', ' ').replace('_', ' ').split("\\s+");
        StringBuilder builder = new StringBuilder();

        for (String word : words) {
            if (word.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) builder.append(word.substring(1));
        }

        return builder.toString();
    }

    private void drawRightPanel(int mouseX, int mouseY, float partialTicks) {
        int leftWidth = Math.min(width / 2, 250);
        int panelX = leftWidth + 10;
        int panelY = 10;
        int panelW = width - panelX - 20;
        int panelH = height - 40;
        panelMaxY = panelY + panelH - 6;

        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0x80000000);
        if (selected == null) {
            panelWidgets.setVisible(false);
            fontRenderer.drawString(I18n.format("gui.mobtracker.noMobSelected"), panelX + 6, panelY + 6, 0xFFFFFF);
            return;
        }

        int textX = panelX + 6;
        int textY = panelY + 6;
        int fixedTextW = panelW - 12;
        int color = 0xFFFFFF;

        // preview: up to a quarter of panel height, slowly rotating (full rotation every 10s)
        int previewSizeLocal = Math.min(fixedTextW, panelH / 4);
        this.previewX = textX + (fixedTextW - previewSizeLocal) / 2;
        this.previewY = textY;
        this.previewSize = previewSizeLocal;
        float previewRotationY = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;

        Entity entity = analyzer.getInitializedEntityInstance(selected);

        // If any modal is open, do not draw preview in panel to avoid overlap
        // Mob rendering has issues with overlapping GUI elements
        boolean renderBlacklisted = ModConfig.shouldRenderEntity(selected.toString())
            || (previewModal != null && previewModal.isVisible())
            || (galleryView != null && galleryView.isVisible());
        if (!renderBlacklisted) {
            GuiDrawingUtils.drawMobPreview(selected, entity, previewX, previewY, previewSize, previewRotationY);
        } else {
            // Draw placeholder for render-blacklisted entities
            drawRect(previewX - 1, previewY - 1, previewX + previewSize + 1, previewY + previewSize + 1, 0xFF808080);
            drawRect(previewX, previewY, previewX + previewSize, previewY + previewSize, 0xFF404040);
        }

        textY += previewSize + 16;

        String name = I18n.format("gui.mobtracker.entityName", formatEntityName(selected, true));
        textY = drawElidedString(fontRenderer, name, textX, textY, 14, fixedTextW, color);

        String entityIdStr = I18n.format("gui.mobtracker.entityId", selected.toString());
        textY = drawElidedString(fontRenderer, entityIdStr, textX, textY, 14, fixedTextW, color);

        Vec3d size = analyzer.getEntitySize(selected);
        String sizeStr = I18n.format("gui.mobtracker.entitySize", format(size.x), format(size.y), format(size.z));
        textY = drawElidedString(fontRenderer, sizeStr, textX, textY, 14, fixedTextW, color);

        // Drops button (always visible for selected entity)
        int buttonY = getPanelButtonY(panelW);
        dropsButton.draw(textX, buttonY, mouseX, mouseY);
        int nextButtonX = textX + dropsButton.getWidth() + 4;

        // JEI button (only if JEI is loaded and can show mob info)
        if (JEIHelper.canShowJERMobPage(selected)) {
            jerButton.draw(nextButtonX, buttonY, mouseX, mouseY);
            nextButtonX += jerButton.getWidth() + 4;
        }

        // Dumped loot category button (only present if the dumped loot file has data for this mob)
        if (JEIHelper.canShowMobLootPage(selected)) {
            lootJeiButton.draw(nextButtonX, buttonY, mouseX, mouseY);
        }

        textY = buttonY + dropsButton.getHeight() + 4;
        panelWidgets.setBounds(panelX + 1, textY, panelW - 2, Math.max(0, panelMaxY - textY));
        panelWidgets.setContentPadding(5, 9);
        panelWidgets.draw(mc, mouseX, mouseY);
    }

    private void rebuildPanelWidgets() {
        if (selected == null) {
            panelWidgets.setVisible(false);
            return;
        }

        String sep = I18n.format("gui.mobtracker.separator");
        String attributes = analyzer.getAttributes(selected).stream()
            .map(SpawnConditionAnalyzer.Attribute::getTranslated)
            .collect(Collectors.joining(sep));
        String attributeString = I18n.format("gui.mobtracker.attributes", attributes);
        GuiScrollableWidgetFactory widgets = new GuiScrollableWidgetFactory(fontRenderer);
        widgets.addWrappedText(attributeString, 0xFFFFFF, 14, 0, 0);

        biomeTooltipWidget.clear();
        biomeWidget = null;

        if (spawnConditions == null) {
            // Entities without native biomes (not in any spawn table) cannot spawn naturally.
            // SpawnConditions.analysisFailed identifies analysis errors in a populated result.
            widgets.addSpacing(10);
            widgets.addWrappedText(I18n.format("gui.mobtracker.cannotSpawnNaturally"), 0xFFAAAA, 12, 0, 0);
            widgets.addWrappedText(I18n.format("gui.mobtracker.cannotSpawnNaturallyHint"), 0xAAAAAA, 12, 0, 0);
            panelWidgets.replaceWidgets(widgets.build());
            panelWidgets.setVisible(true);
            return;
        }

        widgets.addSpacing(20);
        widgets.addText(I18n.format("gui.mobtracker.spawnConditions"), 0xFFFFFF, 12);

        String spawnReason = I18n.format("gui.mobtracker.spawnReason",
            formatSpawnReason(spawnConditions.spawnReason));
        widgets.addWrappedText(spawnReason, dimensionColor, 12, 6, 0);
        // Only show spawn condition details if they have valid data
        if (!spawnConditions.failed()) {
            if (!spawnConditions.lightLevels.isEmpty()) {
                String lightLevels = I18n.format("gui.mobtracker.lightLevels",
                    Utils.formatRangeFromList(spawnConditions.lightLevels, sep));
                widgets.addWrappedText(lightLevels, lightColor, 12, 6, 0);
            }

            if (!spawnConditions.yLevels.isEmpty()) {
                String yPos = I18n.format("gui.mobtracker.yPos",
                    Utils.formatRangeFromList(spawnConditions.yLevels, sep));
                widgets.addWrappedText(yPos, ylevelColor, 12, 6, 0);
            }

            // Show ground blocks only if this condition was queried (list is non-null)
            if (spawnConditions.groundBlocks != null) {
                // Limit displayed ground blocks to avoid excessively long lines
                int maxGroundBlocksToShow = 20;
                List<String> translatedGroundBlocksList = spawnConditions.groundBlocks.stream()
                    .map(TranslationUtils::translateBlockName).collect(Collectors.toList());
                List<String> groundBlocksList = new ArrayList<>(
                    new LinkedHashSet<>(translatedGroundBlocksList));  // Deduplicate
                if (groundBlocksList.size() > maxGroundBlocksToShow) {
                    groundBlocksList = groundBlocksList.subList(0, maxGroundBlocksToShow);
                    groundBlocksList.add("...");
                }

                String groundBlocks = I18n.format("gui.mobtracker.groundBlocks", String.join(sep, groundBlocksList));
                widgets.addWrappedText(groundBlocks, groundColor, 12, 6, 0);
            }

            // Show time of day only if queried (list is non-null)
            if (spawnConditions.timeOfDay != null && !spawnConditions.timeOfDay.isEmpty()) {
                String timeOfDayTL = Utils.formatTimeRanges(spawnConditions.timeOfDay, sep);
                String timeOfDay = I18n.format("gui.mobtracker.timeOfDay", timeOfDayTL);
                widgets.addWrappedText(timeOfDay, timeOfDayColor, 12, 6, 0);
            }

            // Show weather only if queried (list is non-null)
            if (spawnConditions.weather != null) {
                String weatherTL = String.join(sep,
                    ConditionUtils.translateList(spawnConditions.weather, "gui.mobtracker.weather"));
                String weather = I18n.format("gui.mobtracker.weather", weatherTL);
                widgets.addWrappedText(weather, weatherColor, 12, 6, 0);
            }

            // Display sky requirement if determined
            if (spawnConditions.requiresSky != null) {
                String skyKey = spawnConditions.requiresSky ? "outside" : "underground";
                widgets.addWrappedText(I18n.format("gui.mobtracker.sky." + skyKey), skyColor, 12, 6, 0);
            }

            // Display moon phases if determined
            if (spawnConditions.moonPhases != null && !spawnConditions.moonPhases.isEmpty()) {
                String moonPhasesStr = spawnConditions.moonPhases.stream()
                    .map(phase -> I18n.format("gui.mobtracker.moonphase." + phase))
                    .collect(Collectors.joining(sep));
                widgets.addWrappedText(I18n.format("gui.mobtracker.moonphase", moonPhasesStr), skyColor, 12, 6, 0);
            }

            // Display slime chunk requirement if determined
            if (spawnConditions.requiresSlimeChunk != null) {
                String slimeKey = spawnConditions.requiresSlimeChunk ? "required" : "excluded";
                widgets.addWrappedText(I18n.format("gui.mobtracker.slimechunk." + slimeKey), skyColor, 12, 6, 0);
            }

            // Display nether requirement if determined
            if (spawnConditions.requiresNether != null) {
                String netherKey = spawnConditions.requiresNether ? "required" : "excluded";
                widgets.addWrappedText(I18n.format("gui.mobtracker.nether." + netherKey), skyColor, 12, 6, 0);
            }
        } else {
            widgets.addWrappedText(I18n.format("gui.mobtracker.noSpawnConditions"), 0xFFAAAA, 12, 6, 0);

            // Add retry button
            widgets.addSpacing(2);
            widgets.addButton(retryButton);
            widgets.addSpacing(4);
        }

        // Format dimension as "ID (name)" where name is the translated dimension name
        String dimDisplay;
        boolean isDimensionUnknown = false;
        if (spawnConditions.dimension != null) {
            String translatedName = TranslationUtils.translateDimensionName(spawnConditions.dimension);
            dimDisplay = spawnConditions.dimensionId != Integer.MIN_VALUE
                ? spawnConditions.dimensionId + " (" + translatedName + ")" : "? (" + translatedName + ")";
        } else {
            dimDisplay = "?";
            isDimensionUnknown = true;
        }

        String dimension = I18n.format("gui.mobtracker.dimension", dimDisplay);
        if (isDimensionUnknown) {
            widgets.addWidget(new GuiUnknownDimensionWidget(fontRenderer, dimension, dimensionColor, 12, 6, 0));
        } else {
            widgets.addWrappedText(dimension, dimensionColor, 12, 6, 0);
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
            String biomeName = TranslationUtils.translateBiomeName(uniqueBiomes.get(0));
            biomesLabel = I18n.format("gui.mobtracker.biomes", biomeName);
        } else {
            biomesLabel = I18n.format("gui.mobtracker.biomes", uniqueBiomesCount);
        }

        List<String> translatedBiomes = Collections.emptyList();
        if (uniqueBiomesCount > 1) {
            // Sort biomes: minecraft: first, then alphabetically by localized name
            List<String> sortedBiomes = new ArrayList<>(uniqueBiomes);
            sortedBiomes.sort((a, b) -> {
                boolean aMinecraft = a.startsWith("minecraft:");
                boolean bMinecraft = b.startsWith("minecraft:");
                if (aMinecraft != bMinecraft) return aMinecraft ? -1 : 1;

                String biomeB = TranslationUtils.translateBiomeName(b);
                return TranslationUtils.translateBiomeName(a).compareToIgnoreCase(biomeB);
            });

            // Remove REID warning biome if present
            sortedBiomes = sortedBiomes.stream()
                .filter(b -> !b.contains("jeid:error_biome"))
                .collect(Collectors.toList());

            // Translate biome names for display
            translatedBiomes = sortedBiomes.stream()
                .map(TranslationUtils::translateBiomeName)
                .collect(Collectors.toList());

            // Deduplicate biomes for tooltip
            translatedBiomes = new ArrayList<>(new LinkedHashSet<>(translatedBiomes));
            // Give multi-biome tooltip data to the hovered biome widget
            biomeTooltipWidget.setData(translatedBiomes);
        }

        biomeWidget = widgets.addWidget(new GuiBiomeWidget(fontRenderer, biomesLabel, biomeColor, 12, 6, 0,
            this, translatedBiomes));

        List<SpawnConditionAnalyzer.SpawnHint> hints = spawnConditions.hints;
        if (!hints.isEmpty()) {
            widgets.addSpacing(10);
            widgets.addWrappedText(I18n.format("gui.mobtracker.hintsHeader"), 0xFFFFFF, 10, 0, 0);

            for (SpawnConditionAnalyzer.SpawnHint hint : hints) {
                GuiScrollableTextWidget widget = createHintWidget(hint);
                if (widget != null) widgets.addWidget(widget);
            }
        }

        panelWidgets.replaceWidgets(widgets.build());
        panelWidgets.setVisible(true);
    }

    private GuiScrollableTextWidget createHintWidget(SpawnConditionAnalyzer.SpawnHint hint) {
        List<GuiHintArgument> arguments = new ArrayList<>();
        for (SpawnConditionAnalyzer.HintArgument argument : hint.arguments) {
            if (argument.type == SpawnConditionAnalyzer.HintArgument.Type.ITEM) {
                Item item = ForgeRegistries.ITEMS.getValue(argument.id);
                if (item == null) return null;

                arguments.add(new GuiHintItemArgument(mc, new ItemStack(item, argument.count, argument.metadata)));
            } else if (argument.type == SpawnConditionAnalyzer.HintArgument.Type.BLOCK) {
                arguments.add(new GuiHintBlockArgument(mc, TranslationUtils.translateBlockName(argument.id.toString()),
                    argument.id, hintColor));
            } else if (argument.type == SpawnConditionAnalyzer.HintArgument.Type.ENTITY) {
                arguments.add(new GuiHintEntityArgument(formatEntityName(argument.id, true), this, argument.id));
            }
        }

        return new GuiScrollableTextWidget(fontRenderer, hint.key, "- " + I18n.format(hint.key), hintColor,
            hintColor, 12, 0, GuiScrollableTextWidget.Layout.WRAPPED, 6, 0, arguments);
    }

    public List<String> getHintEntityTooltip(ResourceLocation id) {
        String entityName = formatEntityName(id, true);
        List<String> tooltip = new ArrayList<>();
        tooltip.add(entityName);
        if (!entityName.equals(id.toString())) tooltip.add(id.toString());
        tooltip.add("");
        tooltip.add(I18n.format("gui.mobtracker.hint.entity"));
        return tooltip;
    }

    private void drawPreviewTooltip(int mouseX, int mouseY) {
        if (selected == null || previewSize <= 0) return;
        if (previewModal != null && previewModal.isVisible()) return;

        boolean showTooltip = mouseX >= previewX && mouseX <= previewX + previewSize &&
            mouseY >= previewY && mouseY <= previewY + previewSize;
        if (!showTooltip) return;

        drawHoveringText(Collections.singletonList(I18n.format("gui.mobtracker.clickToEnlarge")), mouseX, mouseY);
    }

    private void drawWidgetTooltip(GuiScrollableWidgetList widgets, int mouseX, int mouseY) {
        List<String> tooltip = widgets.getHoveredTooltip();
        if (tooltip.isEmpty()) return;

        ItemStack tooltipStack = widgets.getHoveredTooltipStack();

        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 500.0F);
        if (tooltipStack.isEmpty()) {
            drawHoveringText(tooltip, mouseX, mouseY);
        } else {
            GuiUtils.preItemToolTip(tooltipStack);
            GuiHintTooltipRenderer.beginIconTooltip(tooltipStack);
            try {
                drawHoveringText(GuiHintTooltipRenderer.addIconHeader(tooltip, fontRenderer), mouseX, mouseY);
            } finally {
                GuiHintTooltipRenderer.endIconTooltip();
                GuiUtils.postItemToolTip();
            }
        }
        GlStateManager.popMatrix();
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

    public void handleMobListSelection(ResourceLocation id) {
        long now = Minecraft.getSystemTime();
        boolean isDouble = id.equals(lastClickId) && now - lastClickTime < 500L;
        lastClickId = id;
        lastClickTime = now;
        selectEntity(id);

        if (isDouble) {
            SpawnTrackerManager.toggleTracked(id);
            ModConfig.setClientTrackedIds(SpawnTrackerManager.getTrackedIdStrings());
            SpawnTrackerManager.scanWorld(mc.world);
            listWidget.rebuildFiltered();
        }
    }

    class MobListWidget extends GuiScrollableWidgetList {
        private final FontRenderer fontRenderer;
        private final GuiMobTracker tracker;

        private final List<ResourceLocation> all = new ArrayList<>();
        // Store entries as (displayName, id) pairs to handle duplicate names
        private final List<MobEntry> filteredSortedRaw = new ArrayList<>();
        private final List<MobEntry> filteredSortedI18n = new ArrayList<>();
        private String filter = "";
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
            this.fontRenderer = fontRenderer;
            this.tracker = tracker;
            setBounds(x, y, w, h);
            setContentPadding(0, 6);

            all.addAll(ForgeRegistries.ENTITIES.getKeys().stream()
                .filter(id -> analyzer.getEntityInstance(id) != null)
                .collect(Collectors.toList()));

            rebuildFiltered();
            lastI18n = ClientSettings.i18nNames;
        }

        void setFilter(String filter) {
            if (filter == null) filter = "";
            String normalizedFilter = filter.toLowerCase();

            if (!normalizedFilter.equals(this.filter)) {
                this.filter = normalizedFilter;
                rebuildFiltered();
                scrollToTop();
            }
        }

        private void rebuildFiltered() {
            if (tracker.getSelectedEntity() != null
                && ModConfig.isGuiAndLootExcludedEntity(tracker.getSelectedEntity().toString())) {
                tracker.selectEntity(null);
            }

            String filterLower = this.filter.toLowerCase();

            // Filter by both raw ID and i18n name
            List<ResourceLocation> base = all.stream()
                .filter(id -> !ModConfig.isGuiAndLootExcludedEntity(id.toString()))
                .filter(id -> {
                    if (id.toString().toLowerCase().contains(filterLower)) return true;

                    String i18nName = tracker.formatEntityName(id, true).toLowerCase();

                    return i18nName.contains(filterLower);
                })
                .collect(Collectors.toList());

            List<ResourceLocation> trackedTop = base.stream().filter(SpawnTrackerManager::isTracked)
                                                             .collect(Collectors.toList());
            List<ResourceLocation> rest = base.stream().filter(id -> !SpawnTrackerManager.isTracked(id))
                                                       .collect(Collectors.toList());

            // Build entries with unique display names
            filteredSortedRaw.clear();
            filteredSortedI18n.clear();

            // Count occurrences of each i18n name to detect duplicates (across all entries)
            Map<String, Integer> i18nNameCounts = new HashMap<>();
            for (ResourceLocation id : base) {
                String i18nName = tracker.formatEntityName(id, true);
                i18nNameCounts.merge(i18nName, 1, Integer::sum);
            }

            // Build tracked entries first (they appear at top, sorted within themselves)
            addEntries(trackedTop, i18nNameCounts);

            // Then build non-tracked entries (sorted within themselves)
            addEntries(rest, i18nNameCounts);
            replaceWidgets();
        }

        private void addEntries(List<ResourceLocation> ids, Map<String, Integer> i18nNameCounts) {
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
        }

        private void replaceWidgets() {
            List<GuiScrollableWidget> widgets = new ArrayList<>();
            for (MobEntry entry : getDisplayList()) {
                widgets.add(new GuiMobListEntryWidget(fontRenderer, tracker, entry.id, entry.displayName));
            }
            super.replaceWidgets(widgets);
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

        @Override
        public boolean handleClick(int mouseX, int mouseY, int mouseButton) {
            ensureI18nSync();
            return super.handleClick(mouseX, mouseY, mouseButton);
        }

        /**
         * Ensure the entry at the given index is visible in the list.
         * @param index Index of the entry to make visible
         */
        public void ensureVisible(int index) {
            if (index != -1) super.ensureVisible(index);
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

        @Override
        public void draw(Minecraft minecraft, int mouseX, int mouseY) {
            ensureI18nSync();
            super.draw(minecraft, mouseX, mouseY);
        }
    }
}
