package com.supermobtracker.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import com.supermobtracker.client.ClientSettings;
import com.supermobtracker.client.util.GuiDrawingUtils;
import com.supermobtracker.config.ModConfig;
import com.supermobtracker.spawn.SpawnConditionAnalyzer;
import com.supermobtracker.tracking.SpawnTrackerManager;
import com.supermobtracker.util.TranslationUtils;


/**
 * A full-screen tiled gallery view showing entity preview cards.
 * Serves as an alternative selection screen for the mob tracker GUI.
 * Hovering a card shows the entity name with instructions; clicking selects it
 * and returns to the normal detail view. Esc returns without selecting.
 */
public class GuiGalleryView {

    private final GuiScreen parent;
    private final SpawnConditionAnalyzer analyzer;
    private final FontRenderer fontRenderer;

    private boolean visible = false;
    private int screenWidth, screenHeight;

    // Layout constants
    private static final int TILE_PADDING = 4;
    private static final int MARGIN = 10;

    // Computed layout
    private int tileSize;
    private int columns;
    private int rows;
    private int gridX, gridY, gridW, gridH;
    private int scrollRow = 0;
    private int totalRows;

    // Entity list (filtered and sorted same as MobListWidget)
    private List<GalleryEntry> entries = new ArrayList<>();
    private boolean lastI18n = ClientSettings.i18nNames;

    // Selected entity to return to the caller
    private ResourceLocation selectedResult = null;

    // Tooltip state (set during draw, rendered afterward)
    private String tooltipName = null;
    private int tooltipMouseX, tooltipMouseY;
    private Float rotationLocked = null;

    private static class GalleryEntry {
        final ResourceLocation id;
        final String displayName;

