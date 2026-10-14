package com.supermobtracker.client.gui;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;

import com.supermobtracker.util.Utils;


public class GuiScrollableTextWidget extends GuiScrollableWidget {
    public enum Layout {
        WRAPPED,
        SINGLE,
        ELIDED
    }

    private final int ellipsisWidth;

    private final FontRenderer fontRenderer;
    private final String text;
    private final int color;
    private final int hoverColor;
    private final int lineHeight;
    private final int lineSpacing;
    private final int horizontalOffset;
    private final int widthOffset;
    private final Layout layout;
    private List<String> lines = Collections.emptyList();
    private int cachedWidth = -1;

    public GuiScrollableTextWidget(FontRenderer fontRenderer, String text, int color, int lineHeight, Layout layout,
            int horizontalOffset, int widthOffset) {
        this(fontRenderer, text, color, color, lineHeight, 0, layout, horizontalOffset, widthOffset);
    }

    public GuiScrollableTextWidget(FontRenderer fontRenderer, String text, int color, int hoverColor, int lineHeight,
            int lineSpacing, Layout layout, int horizontalOffset, int widthOffset) {
        this.fontRenderer = fontRenderer;
        this.text = text;
        this.color = color;
        this.hoverColor = hoverColor;
        this.lineHeight = lineHeight;
        this.lineSpacing = lineSpacing;
        this.layout = layout;
        this.horizontalOffset = horizontalOffset;
        this.widthOffset = widthOffset;
        this.ellipsisWidth = Math.max(1, fontRenderer.getStringWidth("..."));
    }

    @Override
    public int getHeight(int width) {
        updateLines(width);
        return lineHeight * lines.size() + lineSpacing * Math.max(0, lines.size() - 1);
    }

    @Override
    public void draw(int x, int y, int width, int remainingYSpace, boolean hovered) {
        updateLines(width);
        int drawY = y;
        int drawColor = hovered ? hoverColor : color;
        for (int i = 0; i < lines.size(); i++) {
            fontRenderer.drawString(lines.get(i), x + horizontalOffset, drawY, drawColor);
            drawY += lineHeight + (i < lines.size() - 1 ? lineSpacing : 0);
        }
    }

    private void updateLines(int width) {
        int textAreaWidth = Math.max(1, width + widthOffset);
        if (cachedWidth == textAreaWidth) return;

        cachedWidth = textAreaWidth;
        if (layout == Layout.WRAPPED) {
            lines = Utils.wrapText(fontRenderer, text, textAreaWidth);
        } else if (layout == Layout.ELIDED) {
            String elided = fontRenderer.trimStringToWidth(text, textAreaWidth - ellipsisWidth);
            if (!elided.equals(text)) elided += "...";
            lines = Collections.singletonList(elided);
        } else {
            lines = Collections.singletonList(text);
        }

    }
}
