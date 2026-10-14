package com.supermobtracker.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;


public class GuiScrollableWidgetFactory {
    private final FontRenderer fontRenderer;
    private final List<GuiScrollableWidget> widgets = new ArrayList<>();

    public GuiScrollableWidgetFactory(FontRenderer fontRenderer) {
        this.fontRenderer = fontRenderer;
    }

    public GuiScrollableTextWidget addText(String text, int color, int lineHeight) {
        return addText(text, color, color, lineHeight, 0, GuiScrollableTextWidget.Layout.SINGLE, 0, 0);
    }

    public GuiScrollableTextWidget addWrappedText(String text, int color, int lineHeight, int horizontalOffset,
            int widthOffset) {
        return addText(text, color, color, lineHeight, 0, GuiScrollableTextWidget.Layout.WRAPPED,
            horizontalOffset, widthOffset);
    }

    public GuiScrollableTextWidget addElidedText(String text, int color, int hoverColor, int lineHeight,
            int horizontalOffset, int widthOffset) {
        return addText(text, color, hoverColor, lineHeight, 0, GuiScrollableTextWidget.Layout.ELIDED,
            horizontalOffset, widthOffset);
    }

    public <T extends GuiTrackerTextButton> T addButton(T button) {
        widgets.add(button);
        return button;
    }

    public GuiScrollableWidgetFactory addSpacing(int height) {
        widgets.add(new GuiScrollableSpacer(height));
        return this;
    }

    public <T extends GuiScrollableWidget> T addWidget(T widget) {
        widgets.add(widget);
        return widget;
    }

    public List<GuiScrollableWidget> build() {
        return new ArrayList<>(widgets);
    }

    private GuiScrollableTextWidget addText(String text, int color, int hoverColor, int lineHeight,
            int lineSpacing, GuiScrollableTextWidget.Layout layout, int horizontalOffset, int widthOffset) {
        GuiScrollableTextWidget widget = new GuiScrollableTextWidget(fontRenderer, text, color, hoverColor,
            lineHeight, lineSpacing, layout, horizontalOffset, widthOffset);
        widgets.add(widget);
        return widget;
    }
}
