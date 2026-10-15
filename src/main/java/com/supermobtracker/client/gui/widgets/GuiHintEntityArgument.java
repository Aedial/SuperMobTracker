package com.supermobtracker.client.gui.widgets;

import java.util.List;

import net.minecraft.util.ResourceLocation;

import com.supermobtracker.client.gui.GuiMobTracker;


public class GuiHintEntityArgument extends GuiHintArgument {
    private final GuiMobTracker tracker;
    private final ResourceLocation entityId;

    public GuiHintEntityArgument(String text, GuiMobTracker tracker, ResourceLocation entityId) {
        super(text);
        this.tracker = tracker;
        this.entityId = entityId;
    }

    @Override
    public int getColor() {
        return 0xFFDD55;
    }

    @Override
    public int getHoverColor() {
        return 0xFFFF55;
    }

    @Override
    public boolean onClick(int mouseButton) {
        if (mouseButton != 0) return false;

        tracker.selectHintEntity(entityId);
        return true;
    }

    @Override
    public List<String> getTooltip() {
        return tracker.getHintEntityTooltip(entityId);
    }
}
