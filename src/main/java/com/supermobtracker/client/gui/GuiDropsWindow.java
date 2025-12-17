package com.supermobtracker.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.supermobtracker.drops.DropSimulator;
import com.supermobtracker.drops.DropSimulator.DropEntry;
import com.supermobtracker.drops.DropSimulator.DropSimulationResult;
import com.supermobtracker.drops.DropSimulator.SimulationTask;
import com.supermobtracker.util.JEIHelper;
import com.supermobtracker.util.Utils;


/**
 * Modal popup window that displays simulated mob drops.
 * Shows a grid of items with their drop rates.
 */
public class GuiDropsWindow {
    private static final int ITEM_SIZE = 18; // 16px item + 2px padding
    private static final int ITEM_PADDING = 4;
    private static final int ITEM_RATE_HEIGHT = 4;
    private static final int HEADER_HEIGHT = 20;
    private static final int FOOTER_HEIGHT = 16;

    private final GuiScreen parent;
    private final ResourceLocation entityId;
    private final String entityName;

    private boolean visible = false;
    private boolean hiddenForJEI = false;
    private int windowX, windowY, windowW, windowH;

    // Item layout
    private int columns, rows;
    private int gridStartX, gridStartY;

    // Hover state
    private int hoveredItemIndex = -1;
    private boolean hoveringDropRate = false;
    private int dropRateHoverIndex = -1;
    private boolean hoveringSimulationCount = false;

    // Current simulation state
    private SimulationTask currentTask = null;
    private DropSimulationResult currentResult = null;

    public GuiDropsWindow(GuiScreen parent, ResourceLocation entityId, String entityName) {
        this.parent = parent;
        this.entityId = entityId;
        this.entityName = entityName;
    }

    /**
     * Show the drops window and start simulation if needed.
     */
    public void show() {
        visible = true;
        hiddenForJEI = false;
        currentTask = DropSimulator.getOrStartSimulation(entityId);
        if (currentTask.completed) currentResult = currentTask.result;

        calculateLayout();
    }

    /**
     * Hide the drops window.
     */
    public void hide() {
        visible = false;
        hiddenForJEI = false;
        hoveredItemIndex = -1;
        hoveringDropRate = false;
        dropRateHoverIndex = -1;
        hoveringSimulationCount = false;
    }

    /**
     * Hide the drops window temporarily for JEI navigation.
     * The window can be restored via restoreIfHiddenForJEI().
     */
    private void hideForJEI() {
        visible = false;
        hiddenForJEI = true;
        hoveredItemIndex = -1;
        hoveringDropRate = false;
        dropRateHoverIndex = -1;
        hoveringSimulationCount = false;
    }

    /**
     * Restore the drops window if it was hidden for JEI navigation.
     * Call this when the parent GUI regains focus after returning from JEI.
     * @return true if the window was restored
     */
    public boolean restoreIfHiddenForJEI() {
        if (hiddenForJEI) {
            visible = true;
            hiddenForJEI = false;
            calculateLayout();

            return true;
        }

        return false;
    }

