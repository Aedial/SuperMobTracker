package com.supermobtracker.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;


public class GuiTrackerTextButton extends GuiScrollableWidget {
    private final FontRenderer fontRenderer;
    private final int height;
    private final String text;
    private final int width;

    public GuiTrackerTextButton(FontRenderer fontRenderer, String text, int height) {
        this.fontRenderer = fontRenderer;
        this.height = height;
        this.text = text;
        width = fontRenderer.getStringWidth(text) + 8;
    }

    public void draw(int x, int y, int mouseX, int mouseY) {
        drawButton(x, y, isHovered(x, y, mouseX, mouseY));
    }

    public boolean isHovered(int x, int y, int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }


    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public int getHeight(int width) {
        return height;
    }

    @Override
    public void draw(int x, int y, int width, int remainingYSpace, boolean hovered) {
        drawButton(x, y, hovered);
    }

    private void drawButton(int x, int y, boolean hovered) {
        int backgroundColor = hovered ? 0x60FFFFFF : 0x40FFFFFF;
        int textColor = hovered ? 0xFFFFAA : 0xCCCCCC;

        Gui.drawRect(x, y, x + width, y + height, backgroundColor);
        fontRenderer.drawString(text, x + 4, y + 2, textColor);
    }
}
