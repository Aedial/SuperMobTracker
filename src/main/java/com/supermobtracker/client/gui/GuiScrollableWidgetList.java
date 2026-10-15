package com.supermobtracker.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;


public class GuiScrollableWidgetList {
    private List<GuiScrollableWidget> widgets = Collections.emptyList();
    private int[] widgetOffsets = new int[0];
    private int[] widgetEnds = new int[0];
    private int x;
    private int y;
    private int width;
    private int height;
    private int contentLeftPadding;
    private int contentRightPadding;
    private int contentWidth;
    private int contentHeight;
    private int scrollOffset;
    private int maxScrollOffset;
    private int firstVisibleWidget;
    private int lastVisibleWidget;
    private GuiScrollableWidget hoveredWidget;
    private int hoveredMouseX;
    private int hoveredMouseY;
    private boolean layoutDirty = true;
    private boolean visibleRangeDirty = true;
    private boolean draggingScrollbar;
    private boolean visible = true;

    public void replaceWidgets(List<? extends GuiScrollableWidget> widgets) {
        this.widgets = Collections.unmodifiableList(new ArrayList<>(widgets));
        hoveredWidget = null;
        layoutDirty = true;
        visibleRangeDirty = true;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) {
            hoveredWidget = null;
            draggingScrollbar = false;
        }
    }

    public void setBounds(int x, int y, int width, int height) {
        int safeWidth = Math.max(0, width);
        int safeHeight = Math.max(0, height);
        if (this.x == x && this.y == y && this.width == safeWidth && this.height == safeHeight) return;

        boolean widthChanged = this.width != safeWidth;
        boolean heightChanged = this.height != safeHeight;
        this.x = x;
        this.y = y;
        this.width = safeWidth;
        this.height = safeHeight;
        if (widthChanged) layoutDirty = true;
        if (heightChanged && !layoutDirty) {
            maxScrollOffset = Math.max(0, contentHeight - height);
            scrollOffset = Math.min(maxScrollOffset, scrollOffset);
        }

        visibleRangeDirty = true;
    }

    public void setContentPadding(int left, int right) {
        int safeLeft = Math.max(0, left);
        int safeRight = Math.max(0, right);
        if (contentLeftPadding == safeLeft && contentRightPadding == safeRight) return;

        contentLeftPadding = safeLeft;
        contentRightPadding = safeRight;
        layoutDirty = true;
        visibleRangeDirty = true;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public void draw(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) return;

        ensureLayout();
        updateVisibleRange();
        hoveredWidget = widgetAt(mouseX, mouseY);
        hoveredMouseX = mouseX;
        hoveredMouseY = mouseY;
        if (width <= 0 || height <= 0 || contentWidth <= 0) return;

        if (hoveredWidget != null) {
            hoveredWidget.onHover(mouseX, mouseY, x + contentLeftPadding, getWidgetY(hoveredWidget), contentWidth);
        }

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        ScaledResolution resolution = new ScaledResolution(minecraft);
        int scaleFactor = resolution.getScaleFactor();
        int scissorY = (resolution.getScaledHeight() - y - height) * scaleFactor;
        GL11.glScissor(x * scaleFactor, scissorY, width * scaleFactor, height * scaleFactor);
        try {
            int contentX = x + contentLeftPadding;
            int viewportBottom = y + height;
            for (int i = firstVisibleWidget; i < lastVisibleWidget; i++) {
                int widgetY = y + widgetOffsets[i] - scrollOffset;
                GuiScrollableWidget widget = widgets.get(i);
                if (!widget.isVisible()) continue;

                widget.draw(contentX, widgetY, contentWidth, viewportBottom - widgetY, widget == hoveredWidget);
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        drawScrollbar();
    }

    public boolean handleClick(int mouseX, int mouseY, int mouseButton) {
        if (!visible) return false;

        ensureLayout();
        updateVisibleRange();
        if (!isMouseOver(mouseX, mouseY)) return false;

        if (isMouseOverScrollbar(mouseX, mouseY)) {
            if (mouseButton != 0) return false;
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);

            return true;
        }

        GuiScrollableWidget widget = widgetAt(mouseX, mouseY);
        if (widget == null) return false;

        return widget.onClick(mouseX, mouseY, x + contentLeftPadding, getWidgetY(widget), contentWidth, mouseButton);
    }

    public void scroll(int amount) {
        ensureLayout();
        setScrollOffset(scrollOffset + amount);
    }

    public void ensureVisible(int index) {
        ensureLayout();
        if (index < 0 || index >= widgets.size()) return;

        int widgetTop = widgetOffsets[index];
        int widgetBottom = widgetEnds[index];
        if (widgetTop < scrollOffset) {
            setScrollOffset(widgetTop);
        } else if (widgetBottom > scrollOffset + height) {
            setScrollOffset(widgetBottom - height);
        }
    }

    public void scrollToTop() {
        setScrollOffset(0);
    }

    public void onMouseDrag(int mouseY) {
        if (!draggingScrollbar) return;

        ensureLayout();
        updateScrollFromMouse(mouseY);
    }

    public void onMouseRelease() {
        draggingScrollbar = false;
    }

    public List<String> getHoveredTooltip() {
        if (hoveredWidget == null) return Collections.emptyList();

        List<String> tooltip = hoveredWidget.getTooltip(hoveredMouseX, hoveredMouseY, x + contentLeftPadding,
            getWidgetY(hoveredWidget), contentWidth);
        return tooltip != null ? tooltip : Collections.emptyList();
    }

    public boolean isHovered(GuiScrollableWidget widget) {
        return widget != null && hoveredWidget == widget;
    }

    private void ensureLayout() {
        int newContentWidth = Math.max(0, width - contentLeftPadding - contentRightPadding);
        if (!layoutDirty && contentWidth == newContentWidth) return;

        contentWidth = newContentWidth;
        widgetOffsets = new int[widgets.size()];
        widgetEnds = new int[widgets.size()];
        int offset = 0;
        for (int i = 0; i < widgets.size(); i++) {
            widgetOffsets[i] = offset;
            GuiScrollableWidget widget = widgets.get(i);
            if (widget.isVisible()) offset += Math.max(0, widget.getHeight(contentWidth));
            widgetEnds[i] = offset;
        }

        contentHeight = offset;
        maxScrollOffset = Math.max(0, contentHeight - height);
        scrollOffset = Math.min(maxScrollOffset, Math.max(0, scrollOffset));
        layoutDirty = false;
        visibleRangeDirty = true;
    }

    private void updateVisibleRange() {
        if (!visibleRangeDirty) return;

        int viewportBottom = scrollOffset + height;
        firstVisibleWidget = findFirstEndAfter(scrollOffset);
        lastVisibleWidget = findFirstOffsetAtOrAfter(viewportBottom);
        if (lastVisibleWidget < firstVisibleWidget) lastVisibleWidget = firstVisibleWidget;

        visibleRangeDirty = false;
    }

    // TODO: could optimize the binary search by assuming the size if uniform
    //       over the entire list, which could give a better window.
    private int findFirstEndAfter(int offset) {
        int low = 0;
        int high = widgetEnds.length;
        while (low < high) {
            int middle = (low + high) / 2;
            if (widgetEnds[middle] <= offset) low = middle + 1;
            else high = middle;
        }

        return low;
    }

    private int findFirstOffsetAtOrAfter(int offset) {
        int low = 0;
        int high = widgetOffsets.length;
        while (low < high) {
            int middle = (low + high) / 2;
            if (widgetOffsets[middle] < offset) low = middle + 1;
            else high = middle;
        }

        return low;
    }

    private GuiScrollableWidget widgetAt(int mouseX, int mouseY) {
        if (!isMouseOver(mouseX, mouseY)) return null;

        int contentX = x + contentLeftPadding;
        if (mouseX < contentX || mouseX >= contentX + contentWidth) return null;

        for (int i = firstVisibleWidget; i < lastVisibleWidget; i++) {
            int widgetY = y + widgetOffsets[i] - scrollOffset;
            int widgetHeight = widgetEnds[i] - widgetOffsets[i];
            GuiScrollableWidget widget = widgets.get(i);
            if (!widget.isVisible() || widgetHeight <= 0) continue;
            if (mouseY >= widgetY && mouseY < widgetY + widgetHeight) return widget;
        }

        return null;
    }

    private int getWidgetY(GuiScrollableWidget widget) {
        int index = widgets.indexOf(widget);
        return index < 0 ? y : y + widgetOffsets[index] - scrollOffset;
    }

    private void setScrollOffset(int offset) {
        int clampedOffset = Math.min(maxScrollOffset, Math.max(0, offset));
        if (scrollOffset == clampedOffset) return;

        scrollOffset = clampedOffset;
        visibleRangeDirty = true;
    }

    private void drawScrollbar() {
        if (maxScrollOffset <= 0) return;

        int barX1 = x + width - 4;
        int barX2 = x + width - 2;
        int barHeight = getScrollbarHeight();
        int barTravel = height - barHeight;
        int barY = y + Math.round(barTravel * ((float) scrollOffset / maxScrollOffset));

        Gui.drawRect(barX1, y, barX2, y + height, 0x40000000);
        Gui.drawRect(barX1, barY, barX2, barY + barHeight, 0x80FFFFFF);
    }

    private boolean isMouseOverScrollbar(int mouseX, int mouseY) {
        if (maxScrollOffset <= 0) return false;

        return mouseX >= x + width - 4 && mouseX <= x + width - 2 && mouseY >= y && mouseY <= y + height;
    }

    private void updateScrollFromMouse(int mouseY) {
        int barHeight = getScrollbarHeight();
        int barTravel = height - barHeight;
        if (barTravel <= 0) return;

        int barY = Math.max(y, Math.min(mouseY - barHeight / 2, y + barTravel));
        float ratio = (float) (barY - y) / barTravel;
        setScrollOffset(Math.round(ratio * maxScrollOffset));
    }

    private int getScrollbarHeight() {
        if (contentHeight <= 0) return height;

        return Math.min(height, Math.max(8, (int) (height * ((float) height / contentHeight))));
    }
}
