package com.supermobtracker.client.gui;

import java.util.Collections;
import java.util.List;

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

    public void onHover() {
    }

    public List<String> getTooltip() {
        return Collections.emptyList();
    }
}
