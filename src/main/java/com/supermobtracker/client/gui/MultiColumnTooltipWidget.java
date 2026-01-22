package com.supermobtracker.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;


/**
 * A reusable widget for displaying lines in a multi-column layout.
 * This widget handles layout calculation, positioning, and rendering of a list
 * of lines in a tooltip that appears when hovering over a label.
 */
public class MultiColumnTooltipWidget {

    private final FontRenderer fontRenderer;

    // Label bounds (the area that triggers the tooltip)
    private int labelX;
    private int labelY;
    private int labelWidth;
    private int labelHeight = 12;

    // Lines to display
    private List<String> lines = null;

    // Screen dimensions for positioning
    private int screenWidth;
    private int screenHeight;

    public MultiColumnTooltipWidget(FontRenderer fontRenderer) {
        this.fontRenderer = fontRenderer;
    }

    /**
     * Sets the data to display in the tooltip.
     * @param lines List of translated names (can be null to hide tooltip)
     * @param labelX X position of the label that triggers the tooltip
     * @param labelY Y position of the label
     * @param labelWidth Width of the label
     */
    public void setData(List<String> lines, int labelX, int labelY, int labelWidth) {
        this.lines = lines;
        this.labelX = labelX;
        this.labelY = labelY;
        this.labelWidth = labelWidth;
    }

    /**
     * Clears the tooltip data.
     */
    public void clear() {
        this.lines = null;
    }

    /**
     * Updates screen dimensions for proper tooltip positioning.
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    public void updateScreenSize(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /**
     * Checks if the tooltip should be shown based on mouse position.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if mouse is hovering over the label
     */
    public boolean isHovered(int mouseX, int mouseY) {
        if (lines == null || lines.isEmpty()) return false;

        return mouseX >= labelX && mouseX <= labelX + labelWidth &&
               mouseY >= labelY && mouseY <= labelY + labelHeight;
    }

    /**
     * Draws the tooltip if the mouse is hovering over the label.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    public void draw(int mouseX, int mouseY) {
        if (!isHovered(mouseX, mouseY)) return;

        int panelH = screenHeight - 40;

        // Screen edge padding (5% of height)
        int edgePadding = panelH / 20;

        // Calculate multi-column layout to show all lines
        int lineHeight = 10;
        int columnPadding = 8;
        int availableHeight = screenHeight - edgePadding * 2;
        int maxLinesPerColumn = Math.max(1, availableHeight / lineHeight);

        // Calculate how many columns we need
        int totalLines = lines.size();
        int numColumns = (int) Math.ceil((double) totalLines / maxLinesPerColumn);

        // Calculate column widths based on content
        List<Integer> columnWidths = new ArrayList<>();
        for (int col = 0; col < numColumns; col++) {
            int startIdx = col * maxLinesPerColumn;
            int endIdx = Math.min(startIdx + maxLinesPerColumn, totalLines);
            int maxWidth = 0;
            for (int i = startIdx; i < endIdx; i++) {
                int w = fontRenderer.getStringWidth(lines.get(i));
                if (w > maxWidth) maxWidth = w;
            }
            columnWidths.add(maxWidth);
        }

        // Calculate total tooltip dimensions
        int tooltipW = columnWidths.stream().mapToInt(Integer::intValue).sum() + columnPadding * (numColumns + 1);
        int linesInTooltip = Math.min(totalLines, maxLinesPerColumn);
        int tooltipH = linesInTooltip * lineHeight + 6;

        // Clamp tooltip width to screen width
        int maxTooltipW = screenWidth - 8;
        if (tooltipW > maxTooltipW) tooltipW = maxTooltipW;

        // Position tooltip: prefer top-left of cursor, then try other positions if it overflows
        int boxX = mouseX - tooltipW - 12;
        if (boxX < edgePadding) {
            boxX = mouseX + 12;
            if (boxX + tooltipW > screenWidth - edgePadding) boxX = edgePadding;
        }

        // Position vertically: prefer above cursor, then below if it overflows
        int boxY = mouseY - tooltipH - 12;
        if (boxY < edgePadding) {
            boxY = mouseY + 12;
            if (boxY + tooltipH > screenHeight - edgePadding) boxY = edgePadding;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0F, 0.0F, 400.0F); // ensure tooltip is on top

        // Draw border and background
        Gui.drawRect(boxX - 1, boxY - 1, boxX + tooltipW + 1, boxY + tooltipH + 1, 0xFF505050);
        Gui.drawRect(boxX, boxY, boxX + tooltipW, boxY + tooltipH, 0xF0100010);

        // Draw lines in columns
        int xOffset = boxX + columnPadding;
        for (int col = 0; col < numColumns; col++) {
            int startIdx = col * maxLinesPerColumn;
            int endIdx = Math.min(startIdx + maxLinesPerColumn, totalLines);
            for (int i = startIdx; i < endIdx; i++) {
                int row = i - startIdx;
                fontRenderer.drawString(lines.get(i), xOffset, boxY + 3 + row * lineHeight, 0xDDDDDD);
            }
            if (col < columnWidths.size()) xOffset += columnWidths.get(col) + columnPadding;
        }
        GlStateManager.popMatrix();
    }

    /**
     * Gets the lines currently set.
     * @return The lines, or null if not set
     */
    public List<String> getLines() {
        return lines;
    }
}
