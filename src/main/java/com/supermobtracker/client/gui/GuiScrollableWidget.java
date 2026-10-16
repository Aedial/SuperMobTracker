package com.supermobtracker.client.gui;

import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;


public abstract class GuiScrollableWidget {
    private boolean visible = true;

    public abstract int getHeight(int width);

    public abstract void draw(int x, int y, int width, int remainingYSpace, boolean hovered);

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean onClick(int mouseButton) {
        return false;
    }

    public boolean onClick(int mouseX, int mouseY, int x, int y, int width, int mouseButton) {
        return onClick(mouseButton);
    }

    public void onHover() {
    }

    public void onHover(int mouseX, int mouseY, int x, int y, int width) {
        onHover();
    }

    public List<String> getTooltip() {
        return Collections.emptyList();
    }

    public List<String> getTooltip(int mouseX, int mouseY, int x, int y, int width) {
        return getTooltip();
    }

    public ItemStack getTooltipStack(int mouseX, int mouseY, int x, int y, int width) {
        return ItemStack.EMPTY;
    }
}
