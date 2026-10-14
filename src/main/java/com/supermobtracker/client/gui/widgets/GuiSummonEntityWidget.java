package com.supermobtracker.client.gui.widgets;

import java.util.List;

import com.supermobtracker.client.gui.GuiMobTracker;
import com.supermobtracker.client.gui.GuiScrollableTextWidget;
import com.supermobtracker.client.gui.GuiScrollableTextWidget.Layout;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;


public class GuiSummonEntityWidget extends GuiScrollableTextWidget {
    private final GuiMobTracker tracker;
    private final ResourceLocation targetId;

    public GuiSummonEntityWidget(FontRenderer fontRenderer, String text, GuiMobTracker tracker,
            ResourceLocation targetId) {
        super(fontRenderer, text, 0xFFDD55, 0xFFFF55, 12, 0, Layout.WRAPPED, 6, 0);
        this.tracker = tracker;
        this.targetId = targetId;
    }

    @Override
    public boolean onClick(int mouseButton) {
        if (mouseButton != 0) return false;

        tracker.selectSummonEntity(targetId);
        return true;
    }

    @Override
    public List<String> getTooltip() {
        return tracker.getSummonEntityTooltip(targetId);
    }
}