        GalleryEntry(ResourceLocation id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    public GuiGalleryView(GuiScreen parent, SpawnConditionAnalyzer analyzer, FontRenderer fontRenderer) {
        this.parent = parent;
        this.analyzer = analyzer;
        this.fontRenderer = fontRenderer;
    }

    /**
     * Shows the gallery view, rebuilding layout for the current screen size.
     * @param width Screen width
     * @param height Screen height
     */
    public void show(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.visible = true;
        this.selectedResult = null;
        this.scrollRow = 0;
        this.lastI18n = ClientSettings.i18nNames;
        this.rotationLocked = null;

        rebuildEntries();
        recalculateLayout();
    }

    /**
     * Updates screen dimensions (e.g. on resize) without resetting scroll.
     * @param width New screen width
     * @param height New screen height
     */
    public void updateScreenSize(int width, int height) {
        if (!visible) return;

        this.screenWidth = width;
        this.screenHeight = height;
        recalculateLayout();
    }

    /**
     * Hides the gallery view.
     */
    public void hide() {
        this.visible = false;
        this.rotationLocked = null;
    }

    /**
     * @return true if the gallery view is currently visible
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * @return The entity selected by the user, or null if none yet
     */
    public ResourceLocation consumeSelection() {
        ResourceLocation result = selectedResult;
        selectedResult = null;

        return result;
    }

    /**
     * Handles a key press.
     * @param keyCode The key code
     * @return true if the key was consumed
     */
    public boolean handleKey(int keyCode) {
        if (!visible) return false;

        // Toggle rotation lock with SPACE
        if (keyCode == Keyboard.KEY_SPACE) {
            if (rotationLocked == null) {
                rotationLocked = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;
            } else {
                rotationLocked = null;
            }

            return true;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            hide();

            return true;
        }

        return false;
    }

    /**
     * Handles a mouse click.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param mouseButton Which button was clicked
     * @return true if the click was consumed
     */
    public boolean handleClick(int mouseX, int mouseY, int mouseButton) {
        if (!visible) return false;
        if (mouseButton != 0) return true;

        // Check if click is on a tile
        int index = getTileIndexAt(mouseX, mouseY);
        if (index >= 0 && index < entries.size()) {
            selectedResult = entries.get(index).id;
            hide();

            return true;
        }

        // Consume click even if not on a tile (don't pass through to parent)
        return true;
    }

    /**
     * Handles mouse scroll.
     * @param direction Positive = scroll down, negative = scroll up
     */
    public void handleScroll(int direction) {
        if (!visible) return;

        scrollRow += direction;
        clampScroll();
    }

    /**
     * Draws the gallery view.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param partialTicks Partial tick time
     */
    public void draw(int mouseX, int mouseY, float partialTicks) {
        if (!visible) return;

        ensureI18nSync();

        // Draw semi-transparent background
        Gui.drawRect(0, 0, screenWidth, screenHeight, 0xC0101010);

        // Title
        String title = I18n.format("gui.mobtracker.gallery.title");
        int titleW = fontRenderer.getStringWidth(title);
        fontRenderer.drawString(title, (screenWidth - titleW) / 2, MARGIN / 2 + 2, 0xFFFFFF);

        // Reset tooltip state
        tooltipName = null;

        // Draw visible tiles
        Float rotationY = rotationLocked;
        if (rotationLocked == null) rotationY = (System.currentTimeMillis() % 10000L) / 10000.0f * 360.0f;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int entryIndex = (scrollRow + row) * columns + col;
                if (entryIndex < 0 || entryIndex >= entries.size()) continue;

                GalleryEntry entry = entries.get(entryIndex);
                int tileX = gridX + col * (tileSize + TILE_PADDING);
                int tileY = gridY + row * (tileSize + TILE_PADDING);

                // Check hover state
                boolean hovered = mouseX >= tileX && mouseX < tileX + tileSize &&
                                  mouseY >= tileY && mouseY < tileY + tileSize;

                // Draw tile background
                int bgColor = hovered ? 0xFF505050 : 0xFF303030;
                int borderColor = hovered ? 0xFFAAAAAA : 0xFF606060;
                Gui.drawRect(tileX - 1, tileY - 1, tileX + tileSize + 1, tileY + tileSize + 1, borderColor);
                Gui.drawRect(tileX, tileY, tileX + tileSize, tileY + tileSize, bgColor);

                // Draw entity preview (skip if blacklisted for rendering)
                if (!ModConfig.shouldRenderEntity(entry.id.toString())) {
                    Entity entity = analyzer.getEntityInstance(entry.id);
                    if (entity != null) {
                        // Use a slightly smaller area for the preview to leave room for the name
                        int previewSize = tileSize - 4;
                        int previewX = tileX + 2;
                        int previewY = tileY + 2;
                        GuiDrawingUtils.drawMobPreview(entry.id, entity, previewX, previewY, previewSize, rotationY);
                    }
                }

                // Set tooltip if hovered
                if (hovered) {
                    tooltipName = entry.displayName;
                    tooltipMouseX = mouseX;
                    tooltipMouseY = mouseY;
                }
            }
        }

        // Draw scroll indicator at bottom
        if (totalRows > rows) {
            String scrollInfo = I18n.format("gui.mobtracker.gallery.scrollInfo",
                scrollRow + 1, totalRows, rows);
            int scrollInfoW = fontRenderer.getStringWidth(scrollInfo);
            fontRenderer.drawString(scrollInfo, (screenWidth - scrollInfoW) / 2,
                screenHeight - MARGIN + 2, 0xAAAAAA);
        }
    }

