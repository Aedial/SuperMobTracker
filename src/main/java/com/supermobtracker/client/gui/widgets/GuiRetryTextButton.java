package com.supermobtracker.client.gui.widgets;

import com.supermobtracker.client.gui.GuiMobTracker;
import com.supermobtracker.client.gui.GuiTrackerTextButton;

import net.minecraft.client.gui.FontRenderer;


public class GuiRetryTextButton extends GuiTrackerTextButton {
    private final GuiMobTracker tracker;

    public GuiRetryTextButton(FontRenderer fontRenderer, String text, int height, GuiMobTracker tracker) {
        super(fontRenderer, text, height);
        this.tracker = tracker;
    }

    @Override
    public boolean onClick(int mouseButton) {
        return mouseButton == 0 && tracker.retrySelectedEntity();
    }
}