    /**
     * Check if the window was hidden for JEI navigation.
     */
    public boolean isHiddenForJEI() {
        return hiddenForJEI;
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * Update the window state (check for simulation completion).
     */
    public void update() {
        if (!visible || currentTask == null) return;

        if (currentTask.completed && currentResult == null) {
            currentResult = currentTask.result;
            calculateLayout();
        }
    }

    /**
     * Calculate window layout based on screen size and number of items.
     */
    private void calculateLayout() {
        Minecraft mc = Minecraft.getMinecraft();
        int screenW = mc.displayWidth / mc.gameSettings.guiScale;
        int screenH = mc.displayHeight / mc.gameSettings.guiScale;

        // Use scaled resolution for proper GUI coordinates
        ScaledResolution sr = new ScaledResolution(mc);
        screenW = sr.getScaledWidth();
        screenH = sr.getScaledHeight();

        float screenRatio = (float) screenW / screenH;

        int itemCount = 0;
        if (currentResult != null && currentResult.drops != null) itemCount = currentResult.drops.size();

        // Calculate grid dimensions to match screen ratio
        if (itemCount > 0) {
            // Start with a square-ish grid and adjust
            int itemsPerRow = Math.max(1, (int) Math.ceil(Math.sqrt(itemCount * screenRatio)));
            int rowCount = (int) Math.ceil((double) itemCount / itemsPerRow);

            // Ensure reasonable bounds
            itemsPerRow = Math.max(1, Math.min(itemsPerRow, 16));
            rowCount = Math.max(1, Math.min(rowCount, 12));

            columns = itemsPerRow;
            rows = rowCount;
        } else {
            columns = 4;
            rows = 2;
        }

        // Calculate window size
        int gridW = columns * ITEM_SIZE + (columns + 1) * ITEM_PADDING;
        int gridH = rows * (ITEM_SIZE + ITEM_RATE_HEIGHT) + (rows + 1) * ITEM_PADDING;

        windowW = Math.max(gridW + 20, 180); // Minimum width for header text
        windowH = HEADER_HEIGHT + gridH + FOOTER_HEIGHT + 10;

        // Ensure window fits on screen
        windowW = Math.min(windowW, screenW - 40);
        windowH = Math.min(windowH, screenH - 40);

        // Center on screen
        windowX = (screenW - windowW) / 2;
        windowY = (screenH - windowH) / 2;

        // Grid position
        gridStartX = windowX + (windowW - gridW) / 2;
        gridStartY = windowY + HEADER_HEIGHT + 5;
    }

    /**
     * Handle mouse click.
     * @return true if the click was handled by this window
     */
    public boolean handleClick(int mouseX, int mouseY, int mouseButton) {
        if (!visible) return false;

        // Check if click is outside window (close on outside click)
        if (mouseX < windowX || mouseX > windowX + windowW ||
            mouseY < windowY || mouseY > windowY + windowH) {
            hide();

            return true;
        }

        // Check if clicking on a hovered item - integrate with JEI
        if (hoveredItemIndex >= 0 && currentResult != null && hoveredItemIndex < currentResult.drops.size()) {
            DropEntry entry = currentResult.drops.get(hoveredItemIndex);

            if (mouseButton == 0) {
                // Left click - show recipes that use this item (uses)
                if (JEIHelper.showItemUses(entry.stack)) {
                    hideForJEI();

                    return true;
                }
            } else if (mouseButton == 1) {
                // Right click - show recipes that produce this item (recipes)
                if (JEIHelper.showItemRecipes(entry.stack)) {
                    hideForJEI();

                    return true;
                }
            }
        }

        // Click inside window consumes the event
        return true;
    }

    /**
     * Handle key press.
     * @return true if the key was handled
     */
    public boolean handleKey(int keyCode) {
        if (!visible) return false;

        if (keyCode == Keyboard.KEY_ESCAPE) {
            hide();

            return true;
        }

        // JEI keybinds - U for recipes (uses), R for uses (recipes that output this item)
        if (hoveredItemIndex >= 0 && currentResult != null && hoveredItemIndex < currentResult.drops.size()) {
            DropEntry entry = currentResult.drops.get(hoveredItemIndex);

            if (keyCode == Keyboard.KEY_U) {
                // U key - show recipes that use this item (uses)
                if (JEIHelper.showItemUses(entry.stack)) {
                    hideForJEI();

                    return true;
                }
            } else if (keyCode == Keyboard.KEY_R) {
                // R key - show recipes that produce this item (recipes)
                if (JEIHelper.showItemRecipes(entry.stack)) {
                    hideForJEI();

                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if the mouse is hovering over this window.
     */
    public boolean isMouseOver(int mouseX, int mouseY) {
        if (!visible) return false;

        return mouseX >= windowX && mouseX <= windowX + windowW &&
               mouseY >= windowY && mouseY <= windowY + windowH;
    }

    /**
     * Draw the drops window.
     */
    public void draw(int mouseX, int mouseY, float partialTicks) {
        if (!visible) return;

        update();

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;

        // Draw semi-transparent overlay behind the modal
        Gui.drawRect(0, 0, mc.displayWidth, mc.displayHeight, 0x80000000);

        // Draw window background
        Gui.drawRect(windowX - 1, windowY - 1, windowX + windowW + 1, windowY + windowH + 1, 0x80303030);
        Gui.drawRect(windowX, windowY, windowX + windowW, windowY + windowH, 0x801A1A1A);

        // Draw header
        String title = I18n.format("gui.mobtracker.drops.title", entityName);
        String elidedTitle = font.trimStringToWidth(title, windowW - 16);
        if (!elidedTitle.equals(title)) elidedTitle += "…";
        font.drawString(elidedTitle, windowX + 6, windowY + 6, 0xFFFFFF);

        // Draw footer
        hoveringSimulationCount = false;
        if (currentResult != null) {
            int footerY = windowY + windowH - FOOTER_HEIGHT + 2;
            String footer = I18n.format("gui.mobtracker.drops.simulationCount", currentResult.simulationCount);

            if (mouseX >= windowX + 6 && mouseX <= windowX + 6 + font.getStringWidth(footer) && mouseY >= footerY && mouseY <= footerY + 10) {
                hoveringSimulationCount = true;
            }

            font.drawString(footer, windowX + 6, footerY, hoveringSimulationCount ? 0xFFFFAA : 0xCCCCCC);
        }

        // Draw content based on simulation state
        if (currentTask == null) {
            String msg = I18n.format("gui.mobtracker.drops.noSimulation");
            font.drawString(msg, windowX + 10, gridStartY + 10, 0xAAAAAA);
        } else if (!currentTask.completed) {
            // Show progress
            String msg = I18n.format("gui.mobtracker.drops.simulating", currentTask.progress.get(), currentTask.total);
            int textW = font.getStringWidth(msg);
            font.drawString(msg, windowX + (windowW - textW) / 2, gridStartY + 20, 0xFFFF00);

            // Draw progress bar
            int barW = windowW - 40;
            int barH = 8;
            int barX = windowX + 20;
            int barY = gridStartY + 40;
            float progress = (float) currentTask.progress.get() / currentTask.total;

            Gui.drawRect(barX, barY, barX + barW, barY + barH, 0xFF333333);
            Gui.drawRect(barX, barY, barX + (int)(barW * progress), barY + barH, 0xFF00AA00);
        } else if (currentTask.errorMessage != null) {
            // Show error message (use I18n if it looks like a translation key)
            String errorMsg = currentTask.errorMessage;
            if (errorMsg.startsWith("gui.")) errorMsg = I18n.format(errorMsg);

            List<String> lines = Utils.wrapText(font, errorMsg, windowW - 10);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineX = windowX + (windowW - font.getStringWidth(line)) / 2;
                int lineY = gridStartY + 20 + i * 10 - (lines.size() - 1) * 5;
                font.drawString(line, lineX, lineY, 0xFF6666);
            }
        } else if (currentResult == null || currentResult.drops.isEmpty()) {
            String msg = I18n.format("gui.mobtracker.drops.noDrops");
            List<String> lines = Utils.wrapText(font, msg, windowW - 10);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineX = windowX + (windowW - font.getStringWidth(line)) / 2;
                int lineY = gridStartY + 20 + i * 10 - (lines.size() - 1) * 5;
                font.drawString(line, lineX, lineY, 0xFF6666);
            }
        } else {
            drawItemGrid(mouseX, mouseY);
        }
    }

    /**
     * Draw the item grid.
     */
    private void drawItemGrid(int mouseX, int mouseY) {
        if (currentResult == null || currentResult.drops == null) return;

        float textScale = 0.5f;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        List<DropEntry> drops = currentResult.drops;

        hoveredItemIndex = -1;
        hoveringDropRate = false;
        dropRateHoverIndex = -1;

        GlStateManager.pushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();

        for (int i = 0; i < drops.size() && i < columns * rows; i++) {
            int col = i % columns;
            int row = i / columns;
            int itemX = gridStartX + ITEM_PADDING + col * (ITEM_SIZE + ITEM_PADDING);
            int itemY = gridStartY + ITEM_PADDING + row * (ITEM_SIZE + ITEM_PADDING + ITEM_RATE_HEIGHT);

            DropEntry entry = drops.get(i);

            // Draw item slot background
            Gui.drawRect(itemX - 1, itemY - 1, itemX + 17, itemY + 17, 0xFF373737);

            // Check if hovering over item
            boolean hoveringItem = mouseX >= itemX && mouseX < itemX + 16 &&
                                   mouseY >= itemY && mouseY < itemY + 16;
            if (hoveringItem) {
                hoveredItemIndex = i;
                Gui.drawRect(itemX - 1, itemY - 1, itemX + 17, itemY + 17, 0xFF555555);
            }

            // Render item
            mc.getRenderItem().renderItemIntoGUI(entry.stack, itemX, itemY);

            // Draw drop rate below item
            String rate = entry.formatDropsPerKill();
            int rateW = (int)(font.getStringWidth(rate) * textScale);
            int rateX = itemX + (16 - rateW) / 2;
            int rateY = itemY + 18;

            // Check if hovering over drop rate text
            boolean hoveringRate = mouseX >= rateX - 1 && mouseX <= rateX + rateW + 1 &&
                                   mouseY >= rateY && mouseY <= rateY + 8;
            if (hoveringRate) {
                hoveringDropRate = true;
                dropRateHoverIndex = i;
            }

            GlStateManager.pushMatrix();
            GlStateManager.scale(textScale, textScale, 1.0f);
            int rateColor = hoveringRate ? 0xFFFFAA : 0xCCCCCC;
            font.drawString(rate, (int) (rateX / textScale), (int) (rateY / textScale), rateColor);
            GlStateManager.popMatrix();
        }

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.popMatrix();
    }

    /**
     * Draw tooltips after everything else.
     */
    public void drawTooltips(int mouseX, int mouseY) {
        if (!visible || currentResult == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        List<DropEntry> drops = currentResult.drops;

        // Item tooltip
        if (hoveredItemIndex >= 0 && hoveredItemIndex < drops.size()) {
            DropEntry entry = drops.get(hoveredItemIndex);
            List<String> tooltip = entry.stack.getTooltip(mc.player, mc.gameSettings.advancedItemTooltips ?
                ITooltipFlag.TooltipFlags.ADVANCED : ITooltipFlag.TooltipFlags.NORMAL);

            GlStateManager.pushMatrix();
            GlStateManager.translate(0, 0, 500);
            drawHoveringText(tooltip, mouseX, mouseY, mc.fontRenderer);
            GlStateManager.popMatrix();
        }

        // Drop rate tooltip
        if (hoveringDropRate && dropRateHoverIndex >= 0 && dropRateHoverIndex < drops.size()) {
            DropEntry entry = drops.get(dropRateHoverIndex);
            String tooltip = I18n.format("gui.mobtracker.drops.rateTooltip", entry.formatDropsPerKill());

            GlStateManager.pushMatrix();
            GlStateManager.translate(0, 0, 500);
            drawHoveringText(Collections.singletonList(tooltip), mouseX, mouseY, mc.fontRenderer);
            GlStateManager.popMatrix();
        }

        // Simulation count tooltip
        if (hoveringSimulationCount) {
            String tooltip = I18n.format("gui.mobtracker.drops.simulationCountTooltipDisclaimer");

            GlStateManager.pushMatrix();
            GlStateManager.translate(0, 0, 500);
            drawHoveringText(Collections.singletonList(tooltip), mouseX, mouseY, mc.fontRenderer);
            GlStateManager.popMatrix();
        }
    }

    /**
     * Draw hovering text (tooltip).
     */
    private void drawHoveringText(List<String> textLines, int x, int y, FontRenderer font) {
        if (textLines.isEmpty()) return;

        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        // Wrap long lines to fit within window bounds (considering 8px padding)
        int maxAllowedWidth = screenW - x - 8;
        List<String> wrappedLines = new ArrayList<>();
        for (String line : textLines) {
            int lineWidth = font.getStringWidth(line);
            if (lineWidth > maxAllowedWidth) {
                // Split line into multiple lines that fit
                List<String> split = font.listFormattedStringToWidth(line, maxAllowedWidth);
                wrappedLines.addAll(split);
            } else {
                wrappedLines.add(line);
            }
        }

        int maxWidth = 0;
        for (String s : wrappedLines) {
            int w = font.getStringWidth(s);
            if (w > maxWidth) maxWidth = w;
        }

        int posX = x + 12;
        int posY = y - 12;

        int height = 8;
        if (wrappedLines.size() > 1) height += 2 + (wrappedLines.size() - 1) * 10;

        if (posX + maxWidth > screenW) posX -= 28 + maxWidth;
        if (posY + height + 6 > screenH) posY = screenH - height - 6;

        int bgColor = 0xF0100010;
        int borderColorStart = 0x505000FF;
        int borderColorEnd = 0x5028007F;

        Gui.drawRect(posX - 3, posY - 4, posX + maxWidth + 3, posY - 3, bgColor);
        Gui.drawRect(posX - 3, posY + height + 3, posX + maxWidth + 3, posY + height + 4, bgColor);
        Gui.drawRect(posX - 3, posY - 3, posX + maxWidth + 3, posY + height + 3, bgColor);
        Gui.drawRect(posX - 4, posY - 3, posX - 3, posY + height + 3, bgColor);
        Gui.drawRect(posX + maxWidth + 3, posY - 3, posX + maxWidth + 4, posY + height + 3, bgColor);

        Gui.drawRect(posX - 3, posY - 3 + 1, posX - 3 + 1, posY + height + 3 - 1, borderColorStart);
        Gui.drawRect(posX + maxWidth + 2, posY - 3 + 1, posX + maxWidth + 3, posY + height + 3 - 1, borderColorStart);
        Gui.drawRect(posX - 3, posY - 3, posX + maxWidth + 3, posY - 3 + 1, borderColorStart);
        Gui.drawRect(posX - 3, posY + height + 2, posX + maxWidth + 3, posY + height + 3, borderColorEnd);

        for (int i = 0; i < wrappedLines.size(); i++) {
            String line = wrappedLines.get(i);
            font.drawStringWithShadow(line, posX, posY, -1);
            posY += (i == 0) ? 12 : 10;
        }

        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        RenderHelper.enableStandardItemLighting();
        GlStateManager.enableRescaleNormal();
    }
}