    /**
     * Draws tooltips after the main draw pass.
     * Must be called separately to ensure tooltips render on top.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    public void drawTooltips(int mouseX, int mouseY) {
        if (!visible || tooltipName == null) return;

        List<String> lines = new ArrayList<>();
        lines.add(tooltipName);
        lines.add("");
        lines.add(I18n.format("gui.mobtracker.gallery.clickToSelect"));
        lines.add(I18n.format("gui.mobtracker.gallery.escToExit"));
        lines.add(I18n.format("gui.mobtracker.gallery.spaceToStopRotation"));

        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 400.0F);
        ((GuiScreen) parent).drawHoveringText(lines, tooltipMouseX, tooltipMouseY);
        GlStateManager.popMatrix();
    }

    // --- Internal helpers ---

    private void rebuildEntries() {
        entries.clear();

        // Get all entities that have a valid instance (same logic as MobListWidget)
        List<ResourceLocation> ids = ForgeRegistries.ENTITIES.getKeys().stream()
            .filter(id -> analyzer.getEntityInstance(id) != null)
            .collect(Collectors.toList());

        // Count i18n name occurrences for disambiguation
        Map<String, Integer> i18nNameCounts = new HashMap<>();
        for (ResourceLocation id : ids) {
            String name = formatEntityName(id, true);
            i18nNameCounts.merge(name, 1, Integer::sum);
        }

        // Build entries with disambiguation
        List<GalleryEntry> batch = new ArrayList<>();

        for (ResourceLocation id : ids) {
            String name = formatEntityName(id, ClientSettings.i18nNames);
            if (ClientSettings.i18nNames && i18nNameCounts.getOrDefault(name, 1) > 1) {
                name = name + " (" + id.toString() + ")";
            }

            batch.add(new GalleryEntry(id, name));
        }

        batch.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        entries.addAll(batch);
    }

    private void ensureI18nSync() {
        boolean curr = ClientSettings.i18nNames;
        if (curr == lastI18n) return;

        lastI18n = curr;
        rebuildEntries();
    }

    private void recalculateLayout() {
        // Available area for the grid (leave margins for title at top and scroll info at bottom)
        int availableW = screenWidth - MARGIN * 2;
        int availableH = screenHeight - MARGIN * 3;

        // Compute tile size: aim for ~64px tiles, but adapt to screen
        // Try to fit at least 4 columns on small screens, more on larger ones
        tileSize = 64;
        columns = Math.max(1, (availableW + TILE_PADDING) / (tileSize + TILE_PADDING));

        // If we get too few columns, shrink tiles
        if (columns < 4 && availableW >= 4 * 32 + 3 * TILE_PADDING) {
            tileSize = (availableW - 3 * TILE_PADDING) / 4;
            columns = 4;
        }

        // Visible rows that fit on screen
        rows = Math.max(1, (availableH + TILE_PADDING) / (tileSize + TILE_PADDING));

        // Total rows of entries
        totalRows = (entries.size() + columns - 1) / columns;

        // Center the grid horizontally
        gridW = columns * tileSize + (columns - 1) * TILE_PADDING;
        gridH = rows * tileSize + (rows - 1) * TILE_PADDING;
        gridX = (screenWidth - gridW) / 2;
        gridY = MARGIN * 2;

        clampScroll();
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, totalRows - rows);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll));
    }

    /**
     * Returns the index in entries of the tile at the given screen coordinates,
     * or -1 if no tile is there.
     */
    private int getTileIndexAt(int mouseX, int mouseY) {
        if (mouseX < gridX || mouseY < gridY) return -1;

        int relX = mouseX - gridX;
        int relY = mouseY - gridY;

        int col = relX / (tileSize + TILE_PADDING);
        int row = relY / (tileSize + TILE_PADDING);

        // Ensure click is actually on a tile, not in the padding between tiles
        int tileLocalX = relX - col * (tileSize + TILE_PADDING);
        int tileLocalY = relY - row * (tileSize + TILE_PADDING);
        if (tileLocalX >= tileSize || tileLocalY >= tileSize) return -1;
        if (col >= columns || row >= rows) return -1;

        int index = (scrollRow + row) * columns + col;
        if (index < 0 || index >= entries.size()) return -1;

        return index;
    }

    private String formatEntityName(ResourceLocation id, boolean applyI18n) {
        return TranslationUtils.formatEntityName(id, analyzer.getEntityInstance(id), applyI18n);
    }
}
