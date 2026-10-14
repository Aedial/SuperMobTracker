package com.supermobtracker.client.gui.widgets;

import java.util.List;

import com.supermobtracker.client.gui.GuiMobTracker;
import com.supermobtracker.client.gui.GuiScrollableTextWidget;

import net.minecraft.client.gui.FontRenderer;


public class GuiBiomeWidget extends GuiScrollableTextWidget {
    private final GuiMobTracker tracker;
    private final List<String> biomes;

    public GuiBiomeWidget(FontRenderer fontRenderer, String text, int color, int lineHeight, int horizontalOffset,
            int widthOffset, GuiMobTracker tracker, List<String> biomes) {
        super(fontRenderer, text, color, color, lineHeight, 0, Layout.SINGLE, horizontalOffset, widthOffset);
        this.tracker = tracker;
        this.biomes = biomes;
    }

    @Override
    public boolean onClick(int mouseButton) {
        if (mouseButton != 0 || biomes.isEmpty()) return false;

        return tracker.copyBiomes(biomes);
    }
}
