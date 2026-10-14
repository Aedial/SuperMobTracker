package com.supermobtracker.client.gui;

public class GuiScrollableSpacer extends GuiScrollableWidget {
    private final int height;

    public GuiScrollableSpacer(int height) {
        this.height = Math.max(0, height);
    }

    @Override
    public int getHeight(int width) {
        return height;
    }

    @Override
    public void draw(int x, int y, int width, int remainingYSpace, boolean hovered) {
    }
}
